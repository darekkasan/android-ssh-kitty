package com.kisshkitty.core.terminal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Basic VT100/ANSI terminal emulator.
 * Parses escape sequences and maintains terminal state.
 */
class TerminalEmulator(
    private var cols: Int = 80,
    private var rows: Int = 24
) {
    private var buffer = Array(rows) { CharArray(cols) { ' ' } }
    private var colors = Array(rows) { IntArray(cols) { Color.WHITE } }
    private var attributes = Array(rows) { IntArray(cols) { 0 } }
    
    private var cursorX = 0
    private var cursorY = 0
    private var currentColor = Color.WHITE
    private var currentAttributes = 0
    
    // ANSI color codes
    private val ansiColors = intArrayOf(
        Color.BLACK,       // 0 - Black
        Color.RED,         // 1 - Red
        Color.GREEN,       // 2 - Green
        Color.YELLOW,      // 3 - Yellow
        Color.BLUE,        // 4 - Blue
        Color.MAGENTA,     // 5 - Magenta
        Color.CYAN,        // 6 - Cyan
        Color.WHITE,       // 7 - White
        Color.rgb(128, 128, 128), // 8 - Bright Black (Gray)
        Color.rgb(255, 0, 0),     // 9 - Bright Red
        Color.rgb(0, 255, 0),     // 10 - Bright Green
        Color.rgb(255, 255, 0),   // 11 - Bright Yellow
        Color.rgb(0, 0, 255),     // 12 - Bright Blue
        Color.rgb(255, 0, 255),   // 13 - Bright Magenta
        Color.rgb(0, 255, 255),   // 14 - Bright Cyan
        Color.WHITE                 // 15 - Bright White
    )

    /**
     * Process a string of output from the terminal.
     */
    fun processOutput(text: String) {
        var i = 0
        while (i < text.length) {
            val char = text[i]
            
            when {
                char == '\u001B' -> {
                    // Escape sequence
                    val result = parseEscapeSequence(text, i)
                    i = result.newIndex
                }
                char == '\r' -> {
                    cursorX = 0
                    i++
                }
                char == '\n' -> {
                    cursorY++
                    if (cursorY >= rows) {
                        scrollUp()
                        cursorY = rows - 1
                    }
                    i++
                }
                char == '\t' -> {
                    // Tab - advance to next multiple of 8
                    cursorX = (cursorX / 8 + 1) * 8
                    if (cursorX >= cols) {
                        cursorX = 0
                        cursorY++
                        if (cursorY >= rows) {
                            scrollUp()
                            cursorY = rows - 1
                        }
                    }
                    i++
                }
                char == '\b' -> {
                    // Backspace
                    if (cursorX > 0) cursorX--
                    i++
                }
                else -> {
                    // Regular character (guard with real array bounds)
                    if (cursorY in buffer.indices && cursorX in buffer[cursorY].indices) {
                        buffer[cursorY][cursorX] = char
                        colors[cursorY][cursorX] = currentColor
                        attributes[cursorY][cursorX] = currentAttributes
                    }
                    cursorX++
                    if (cursorX >= cols) {
                        cursorX = 0
                        cursorY++
                        if (cursorY >= rows) {
                            scrollUp()
                            cursorY = rows - 1
                        }
                    }
                    i++
                }
            }
        }
    }

    private fun parseEscapeSequence(text: String, startIndex: Int): ParseResult {
        var i = startIndex + 1
        if (i >= text.length) return ParseResult(i)

        when (text[i]) {
            '[' -> {
                // CSI sequence
                return parseCsiSequence(text, i + 1)
            }
            ']' -> {
                // OSC sequence - skip until ST or BEL
                return parseOscSequence(text, i + 1)
            }
            '_' -> {
                // APC sequence - Kitty graphics
                return parseApcSequence(text, i + 1)
            }
            else -> {
                // Skip unrecognized escape
                return ParseResult(i + 1)
            }
        }
    }

    private fun parseCsiSequence(text: String, startIndex: Int): ParseResult {
        var i = startIndex
        val params = mutableListOf<Int>()
        var currentParam = StringBuilder()

        // Collect parameters
        while (i < text.length) {
            val char = text[i]
            when {
                char.isDigit() -> {
                    currentParam.append(char)
                    i++
                }
                char == ';' -> {
                    params.add(currentParam.toString().toIntOrNull() ?: 0)
                    currentParam = StringBuilder()
                    i++
                }
                else -> {
                    // Final character
                    if (currentParam.isNotEmpty()) {
                        params.add(currentParam.toString().toIntOrNull() ?: 0)
                    }
                    executeCsiSequence(params, char)
                    return ParseResult(i + 1)
                }
            }
        }

        return ParseResult(i)
    }

    private fun executeCsiSequence(params: List<Int>, command: Char) {
        when (command) {
            'A' -> {
                // Cursor Up
                val n = params.firstOrNull() ?: 1
                cursorY = (cursorY - n).coerceAtLeast(0)
            }
            'B' -> {
                // Cursor Down
                val n = params.firstOrNull() ?: 1
                cursorY = (cursorY + n).coerceAtMost(rows - 1)
            }
            'C' -> {
                // Cursor Forward
                val n = params.firstOrNull() ?: 1
                cursorX = (cursorX + n).coerceAtMost(cols - 1)
            }
            'D' -> {
                // Cursor Backward
                val n = params.firstOrNull() ?: 1
                cursorX = (cursorX - n).coerceAtLeast(0)
            }
            'H' -> {
                // Cursor Position
                val row = (params.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                val col = (params.getOrElse(1) { 1 } - 1).coerceIn(0, cols - 1)
                cursorY = row
                cursorX = col
            }
            'J' -> {
                // Erase in Display
                when (params.firstOrNull() ?: 0) {
                    0 -> {
                        // Clear from cursor to end
                        for (x in cursorX until cols) {
                            buffer[cursorY][x] = ' '
                            colors[cursorY][x] = currentColor
                        }
                        for (y in cursorY + 1 until rows) {
                            buffer[y] = CharArray(cols) { ' ' }
                            colors[y] = IntArray(cols) { currentColor }
                        }
                    }
                    2 -> {
                        // Clear entire screen
                        clear()
                    }
                    3 -> {
                        // Clear screen and scrollback
                        clear()
                    }
                }
            }
            'K' -> {
                // Erase in Line
                when (params.firstOrNull() ?: 0) {
                    0 -> {
                        // Clear from cursor to end of line
                        for (x in cursorX until cols) {
                            buffer[cursorY][x] = ' '
                            colors[cursorY][x] = currentColor
                        }
                    }
                    2 -> {
                        // Clear entire line
                        buffer[cursorY] = CharArray(cols) { ' ' }
                        colors[cursorY] = IntArray(cols) { currentColor }
                    }
                }
            }
            'm' -> {
                // Set Graphics Mode (colors and attributes)
                for (param in params) {
                    when (param) {
                        0 -> {
                            currentColor = Color.WHITE
                            currentAttributes = 0
                        }
                        1 -> currentAttributes = currentAttributes or ATTR_BOLD
                        2 -> currentAttributes = currentAttributes or ATTR_DIM
                        3 -> currentAttributes = currentAttributes or ATTR_ITALIC
                        4 -> currentAttributes = currentAttributes or ATTR_UNDERLINE
                        5 -> currentAttributes = currentAttributes or ATTR_BLINK
                        7 -> currentAttributes = currentAttributes or ATTR_REVERSE
                        8 -> currentAttributes = currentAttributes or ATTR_HIDDEN
                        22 -> currentAttributes = currentAttributes and (ATTR_BOLD or ATTR_DIM).inv()
                        23 -> currentAttributes = currentAttributes and ATTR_ITALIC.inv()
                        24 -> currentAttributes = currentAttributes and ATTR_UNDERLINE.inv()
                        25 -> currentAttributes = currentAttributes and ATTR_BLINK.inv()
                        27 -> currentAttributes = currentAttributes and ATTR_REVERSE.inv()
                        28 -> currentAttributes = currentAttributes and ATTR_HIDDEN.inv()
                        in 30..37 -> currentColor = ansiColors[param - 30]
                        39 -> currentColor = Color.WHITE
                        in 40..47 -> {
                            // Background colors - simplified
                        }
                    }
                }
            }
        }
    }

    private fun parseOscSequence(text: String, startIndex: Int): ParseResult {
        var i = startIndex
        while (i < text.length) {
            when {
                text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\' -> {
                    // ST (String Terminator)
                    return ParseResult(i + 2)
                }
                text[i] == '\u0007' -> {
                    // BEL
                    return ParseResult(i + 1)
                }
                else -> i++
            }
        }
        return ParseResult(i)
    }

    private fun parseApcSequence(text: String, startIndex: Int): ParseResult {
        var i = startIndex
        while (i < text.length) {
            if (text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\') {
                // APC terminator
                return ParseResult(i + 2)
            }
            i++
        }
        return ParseResult(i)
    }

    private fun scrollUp() {
        // Move all lines up
        for (y in 0 until rows - 1) {
            buffer[y] = buffer[y + 1]
            colors[y] = colors[y + 1]
            attributes[y] = attributes[y + 1]
        }
        // Clear last line
        buffer[rows - 1] = CharArray(cols) { ' ' }
        colors[rows - 1] = IntArray(cols) { currentColor }
        attributes[rows - 1] = IntArray(cols) { 0 }
    }

    fun clear() {
        for (y in 0 until rows) {
            buffer[y] = CharArray(cols) { ' ' }
            colors[y] = IntArray(cols) { currentColor }
            attributes[y] = IntArray(cols) { 0 }
        }
        cursorX = 0
        cursorY = 0
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        // Copy content into the new grid using real array bounds
        // (never the cols/rows fields, which may already disagree).
        val newBuffer = Array(newRows) { y ->
            CharArray(newCols) { x ->
                if (y < buffer.size && x < buffer[y].size) buffer[y][x] else ' '
            }
        }
        val newColors = Array(newRows) { y ->
            IntArray(newCols) { x ->
                if (y < colors.size && x < colors[y].size) colors[y][x] else currentColor
            }
        }
        val newAttributes = Array(newRows) { y ->
            IntArray(newCols) { x ->
                if (y < attributes.size && x < attributes[y].size) attributes[y][x] else 0
            }
        }

        buffer = newBuffer
        colors = newColors
        attributes = newAttributes
        cols = newCols
        rows = newRows
        cursorX = cursorX.coerceIn(0, (cols - 1).coerceAtLeast(0))
        cursorY = cursorY.coerceIn(0, (rows - 1).coerceAtLeast(0))
    }

    fun getBuffer(): Array<CharArray> = buffer
    fun getColors(): Array<IntArray> = colors
    fun getCursorX(): Int = cursorX
    fun getCursorY(): Int = cursorY
    fun getCols(): Int = cols
    fun getRows(): Int = rows

    companion object {
        const val ATTR_BOLD = 1
        const val ATTR_DIM = 2
        const val ATTR_ITALIC = 4
        const val ATTR_UNDERLINE = 8
        const val ATTR_BLINK = 16
        const val ATTR_REVERSE = 32
        const val ATTR_HIDDEN = 64
    }

    data class ParseResult(val newIndex: Int)
}
