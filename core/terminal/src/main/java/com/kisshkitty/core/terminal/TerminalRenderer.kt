package com.kisshkitty.core.terminal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Terminal renderer that draws the terminal state to a Canvas.
 */
class TerminalRenderer(
    private var fontSize: Float = 14f
) {
    private val paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        this.textSize = fontSize
    }

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var ascent = 0f

    /**
     * Update font size and recalculate cell dimensions.
     */
    fun updateFontSize(size: Float) {
        fontSize = size
        paint.textSize = fontSize
        cellWidth = paint.measureText("M")
        cellHeight = fontSize * 1.2f
        ascent = -paint.fontMetrics.ascent
    }

    /**
     * Get the required width for the given number of columns.
     */
    fun getRequiredWidth(cols: Int): Int {
        return (cellWidth * cols).toInt()
    }

    /**
     * Get the required height for the given number of rows.
     */
    fun getRequiredHeight(rows: Int): Int {
        return (cellHeight * rows).toInt()
    }

    /**
     * Render the terminal buffer to a Canvas.
     */
    fun render(
        canvas: Canvas,
        emulator: TerminalEmulator,
        bgColor: Int = Color.BLACK,
        cursorVisible: Boolean = true
    ) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()

        // Clear background
        canvas.drawColor(bgColor)

        val buffer = emulator.getBuffer()
        val colors = emulator.getColors()
        val cursorX = emulator.getCursorX()
        val cursorY = emulator.getCursorY()

        // Draw each cell
        for (y in 0 until emulator.getRows()) {
            for (x in 0 until emulator.getCols()) {
                val char = buffer[y][x]
                val color = colors[y][x]

                val left = x * cellWidth
                val top = y * cellHeight

                // Draw cursor
                if (x == cursorX && y == cursorY && cursorVisible) {
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(
                        left,
                        top,
                        left + cellWidth,
                        top + cellHeight,
                        paint
                    )
                    paint.color = Color.BLACK
                } else {
                    paint.color = color
                }

                // Draw character
                if (char != ' ') {
                    paint.style = Paint.Style.FILL
                    canvas.drawText(
                        char.toString(),
                        left,
                        top + ascent,
                        paint
                    )
                }
            }
        }
    }

    /**
     * Get the cell coordinates for a pixel position.
     */
    fun getCellFromPixel(pixelX: Float, pixelY: Float): Pair<Int, Int> {
        val col = (pixelX / cellWidth).toInt()
        val row = (pixelY / cellHeight).toInt()
        return Pair(col, row)
    }

    /**
     * Get the pixel position for cell coordinates.
     */
    fun getPixelFromCell(col: Int, row: Int): Pair<Float, Float> {
        val x = col * cellWidth
        val y = row * cellHeight
        return Pair(x, y)
    }

    fun getCellWidth(): Float = cellWidth
    fun getCellHeight(): Float = cellHeight
    fun getFontSize(): Float = fontSize
}
