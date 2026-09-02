package com.kisshkitty.core.kitty

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Renderer for Kitty protocol images in a terminal context.
 * Handles positioning and drawing images based on terminal cell coordinates.
 */
class KittyImageRenderer {

    private val parser = KittyProtocolParser()
    private var cellWidth = 0
    private var cellHeight = 0
    private var terminalWidth = 0
    private var terminalHeight = 0

    /**
     * Update terminal dimensions for proper image scaling.
     */
    fun updateTerminalDimensions(cols: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) {
        terminalWidth = pixelWidth
        terminalHeight = pixelHeight
        cellWidth = if (cols > 0) pixelWidth / cols else 0
        cellHeight = if (rows > 0) pixelHeight / rows else 0
    }

    /**
     * Process text output from the terminal, extracting and rendering Kitty images.
     */
    fun processOutput(text: String): TerminalOutput {
        if (!parser.containsKittySequence(text)) {
            return TerminalOutput(text, emptyList())
        }

        val sequences = parser.extractSequences(text)
        val images = mutableListOf<RenderedImage>()

        // Remove sequences from text
        var cleanText = text
        for (seq in sequences) {
            cleanText = cleanText.replace(seq, "")
        }

        // Parse each sequence and prepare for rendering
        for (seq in sequences) {
            val image = parser.parse(seq)
            if (image != null) {
                images.add(
                    RenderedImage(
                        image = image,
                        x = image.placement?.x ?: 0,
                        y = image.placement?.y ?: 0,
                        width = image.placement?.columns?.let { it * cellWidth } ?: image.width,
                        height = image.placement?.rows?.let { it * cellHeight } ?: image.height
                    )
                )
            }
        }

        return TerminalOutput(cleanText, images)
    }

    /**
     * Render all images onto a canvas at their specified positions.
     */
    fun renderImages(canvas: Canvas, images: List<RenderedImage>) {
        for (renderedImage in images) {
            val image = renderedImage.image
            val destRect = Rect(
                renderedImage.x,
                renderedImage.y,
                renderedImage.x + renderedImage.width,
                renderedImage.y + renderedImage.height
            )

            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }

            canvas.drawBitmap(image.bitmap, null, destRect, paint)
        }
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

data class TerminalOutput(
    val text: String,
    val images: List<RenderedImage>
)

data class RenderedImage(
    val image: KittyImage,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)
