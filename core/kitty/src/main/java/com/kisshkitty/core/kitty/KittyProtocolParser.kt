package com.kisshkitty.core.kitty

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Parser for Kitty Graphics Protocol escape sequences.
 * 
 * The Kitty protocol uses APC (Application Programming Command) sequences:
 * ESC _ G <control data> ; <payload> ESC \
 * 
 * Reference: https://sw.kovidgoyal.net/kitty/graphics-protocol/
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
        
        // Transmission media
        const val MEDIA_DIRECT = "d"
        const val MEDIA_FILE = "f"
        const val MEDIA_TEMP = "t"
        const val MEDIA_SHARED_MEMORY = "s"
    }

    private val images = mutableMapOf<Int, KittyImage>()
    private var currentImageId = 0
    private var currentChunkBuffer = ByteArrayOutputStream()
    private var isReceivingChunks = false

    /**
     * Parse a Kitty graphics escape sequence.
     * Returns a KittyImage if the sequence is complete, null otherwise.
     */
    fun parse(sequence: String): KittyImage? {
        // Check if it's a valid APC sequence
        if (!sequence.startsWith(APC_START) || !sequence.endsWith(APC_END)) {
            return null
        }

        // Extract the content between APC markers
        val content = sequence.removePrefix(APC_START).removeSuffix(APC_END)
        
        // Split into control data and payload
        val separatorIndex = content.indexOf(';')
        if (separatorIndex == -1) return null

        val controlData = content.substring(0, separatorIndex)
        val payload = content.substring(separatorIndex + 1)

        // Parse control data key-value pairs
        val params = parseControlData(controlData)
        
        // Get action
        val action = params["a"] ?: ACTION_TRANSMIT
        
        return when (action) {
            ACTION_TRANSMIT, ACTION_TRANSMIT_AND_DISPLAY -> {
                handleTransmission(params, payload)
            }
            ACTION_QUERY -> {
                // Query response - we could handle this
                null
            }
            ACTION_PUT -> {
                // Display a previously transmitted image
                handlePut(params)
            }
            ACTION_DELETE -> {
                // Delete an image
                handleDelete(params)
                null
            }
            else -> null
        }
    }

    private fun parseControlData(data: String): Map<String, String> {
        return data.split(",").associate { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
        }
    }

    private fun handleTransmission(params: Map<String, String>, payload: String): KittyImage? {
        val imageId = params["i"]?.toIntOrNull() ?: currentImageId++
        val format = params["f"]?.toIntOrNull() ?: FORMAT_RGBA
        val width = params["s"]?.toIntOrNull() ?: 0
        val height = params["v"]?.toIntOrNull() ?: 0
        val moreChunks = params["m"]?.toIntOrNull() ?: 0
        val compressed = params["o"] == "z"

        // Decode base64 payload
        val imageData = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }

        if (moreChunks == 1) {
            // More chunks coming
            isReceivingChunks = true
            currentChunkBuffer.write(imageData)
            return null
        } else {
            // Final chunk
            val completeData = if (isReceivingChunks) {
                currentChunkBuffer.write(imageData)
                val result = currentChunkBuffer.toByteArray()
                currentChunkBuffer.reset()
                isReceivingChunks = false
                result
            } else {
                imageData
            }

            // Create bitmap based on format
            val bitmap = when (format) {
                FORMAT_RGB -> createRgbBitmap(completeData, width, height)
                FORMAT_RGBA -> createRgbaBitmap(completeData, width, height)
                FORMAT_PNG -> createPngBitmap(completeData)
                else -> return null
            } ?: return null

            val image = KittyImage(
                id = imageId,
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                format = format
            )

            images[imageId] = image
            return image
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
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    private fun handlePut(params: Map<String, String>): KittyImage? {
        val imageId = params["i"]?.toIntOrNull() ?: return null
        return images[imageId]
    }

    private fun handleDelete(params: Map<String, String>) {
        val imageId = params["i"]?.toIntOrNull()
        val deleteAll = params["d"] == "a"
        
        if (deleteAll) {
            images.clear()
        } else if (imageId != null) {
            images.remove(imageId)
        }
    }

    /**
     * Check if a string contains a Kitty graphics escape sequence.
     */
    fun containsKittySequence(text: String): Boolean {
        return text.contains(APC_START) && text.contains(APC_END)
    }

    /**
     * Extract all Kitty graphics sequences from text.
     */
    fun extractSequences(text: String): List<String> {
        val sequences = mutableListOf<String>()
        var start = 0
        
        while (true) {
            val begin = text.indexOf(APC_START, start)
            if (begin == -1) break
            
            val end = text.indexOf(APC_END, begin)
            if (end == -1) break
            
            sequences.add(text.substring(begin, end + APC_END.length))
            start = end + APC_END.length
        }
        
        return sequences
    }

    fun getImage(id: Int): KittyImage? = images[id]
    fun getAllImages(): Map<Int, KittyImage> = images.toMap()
    fun clearImages() = images.clear()
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
