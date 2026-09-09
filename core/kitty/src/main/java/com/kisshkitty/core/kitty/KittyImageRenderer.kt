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

    /** Unprocessed tail: at most one incomplete escape sequence. */
    private var pending = ByteArray(0)

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
    fun processBytes(data: ByteArray): KittyOutput {
        val buf = if (pending.isEmpty()) data else pending + data
        pending = ByteArray(0)
        val events = mutableListOf<OutputEvent>()
        val n = buf.size
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
            val end = escapeEnd(buf, esc, n)
            if (end == -1) {
                // Incomplete escape: emit text before it, hold the rest.
                flushText(esc)
                pos = esc
                break
            }
            if (isGraphicsApc(buf, esc, end)) {
                flushText(esc)
                val innerStart = esc + 3
                val innerEnd = end - 2
                var semi = -1
                var j = innerStart
                while (j < innerEnd) {
                    if (buf[j] == 0x3B.toByte()) {
                        semi = j
                        break
                    }
                    j++
                }
                val control: String
                val payOff: Int
                val payLen: Int
                if (semi == -1) {
                    control = String(buf, innerStart, innerEnd - innerStart, Charsets.US_ASCII)
                    payOff = innerEnd
                    payLen = 0
                } else {
                    control = String(buf, innerStart, semi - innerStart, Charsets.US_ASCII)
                    payOff = semi + 1
                    payLen = innerEnd - payOff
                }
                for (event in parser.parseControl(control, buf, payOff, payLen)) {
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
        if (n - pos > 1_000_000) {
            flushText(n)
            pos = n
        }
        if (pos < n) {
            pending = buf.copyOfRange(pos, n)
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
    private fun escapeEnd(buf: ByteArray, esc: Int, n: Int): Int {
        if (esc + 1 >= n) return -1
        return when (buf[esc + 1].toInt().toChar()) {
            '[' -> {
                var i = esc + 2
                while (i < n && (buf[i].toInt() and 0xFF) in 0x30..0x3F) i++
                while (i < n && (buf[i].toInt() and 0xFF) in 0x20..0x2F) i++
                if (i < n) i + 1 else -1
            }
            ']', 'P', 'X', '^', '_' -> {
                var i = esc + 2
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
}
