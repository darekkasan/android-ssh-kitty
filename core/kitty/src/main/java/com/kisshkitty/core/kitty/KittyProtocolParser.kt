package com.kisshkitty.core.kitty

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

/**
 * Parser for the Kitty Graphics Protocol.
 *
 * Graphics escape codes look like:
 * ESC _ G <control data> ; <payload> ESC \
 * where control data is a comma-separated list of key=value pairs and the
 * payload is base64 encoded image data.
 *
 * Reference: https://sw.kovidgoyal.net/kitty/graphics-protocol/
 *
 * Supported: transmit (a=t), transmit+display (a=T), query (a=q),
 * put/display (a=p), delete (a=d), chunked uploads (m), zlib (o=z),
 * direct medium (t=d), ids (i), numbers (I), placements (c/r/x/y/w/h/
 * X/Y/z/C), virtual placements (U, stored but not rendered),
 * quiet levels (q=1 no OK, q=2 no responses).
 *
 * Not supported: file/shm media (t=f/t/s, answered with an error),
 * animations (a=f frames are stored as plain images, a=a/a=c ignored),
 * relative placements (P/Q treated as cursor placements),
 * Unicode placeholders (U+10EEEE rendering).
 */
class KittyProtocolParser {

    companion object {
        // APC introducer and terminator
        const val APC_START = "\u001B_"  // ESC _
        const val APC_END = "\u001B\\"    // ESC \

        // Image formats
        const val FORMAT_RGB = 24
        const val FORMAT_RGBA = 32
        const val FORMAT_PNG = 100

        // Actions
        const val ACTION_TRANSMIT = "t"
        const val ACTION_TRANSMIT_AND_DISPLAY = "T"
        const val ACTION_QUERY = "q"
        const val ACTION_PUT = "p"
        const val ACTION_DELETE = "d"
        const val ACTION_FRAME = "f"
        const val ACTION_ANIMATE = "a"
        const val ACTION_COMPOSE = "c"

        /** Largest single upload kept in memory (bytes). */
        const val MAX_PENDING_BYTES = 64 * 1024 * 1024
        /** Cap on stored images (placed refs keep their bitmaps anyway). */
        const val MAX_STORED_IMAGES = 64
    }

    /** Placement of an image, resolved without pixel metrics. */
    data class ShowOp(
        /** Destination cells (0 = auto from aspect, resolved by renderer). */
        val destCols: Int,
        val destRows: Int,
        /** Source rectangle in pixels (0 w/h = full image). */
        val srcX: Int,
        val srcY: Int,
        val srcW: Int,
        val srcH: Int,
        /** Origin offset inside the cell, in pixels. */
        val xOffPx: Int,
        val yOffPx: Int,
        val zIndex: Int,
        val noCursorMove: Boolean
    )

    /** Delete selector. Coordinates are 1-based like cursor positions. */
    data class DeleteSelector(
        /** Normalized lowercase kind: a i n c f p q r x y z. */
        val kind: Char,
        val imageId: Int = 0,
        val placementId: Int = 0,
        val number: Int = 0,
        val x: Int = 0,
        val y: Int = 0,
        val z: Int = 0,
        val freeData: Boolean = false,
        /** Resolve against the cursor position (kind 'c'). */
        val atCursor: Boolean = false
    )

    sealed interface KittyEvent {
        data class Show(val image: KittyImage, val op: ShowOp) : KittyEvent
        data class Delete(val selector: DeleteSelector) : KittyEvent
        /** Raw APC payload to write back to the pty. */
        data class Respond(val payload: String) : KittyEvent
    }

    private data class PendingUpload(
        var params: Map<String, String>,
        val data: ByteArrayOutputStream = ByteArrayOutputStream()
    )

    private val images = mutableMapOf<Int, KittyImage>()
    /** Image number -> image ids in creation order (newest last). */
    private val numbers = mutableMapOf<Int, MutableList<Int>>()
    private val pending = mutableMapOf<Int, PendingUpload>()
    private var lastChainId: Int? = null
    private var nextAutoId = 1

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * Parse one complete APC sequence into events.
     * Returns an empty list while chunked uploads are incomplete.
     */
    fun parse(sequence: String): List<KittyEvent> {
        if (!sequence.startsWith(APC_START) || !sequence.endsWith(APC_END)) {
            return emptyList()
        }
        val content = sequence.removePrefix(APC_START).removeSuffix(APC_END)
        val separatorIndex = content.indexOf(';')
        if (separatorIndex == -1) return emptyList()

        val params = parseControlData(content.substring(0, separatorIndex))
        val payload = content.substring(separatorIndex + 1)

        // Foreign protocol responses (our own echoed replies, real
        // kitty replies, DA answers) always carry Gi and are never
        // client commands. Ignore them outright: parsing them as
        // anonymous uploads steals pending chunk state and the error
        // responses they spawn loop forever through pty echo.
        if (params.containsKey("Gi")) return emptyList()

        val action = params["a"] ?: ACTION_TRANSMIT
        val quiet = params["q"]?.toIntOrNull() ?: 0
        val hasI = params.containsKey("i")
        val hasNum = params.containsKey("I")

        if (hasI && hasNum) {
            return err(quiet, 0, "EINVAL: i and I must not be combined", true)
        }

        return when (action) {
            ACTION_TRANSMIT, ACTION_TRANSMIT_AND_DISPLAY,
            ACTION_QUERY, ACTION_FRAME -> handleLoad(params, payload, action, quiet)
            ACTION_PUT -> handlePut(params, quiet)
            ACTION_DELETE -> handleDelete(params)
            // Animation control / compose: parsed, intentionally ignored.
            ACTION_ANIMATE, ACTION_COMPOSE -> emptyList()
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Loading (transmit / query / frames)
    // ------------------------------------------------------------------

    private fun handleLoad(
        params: Map<String, String>,
        payload: String,
        action: String,
        quiet: Int
    ): List<KittyEvent> {
        val medium = params["t"] ?: "d"
        // Correlatable replies need an explicit i/I (like xterm); without
        // one there is nobody to answer, so stay silent.
        val correlate = params.containsKey("i") || params.containsKey("I")
        if (medium != "d") {
            val id = params["i"]?.toIntOrNull() ?: 0
            return err(quiet, id, "EBADMEDIUM: only direct (t=d) uploads are supported", correlate)
        }

        // Resolve which upload chain this chunk belongs to.
        // Continuations carry (almost) no control keys; anything without
        // image keys that is not a known continuation is garbage
        // (e.g. an echoed protocol response) and must be ignored so it
        // can neither spawn responses nor poison pending uploads.
        // ('a' alone doesn't count: frame continuations repeat a=f.)
        val hasImageKeys = params.keys.any { it != "m" && it != "q" && it != "a" }
        var chainId: Int? = null
        var chainParams = params
        if (params.containsKey("i")) {
            chainId = params["i"]?.toIntOrNull() ?: 0
        } else if (params.containsKey("I")) {
            // A number always starts a new image.
            chainId = nextAutoId++
            params["I"]?.toIntOrNull()?.let { num ->
                if (num != 0) {
                    numbers.getOrPut(num) { mutableListOf() }.add(chainId!!)
                }
            }
        } else if (!hasImageKeys) {
            val cid = lastChainId
            val state = cid?.let { pending[it] } ?: return emptyList()
            chainId = cid
            chainParams = state.params
        } else {
            // Anonymous new upload (single chunk or first chunk).
            chainId = 0
        }
        if (chainId == null) chainId = 0

        val more = params["m"]?.toIntOrNull() ?: 0
        val data = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: Exception) {
            pending.remove(chainId)
            if (chainId == lastChainId) lastChainId = null
            return err(quiet, chainId ?: 0, "EINVAL: bad base64 payload", correlate)
        }

        if (more == 1) {
            val state = pending.getOrPut(chainId) { PendingUpload(chainParams) }
            state.data.write(data)
            if (state.data.size() > MAX_PENDING_BYTES) {
                pending.remove(chainId)
                if (chainId == lastChainId) lastChainId = null
                return err(quiet, chainId, "ENOSPC: upload too large", correlate)
            }
            lastChainId = chainId
            return emptyList()
        }

        // Final chunk. A fresh single-chunk upload (full keys, no m key)
        // must not inherit a stale aborted chain.
        val freshSingle = hasImageKeys && !params.containsKey("m")
        val first = if (freshSingle) {
            pending.remove(chainId)
            if (chainId == lastChainId) lastChainId = null
            null
        } else {
            pending.remove(chainId).also {
                if (chainId == lastChainId) lastChainId = null
            }
        }
        val effective = if (first != null) first.params else chainParams
        val complete = ByteArrayOutputStream().also { out ->
            first?.data?.writeTo(out)
            out.write(data)
        }.toByteArray()

        return finishLoad(effective, complete, action, quiet, chainId, correlate)
    }

    private fun finishLoad(
        params: Map<String, String>,
        data: ByteArray,
        action: String,
        quiet: Int,
        chainId: Int,
        correlate: Boolean
    ): List<KittyEvent> {
        // Empty transmit: nothing to store (xterm parity).
        if (data.isEmpty() && action == ACTION_TRANSMIT) return emptyList()

        val format = params["f"]?.toIntOrNull() ?: FORMAT_RGBA
        val width = params["s"]?.toIntOrNull() ?: 0
        val height = params["v"]?.toIntOrNull() ?: 0

        var raw = data
        if (params["o"] == "z") {
            raw = try {
                InflaterInputStream(ByteArrayInputStream(data)).readBytes()
            } catch (e: Exception) {
                return err(quiet, chainId, "EINVAL: bad zlib data", correlate)
            }
        }

        val bitmap = when (format) {
            FORMAT_RGB -> createRgbBitmap(raw, width, height)
            FORMAT_RGBA -> createRgbaBitmap(raw, width, height)
            FORMAT_PNG -> createPngBitmap(raw)
            else -> null
        } ?: return err(quiet, chainId, "EINVAL: bad image data", correlate)

        // Query action: validate only, never store or display.
        if (action == ACTION_QUERY) {
            return ok(quiet, chainId, null, true)
        }

        val imageId = if (params.containsKey("i")) {
            params["i"]?.toIntOrNull() ?: 0
        } else {
            chainId
        }

        // Re-transmission replaces the old image (placements die with it;
        // the UI layer drops them when the bitmap identity changes... see
        // replaceImage below which returns the replaced ids).
        if (imageId != 0) {
            images.remove(imageId)
        }
        val image = KittyImage(
            id = imageId,
            bitmap = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            format = format
        )
        if (imageId != 0) {
            images[imageId] = image
            while (images.size > MAX_STORED_IMAGES) {
                val eldest = images.keys.firstOrNull() ?: break
                if (eldest == imageId) break
                images.remove(eldest)
                numbers.values.forEach { it.remove(eldest) }
            }
        }

        // Frames are stored like plain images but never auto-displayed.
        if (action == ACTION_FRAME) {
            return ok(quiet, imageId, null, correlate)
        }

        if (action == ACTION_TRANSMIT) {
            return ok(quiet, imageId, params["p"]?.toIntOrNull(), correlate)
        }

        // Transmit-and-display.
        if (params["U"]?.toIntOrNull() == 1) {
            // Virtual placement for Unicode placeholders: stored, not drawn.
            return ok(quiet, imageId, params["p"]?.toIntOrNull(), correlate)
        }
        return listOf(KittyEvent.Show(image, placementOf(params))) +
            ok(quiet, imageId, params["p"]?.toIntOrNull(), correlate)
    }

    private fun placementOf(params: Map<String, String>): ShowOp {
        return ShowOp(
            destCols = params["c"]?.toIntOrNull() ?: 0,
            destRows = params["r"]?.toIntOrNull() ?: 0,
            srcX = params["x"]?.toIntOrNull() ?: 0,
            srcY = params["y"]?.toIntOrNull() ?: 0,
            srcW = params["w"]?.toIntOrNull() ?: 0,
            srcH = params["h"]?.toIntOrNull() ?: 0,
            xOffPx = params["X"]?.toIntOrNull() ?: 0,
            yOffPx = params["Y"]?.toIntOrNull() ?: 0,
            zIndex = params["z"]?.toIntOrNull() ?: 0,
            noCursorMove = params["C"]?.toIntOrNull() == 1
        )
    }

    // ------------------------------------------------------------------
    // Put / delete
    // ------------------------------------------------------------------

    private fun handlePut(params: Map<String, String>, quiet: Int): List<KittyEvent> {
        val correlate = params.containsKey("i") || params.containsKey("I")
        val image: KittyImage? = when {
            params.containsKey("i") -> images[params["i"]?.toIntOrNull()]
            params.containsKey("I") -> {
                val num = params["I"]?.toIntOrNull() ?: 0
                numbers[num]?.lastOrNull()?.let { images[it] }
            }
            else -> null
        }
        if (image == null) {
            if (!correlate) return emptyList()
            val id = params["i"]?.toIntOrNull() ?: 0
            return err(quiet, id, "ENOENT: no image with the given id", true)
        }
        if (params["U"]?.toIntOrNull() == 1) {
            return ok(quiet, image.id, params["p"]?.toIntOrNull(), correlate)
        }
        return listOf(KittyEvent.Show(image, placementOf(params))) +
            ok(quiet, image.id, params["p"]?.toIntOrNull(), correlate)
    }

    private fun handleDelete(params: Map<String, String>): List<KittyEvent> {
        val raw = params["d"] ?: "a"
        val kind = raw.firstOrNull() ?: 'a'
        val freeData = kind.isUpperCase()
        val selector = DeleteSelector(
            kind = kind.lowercaseChar(),
            imageId = params["i"]?.toIntOrNull() ?: 0,
            placementId = params["p"]?.toIntOrNull() ?: 0,
            number = params["I"]?.toIntOrNull() ?: 0,
            x = params["x"]?.toIntOrNull() ?: 0,
            y = params["y"]?.toIntOrNull() ?: 0,
            z = params["z"]?.toIntOrNull() ?: 0,
            freeData = freeData,
            atCursor = kind.lowercaseChar() == 'c'
        )
        // Any delete aborts affected partial uploads.
        if (selector.kind == 'a') {
            pending.clear()
            lastChainId = null
        } else if (selector.imageId != 0) {
            pending.remove(selector.imageId)
            if (lastChainId == selector.imageId) lastChainId = null
        }
        // Deletes never get responses.
        return listOf(KittyEvent.Delete(selector))
    }

    // ------------------------------------------------------------------
    // Responses (q=1 suppresses OK, q=2 suppresses everything).
    // Like xterm, only operations that named an id/number get answers;
    // anonymous traffic stays silent so echoes can never cascade.
    // ------------------------------------------------------------------

    private fun ok(quiet: Int, id: Int, placementId: Int?, respond: Boolean = true): List<KittyEvent> {
        if (!respond || quiet >= 1) return emptyList()
        val extra = if (placementId != null && placementId != 0) ",p=$placementId" else ""
        return listOf(KittyEvent.Respond("$APC_START" + "Gi=$id$extra;OK$APC_END"))
    }

    private fun err(quiet: Int, id: Int, message: String, respond: Boolean = true): List<KittyEvent> {
        if (!respond || quiet >= 2) return emptyList()
        return listOf(KittyEvent.Respond("$APC_START" + "Gi=$id;$message$APC_END"))
    }

    // ------------------------------------------------------------------
    // Store access for the UI layer
    // ------------------------------------------------------------------

    fun newestIdForNumber(number: Int): Int? = numbers[number]?.lastOrNull()

    fun freeUnreferenced(keepIds: Set<Int>) {
        val it = images.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in keepIds) it.remove()
        }
        val nit = numbers.entries.iterator()
        while (nit.hasNext()) {
            val e = nit.next()
            e.value.removeAll { id -> id !in images }
            if (e.value.isEmpty()) nit.remove()
        }
    }

    fun clearStoredImages() {
        images.clear()
        numbers.clear()
        pending.clear()
        lastChainId = null
    }

    fun getImage(id: Int): KittyImage? = images[id]
    fun getAllImages(): Map<Int, KittyImage> = images.toMap()

    // ------------------------------------------------------------------
    // Bitmap decoding
    // ------------------------------------------------------------------

    private fun parseControlData(data: String): Map<String, String> {
        if (data.isEmpty()) return emptyMap()
        return data.split(",").associate { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
        }
    }

    private fun createRgbBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (data.size < width * height * 3) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in 0 until width * height) {
            val offset = i * 3
            val r = data[offset].toInt() and 0xFF
            val g = data[offset + 1].toInt() and 0xFF
            val b = data[offset + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun createRgbaBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (data.size < width * height * 4) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in 0 until width * height) {
            val offset = i * 4
            val r = data[offset].toInt() and 0xFF
            val g = data[offset + 1].toInt() and 0xFF
            val b = data[offset + 2].toInt() and 0xFF
            val a = data[offset + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun createPngBitmap(data: ByteArray): Bitmap? {
        if (data.isEmpty()) return null
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    /**
     * Check if a string contains a Kitty graphics escape sequence.
     */
    fun containsKittySequence(text: String): Boolean {
        return text.contains(APC_START) && text.contains(APC_END)
    }

    /**
     * Extract all complete Kitty graphics sequences from text, in order.
     */
    fun extractSequences(text: String): List<String> {
        return extractSequenceRanges(text).map { text.substring(it) }
    }

    fun extractSequenceRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0

        while (true) {
            val begin = text.indexOf(APC_START, start)
            if (begin == -1) break

            val end = text.indexOf(APC_END, begin)
            if (end == -1) break

            ranges.add(begin until end + APC_END.length)
            start = end + APC_END.length
        }

        return ranges
    }
}

data class KittyImage(
    val id: Int,
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val format: Int,
    val placement: KittyPlacement? = null
)

data class KittyPlacement(
    val x: Int = 0,
    val y: Int = 0,
    val columns: Int = 0,
    val rows: Int = 0,
    val cellOffsetX: Int = 0,
    val cellOffsetY: Int = 0,
    val zIndex: Int = 0
)
