package com.kisshkitty.core.kitty

import com.kisshkitty.core.kitty.KittyProtocolParser.DeleteSelector
import com.kisshkitty.core.kitty.KittyProtocolParser.KittyEvent
import com.kisshkitty.core.kitty.KittyProtocolParser.ShowOp

/**
 * Turns raw terminal output into clean text plus ordered Kitty events.
 *
 * Text and graphics sequences are processed in stream order so image
 * anchors line up with the cursor position at display time. Everything
 * works on bytes: image payloads are never materialized as Strings
 * (saves ~the full payload size in copies per frame).
 */
class KittyImageRenderer {

    private val parser = KittyProtocolParser()

    /**
     * Unprocessed tail (at most one incomplete escape sequence) lives in
     * [hold]/[holdLen]: a growable buffer appended in place, so a large
     * upload split over many reads is never re-concatenated.
     */
    private var hold = ByteArray(0)
    private var holdLen = 0
    /** Last time the held-back tail was extended (nanoTime). */
    private var pendingStamp = 0L

    sealed interface OutputEvent {
        data class Text(val text: String) : OutputEvent
        data class Show(val image: KittyImage, val op: ShowOp) : OutputEvent
        data class Delete(val selector: DeleteSelector) : OutputEvent
        data class Respond(val payload: String) : OutputEvent
    }

    data class KittyOutput(
        val events: List<OutputEvent>
    )

    /**
     * Process raw output bytes. Complete units become events; a trailing
     * incomplete escape (or split UTF-8 tail) is held for the next call.
     */
    fun processBytes(data: ByteArray, len: Int = data.size): KittyOutput {
        // [data] may be a caller-owned reused buffer: nothing below keeps
        // a reference to it (payloads/text are copied, the tail is cloned).
        // An unterminated escape (killed program, lost bytes) would
        // otherwise swallow every later byte, prompt and echo included.
        // A partial sequence idle for this long is certainly abandoned.
        if (holdLen > 0 && System.nanoTime() - pendingStamp > PENDING_IDLE_NANOS) {
            holdLen = 0
        }
        // The held tail was already scanned up to here: resume the
        // terminator search just before its end instead of from scratch.
        val resumeAt = if (holdLen > 0) holdLen - 1 else 0
        val buf: ByteArray
        val n: Int
        if (holdLen == 0) {
            buf = data
            n = len
        } else {
            n = holdLen + len
            if (hold.size < n) hold = hold.copyOf(maxOf(n, hold.size * 2))
            System.arraycopy(data, 0, hold, holdLen, len)
            buf = hold
        }
        val events = mutableListOf<OutputEvent>()
        var pos = 0
        var textStart = 0

        fun flushText(upTo: Int) {
            if (upTo > textStart) {
                events.add(
                    OutputEvent.Text(
                        String(buf, textStart, upTo - textStart, Charsets.UTF_8)
                    )
                )
            }
        }

        while (true) {
            val esc = indexOfByte(buf, 0x1B, pos, n)
            if (esc == -1) {
                // No more escapes: emit text up to a UTF-8-safe end and
                // hold a possibly split trailing character.
                val safe = utf8SafeEnd(buf, pos, n)
                flushText(safe)
                pos = safe
                break
            }
            val end = escapeEnd(buf, esc, n, if (esc == 0) resumeAt else 0)
            if (end == -1) {
                // Incomplete escape: emit text before it, hold the rest.
                flushText(esc)
                pos = esc
                break
            }
            if (isGraphicsApc(buf, esc, end)) {
                flushText(esc)
                val innerStart = esc + 3
                // ST is two bytes (ESC \), BEL is one.
                val innerEnd = if (buf[end - 1] == 0x07.toByte()) end - 1 else end - 2
                var semi = -1
                var j = innerStart
                while (j < innerEnd) {
                    if (buf[j] == 0x3B.toByte()) {
                        semi = j
                        break
                    }
                    j++
                }
                val ctrlEnd = if (semi == -1) innerEnd else semi
                val payOff = if (semi == -1) innerEnd else semi + 1
                val payLen = innerEnd - payOff
                // Bare continuation chunks (m=0/1[,q=N]) are nearly all of
                // video traffic: recognize them in the raw bytes, no String.
                val marker = markerOf(buf, innerStart, ctrlEnd)
                val parsed = if (marker >= 0) {
                    parser.parseMarkerChunk(marker shr 8, marker and 0xFF, buf, payOff, payLen)
                } else {
                    val control = String(buf, innerStart, ctrlEnd - innerStart, Charsets.US_ASCII)
                    parser.parseControl(control, buf, payOff, payLen)
                }
                for (event in parsed) {
                    events.add(
                        when (event) {
                            is KittyEvent.Show -> OutputEvent.Show(event.image, event.op)
                            is KittyEvent.Delete -> OutputEvent.Delete(event.selector)
                            is KittyEvent.Respond -> OutputEvent.Respond(event.payload)
                        }
                    )
                }
                pos = end
                textStart = end
            } else {
                // Other escapes pass through to the emulator, which knows
                // how to skip them. Keep them inside the text flow.
                pos = end
            }
        }

        // Cap the hold-back so an abandoned escape can never grow
        // unbounded: flush it as plain text.
        if (n - pos > MAX_HOLD_BYTES) {
            flushText(n)
            pos = n
        }
        val tail = n - pos
        if (tail > 0) {
            if (buf === hold) {
                if (pos > 0) System.arraycopy(hold, pos, hold, 0, tail)
            } else {
                if (hold.size < tail) hold = ByteArray(maxOf(tail, 1024))
                System.arraycopy(buf, pos, hold, 0, tail)
            }
            holdLen = tail
            pendingStamp = System.nanoTime()
        } else {
            holdLen = 0
            // Don't keep a huge buffer alive after a big upload.
            if (hold.size > HOLD_KEEP_BYTES) hold = ByteArray(0)
        }
        return KittyOutput(events)
    }

    private fun indexOfByte(buf: ByteArray, v: Int, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if ((buf[i].toInt() and 0xFF) == v) return i
            i++
        }
        return -1
    }

    /** Exclusive end index of the escape starting at [esc], or -1. */
    private fun escapeEnd(buf: ByteArray, esc: Int, n: Int, resumeAt: Int = 0): Int {
        if (esc + 1 >= n) return -1
        return when (buf[esc + 1].toInt().toChar()) {
            '[' -> {
                var i = esc + 2
                while (i < n && (buf[i].toInt() and 0xFF) in 0x30..0x3F) i++
                while (i < n && (buf[i].toInt() and 0xFF) in 0x20..0x2F) i++
                if (i < n) i + 1 else -1
            }
            ']', 'P', 'X', '^', '_' -> {
                var i = maxOf(esc + 2, resumeAt)
                while (i < n) {
                    val b = buf[i].toInt() and 0xFF
                    if (b == 0x07) return i + 1
                    if (b == 0x1B && i + 1 < n &&
                        (buf[i + 1].toInt() and 0xFF) == 0x5C
                    ) {
                        return i + 2
                    }
                    i++
                }
                -1
            }
            '#', '(', ')', '%', '&' -> if (esc + 2 < n) esc + 3 else -1
            else -> esc + 2
        }
    }

    /**
     * Recognizes "m=0|1" with optional ",q=<digits>" in [from, to).
     * Returns (m shl 8) or q, or -1 for anything else.
     */
    private fun markerOf(buf: ByteArray, from: Int, to: Int): Int {
        val len = to - from
        if (len < 3 || buf[from] != 'm'.code.toByte() || buf[from + 1] != '='.code.toByte()) return -1
        val mc = buf[from + 2].toInt()
        if (mc != '0'.code && mc != '1'.code) return -1
        val m = mc - '0'.code
        if (len == 3) return m shl 8
        if (len < 7 || buf[from + 3] != ','.code.toByte() ||
            buf[from + 4] != 'q'.code.toByte() || buf[from + 5] != '='.code.toByte()
        ) return -1
        var q = 0
        for (i in from + 6 until to) {
            val d = buf[i].toInt() - '0'.code
            if (d < 0 || d > 9) return -1
            q = q * 10 + d
            if (q > 0xFF) return -1
        }
        return (m shl 8) or q
    }

    private fun isGraphicsApc(buf: ByteArray, esc: Int, end: Int): Boolean {
        return end - esc >= 4 &&
            (buf[esc + 1].toInt() and 0xFF) == 0x5F &&
            (buf[esc + 2].toInt() and 0xFF) == 0x47
    }

    /**
     * Largest end index in [from, to) that doesn't split a UTF-8
     * multi-byte character.
     */
    private fun utf8SafeEnd(buf: ByteArray, from: Int, to: Int): Int {
        var i = to - 1
        var cont = 0
        while (i >= from && cont < 3 && (buf[i].toInt() and 0xC0) == 0x80) {
            i--
            cont++
        }
        if (i < from) return from
        val b = buf[i].toInt() and 0xFF
        val need = when {
            b < 0x80 -> 0
            b < 0xC0 -> 0
            b < 0xE0 -> 1
            b < 0xF0 -> 2
            else -> 3
        }
        return if (to - i - 1 >= need) to else i
    }

    /**
     * Query the terminal for Kitty graphics support.
     * Returns the query sequence to send.
     */
    fun createQuerySequence(): String {
        return "${KittyProtocolParser.APC_START}Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA${KittyProtocolParser.APC_END}"
    }

    fun getParser(): KittyProtocolParser = parser

    private companion object {
        const val PENDING_IDLE_NANOS = 3_000_000_000L
        /** Largest held-back tail (one big unchunked upload). */
        const val MAX_HOLD_BYTES = 48 * 1024 * 1024
        /** Hold buffers above this are released once drained. */
        const val HOLD_KEEP_BYTES = 4 * 1024 * 1024
    }
}
