package com.kisshkitty.core.terminal

import android.graphics.Color

/**
 * VT100/ANSI terminal emulator with scrollback.
 *
 * Parses escape sequences and maintains terminal state. The visible grid is
 * [cols] x [rows]; lines scrolled off the top are kept in [scrollback]
 * (up to [MAX_SCROLLBACK]) and can be viewed through [getWindow].
 */
class TerminalEmulator(
    private var cols: Int = 80,
    private var rows: Int = 24
) {
    data class ScrollLine(
        val chars: CharArray,
        val fg: IntArray,
        val bg: IntArray
    )

    data class EmulatorWindow(
        val chars: Array<CharArray>,
        val fg: Array<IntArray>,
        val bg: Array<IntArray>,
        /** Absolute line number of the first visible row. */
        val windowStart: Int,
        val totalLines: Int,
        val offset: Int,
        val maxOffset: Int,
        val cursorX: Int,
        /** Viewport row, or -1 when the cursor is hidden / outside. */
        val cursorY: Int
    )

    private var buffer = Array(rows) { CharArray(cols) { ' ' } }
    private var fgColors = Array(rows) { IntArray(cols) { Color.WHITE } }
    private var bgColors = Array(rows) { IntArray(cols) { Color.BLACK } }
    private var attributes = Array(rows) { IntArray(cols) { 0 } }

    private val scrollback = ArrayDeque<ScrollLine>()

    private var cursorX = 0
    private var cursorY = 0
    private var currentFg = Color.WHITE
    private var currentBg = Color.BLACK
    private var currentAttributes = 0

    var cursorVisible = true
    var autoWrap = true
    var bracketedPaste = false

    // Scroll region (inclusive, 0-based). Defaults to the full grid.
    private var regionTop = 0
    private var regionBottom = rows - 1

    // Saved cursor (DECSC).
    private var savedX = 0
    private var savedY = 0
    private var savedFg = Color.WHITE
    private var savedBg = Color.BLACK
    private var savedAttr = 0

    // ANSI color codes
    private val ansiColors = intArrayOf(
        Color.BLACK,       // 0 - Black
        Color.RED,         // 1 - Red
        Color.GREEN,       // 2 - Green
        Color.YELLOW,      // 3 - Yellow
        Color.BLUE,        // 4 - Blue
        Color.MAGENTA,     // 5 - Magenta
        Color.CYAN,        // 6 - Cyan
        Color.WHITE        // 7 - White
    )

    private val brightColors = intArrayOf(
        Color.rgb(128, 128, 128), // 8 - Bright Black (Gray)
        Color.rgb(255, 85, 85),   // 9 - Bright Red
        Color.rgb(85, 255, 85),   // 10 - Bright Green
        Color.rgb(255, 255, 85),  // 11 - Bright Yellow
        Color.rgb(85, 85, 255),   // 12 - Bright Blue
        Color.rgb(255, 85, 255),  // 13 - Bright Magenta
        Color.rgb(85, 255, 255),  // 14 - Bright Cyan
        Color.WHITE                 // 15 - Bright White
    )

    private val cubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

    private fun palette256(n: Int): Int = when {
        n < 0 -> Color.WHITE
        n < 8 -> ansiColors[n]
        n < 16 -> brightColors[n - 8]
        n < 232 -> {
            val j = n - 16
            Color.rgb(
                cubeLevels[j / 36],
                cubeLevels[(j % 36) / 6],
                cubeLevels[j % 6]
            )
        }
        else -> {
            val v = 8 + 10 * (n - 232)
            Color.rgb(v, v, v)
        }
    }

    /**
     * Process a string of output from the terminal.
     * Callers must only pass complete escape sequences (see the
     * escape hold-back in the UI layer); a truncated sequence at the
     * very end is consumed without effect.
     */
    fun processOutput(text: String) {
        var i = 0
        while (i < text.length) {
            val char = text[i]

            when {
                char == '\u001B' -> {
                    val result = parseEscapeSequence(text, i)
                    i = result.newIndex
                }
                char == '\r' -> {
                    cursorX = 0
                    i++
                }
                char == '\n' -> {
                    lineFeed()
                    i++
                }
                char == '\t' -> {
                    // Tab - advance to next multiple of 8
                    cursorX = (cursorX / 8 + 1) * 8
                    if (cursorX >= cols) {
                        if (autoWrap) {
                            cursorX = 0
                            lineFeed()
                        } else {
                            cursorX = cols - 1
                        }
                    }
                    i++
                }
                char == '\b' -> {
                    // Backspace
                    if (cursorX > 0) cursorX--
                    i++
                }
                char == '\u0007' -> {
                    // BEL - ignore
                    i++
                }
                char < ' ' -> {
                    // Other C0 controls - ignore
                    i++
                }
                else -> {
                    putChar(char)
                    i++
                }
            }
        }
    }

    private fun putChar(char: Char) {
        if (cursorX >= cols) {
            if (autoWrap) {
                cursorX = 0
                lineFeed()
            } else {
                cursorX = cols - 1
            }
        }
        // Regular character (guard with real array bounds)
        if (cursorY in buffer.indices && cursorX in buffer[cursorY].indices) {
            buffer[cursorY][cursorX] = char
            fgColors[cursorY][cursorX] = currentFg
            bgColors[cursorY][cursorX] = currentBg
            attributes[cursorY][cursorX] = currentAttributes
        }
        cursorX++
    }

    private fun lineFeed() {
        val limit = if (isRegionActive()) regionBottom else rows - 1
        cursorY++
        if (cursorY > limit) {
            cursorY = limit
            if (isRegionActive()) scrollRegion() else scrollUp()
        }
    }

    private fun isRegionActive(): Boolean = regionTop > 0 || regionBottom < rows - 1

    private fun scrollUp() {
        // The top line moves into the scrollback.
        if (buffer.isNotEmpty()) {
            scrollback.addLast(
                ScrollLine(
                    buffer[0].copyOf(),
                    fgColors[0].copyOf(),
                    bgColors[0].copyOf()
                )
            )
            while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
        }
        for (y in 0 until rows - 1) {
            buffer[y] = buffer[y + 1]
            fgColors[y] = fgColors[y + 1]
            bgColors[y] = bgColors[y + 1]
            attributes[y] = attributes[y + 1]
        }
        if (rows > 0) {
            buffer[rows - 1] = CharArray(cols) { ' ' }
            fgColors[rows - 1] = IntArray(cols) { currentFg }
            bgColors[rows - 1] = IntArray(cols) { currentBg }
            attributes[rows - 1] = IntArray(cols) { 0 }
        }
    }

    /** Scroll lines [top, bottom] up by one without touching scrollback. */
    private fun scrollRegion() {
        val top = regionTop.coerceIn(0, rows - 1)
        val bottom = regionBottom.coerceIn(0, rows - 1)
        if (top >= bottom) return
        for (y in top until bottom) {
            buffer[y] = buffer[y + 1]
            fgColors[y] = fgColors[y + 1]
            bgColors[y] = bgColors[y + 1]
            attributes[y] = attributes[y + 1]
        }
        buffer[bottom] = CharArray(cols) { ' ' }
        fgColors[bottom] = IntArray(cols) { currentFg }
        bgColors[bottom] = IntArray(cols) { currentBg }
        attributes[bottom] = IntArray(cols) { 0 }
    }

    /** Scroll lines [top, bottom] down by [n] (insert blank lines at top). */
    private fun scrollRegionDown(n: Int) {
        val top = regionTop.coerceIn(0, rows - 1)
        val bottom = regionBottom.coerceIn(0, rows - 1)
        if (top >= bottom) return
        val count = n.coerceIn(1, bottom - top + 1)
        for (y in bottom downTo top + count) {
            buffer[y] = buffer[y - count]
            fgColors[y] = fgColors[y - count]
            bgColors[y] = bgColors[y - count]
            attributes[y] = attributes[y - count]
        }
        for (y in top until top + count) {
            buffer[y] = CharArray(cols) { ' ' }
            fgColors[y] = IntArray(cols) { currentFg }
            bgColors[y] = IntArray(cols) { currentBg }
            attributes[y] = IntArray(cols) { 0 }
        }
    }

    /**
     * Advance the cursor for a placed image of [placeCols] x [placeRows]
     * cells (Kitty cursor movement policy: move after placement).
     */
    fun advanceForImage(placeCols: Int, placeRows: Int) {
        cursorX += placeCols.coerceAtLeast(0)
        cursorY += (placeRows - 1).coerceAtLeast(0)
        while (cursorX >= cols) {
            cursorX -= cols
            lineFeed()
        }
        while (cursorY >= rows) {
            lineFeed()
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
            '_', 'P', 'X', '^' -> {
                // APC/DCS/SOS/PM - skip until ST
                return parseUntilSt(text, i + 1)
            }
            'M' -> {
                // Reverse index
                if (cursorY <= regionTop) {
                    scrollRegionDown(1)
                } else {
                    cursorY--
                }
                return ParseResult(i + 1)
            }
            'c' -> {
                // Full reset (RIS)
                clear()
                scrollback.clear()
                currentFg = Color.WHITE
                currentBg = Color.BLACK
                currentAttributes = 0
                regionTop = 0
                regionBottom = rows - 1
                cursorVisible = true
                autoWrap = true
                bracketedPaste = false
                return ParseResult(i + 1)
            }
            '7' -> {
                // Save cursor (DECSC)
                savedX = cursorX
                savedY = cursorY
                savedFg = currentFg
                savedBg = currentBg
                savedAttr = currentAttributes
                return ParseResult(i + 1)
            }
            '8' -> {
                // Restore cursor (DECRC)
                cursorX = savedX.coerceIn(0, (cols - 1).coerceAtLeast(0))
                cursorY = savedY.coerceIn(0, (rows - 1).coerceAtLeast(0))
                currentFg = savedFg
                currentBg = savedBg
                currentAttributes = savedAttr
                return ParseResult(i + 1)
            }
            'D' -> {
                // Index (same as line feed)
                lineFeed()
                return ParseResult(i + 1)
            }
            'E' -> {
                // Next line
                cursorX = 0
                lineFeed()
                return ParseResult(i + 1)
            }
            else -> {
                // Skip unrecognized single-char escape (e.g. = > H)
                // and 3-byte ones (# X, ( X, ) X, % X).
                var end = i + 1
                if (i < text.length && text[i] in "#()%&" && end < text.length) end++
                return ParseResult(end)
            }
        }
    }

    private fun parseCsiSequence(text: String, startIndex: Int): ParseResult {
        var i = startIndex
        val n = text.length
        // Parameter bytes: 0x30-0x3F (digits ; : < = > ?)
        val p0 = i
        while (i < n && text[i] in '0'..'?') i++
        val paramStr = text.substring(p0, i)
        // Intermediate bytes: 0x20-0x2F (space ! " # $ % & ' ( ) * + , - . /)
        val m0 = i
        while (i < n && text[i] in ' '..'/') i++
        val inter = text.substring(m0, i)
        if (i >= n) return ParseResult(i)
        val finalChar = text[i]
        executeCsiSequence(paramStr, inter, finalChar)
        return ParseResult(i + 1)
    }

    private fun csiParams(paramStr: String): List<Int> {
        val body = paramStr.trimStart('?')
        if (body.isEmpty()) return emptyList()
        return body.split(';', ':').map { it.toIntOrNull() ?: 0 }
    }

    private fun executeCsiSequence(paramStr: String, inter: String, finalChar: Char) {
        val isPrivate = paramStr.startsWith("?")
        val params = csiParams(paramStr)
        fun p(index: Int, default: Int): Int = params.getOrNull(index) ?: default

        when (finalChar) {
            '@' -> {
                // Insert blank characters
                val count = p(0, 1).coerceAtLeast(1)
                val row = cursorY
                if (row in buffer.indices) {
                    for (x in (cols - 1) downTo cursorX + count) {
                        if (x < cols && x - count >= 0) {
                            buffer[row][x] = buffer[row][x - count]
                            fgColors[row][x] = fgColors[row][x - count]
                            bgColors[row][x] = bgColors[row][x - count]
                            attributes[row][x] = attributes[row][x - count]
                        }
                    }
                    for (x in cursorX until (cursorX + count).coerceAtMost(cols)) {
                        buffer[row][x] = ' '
                        fgColors[row][x] = currentFg
                        bgColors[row][x] = currentBg
                        attributes[row][x] = 0
                    }
                }
            }
            'A' -> {
                // Cursor Up
                cursorY = (cursorY - p(0, 1)).coerceAtLeast(if (isRegionActive()) regionTop else 0)
            }
            'B' -> {
                // Cursor Down
                cursorY = (cursorY + p(0, 1)).coerceAtMost(if (isRegionActive()) regionBottom else rows - 1)
            }
            'C' -> {
                // Cursor Forward
                cursorX = (cursorX + p(0, 1)).coerceAtMost(cols - 1)
            }
            'D' -> {
                // Cursor Backward
                cursorX = (cursorX - p(0, 1)).coerceAtLeast(0)
            }
            'E' -> {
                // Cursor Next Line
                cursorX = 0
                cursorY = (cursorY + p(0, 1)).coerceAtMost(if (isRegionActive()) regionBottom else rows - 1)
            }
            'F' -> {
                // Cursor Previous Line
                cursorX = 0
                cursorY = (cursorY - p(0, 1)).coerceAtLeast(if (isRegionActive()) regionTop else 0)
            }
            'G' -> {
                // Cursor Horizontal Absolute (1-based)
                cursorX = (p(0, 1) - 1).coerceIn(0, (cols - 1).coerceAtLeast(0))
            }
            'H', 'f' -> {
                // Cursor Position (1-based)
                val top = if (isRegionActive()) regionTop else 0
                val bottom = if (isRegionActive()) regionBottom else rows - 1
                cursorY = (p(0, 1) - 1).coerceIn(top, bottom.coerceAtLeast(top))
                cursorX = (p(1, 1) - 1).coerceIn(0, (cols - 1).coerceAtLeast(0))
            }
            'J' -> {
                // Erase in Display
                when (p(0, 0)) {
                    0 -> {
                        // Clear from cursor to end
                        if (cursorY in buffer.indices) {
                            for (x in cursorX.coerceAtLeast(0) until cols) {
                                buffer[cursorY][x] = ' '
                                fgColors[cursorY][x] = currentFg
                                bgColors[cursorY][x] = currentBg
                            }
                            for (y in cursorY + 1 until rows) {
                                buffer[y] = CharArray(cols) { ' ' }
                                fgColors[y] = IntArray(cols) { currentFg }
                                bgColors[y] = IntArray(cols) { currentBg }
                            }
                        }
                    }
                    1 -> {
                        // Clear from start to cursor
                        if (cursorY in buffer.indices) {
                            for (y in 0 until cursorY) {
                                buffer[y] = CharArray(cols) { ' ' }
                                fgColors[y] = IntArray(cols) { currentFg }
                                bgColors[y] = IntArray(cols) { currentBg }
                            }
                            for (x in 0..cursorX.coerceAtMost(cols - 1)) {
                                buffer[cursorY][x] = ' '
                                fgColors[cursorY][x] = currentFg
                                bgColors[cursorY][x] = currentBg
                            }
                        }
                    }
                    2 -> clear()
                    3 -> {
                        clear()
                        scrollback.clear()
                    }
                }
            }
            'K' -> {
                // Erase in Line
                if (cursorY !in buffer.indices) return
                when (p(0, 0)) {
                    0 -> {
                        for (x in cursorX.coerceAtLeast(0) until cols) {
                            buffer[cursorY][x] = ' '
                            fgColors[cursorY][x] = currentFg
                            bgColors[cursorY][x] = currentBg
                        }
                    }
                    1 -> {
                        for (x in 0..cursorX.coerceAtMost(cols - 1)) {
                            buffer[cursorY][x] = ' '
                            fgColors[cursorY][x] = currentFg
                            bgColors[cursorY][x] = currentBg
                        }
                    }
                    2 -> {
                        buffer[cursorY] = CharArray(cols) { ' ' }
                        fgColors[cursorY] = IntArray(cols) { currentFg }
                        bgColors[cursorY] = IntArray(cols) { currentBg }
                    }
                }
            }
            'L' -> {
                // Insert lines
                val count = p(0, 1).coerceAtLeast(1)
                val top = cursorY.coerceIn(regionTop, regionBottom)
                val bottom = regionBottom.coerceIn(0, rows - 1)
                for (y in bottom downTo top + count) {
                    buffer[y] = buffer[y - count]
                    fgColors[y] = fgColors[y - count]
                    bgColors[y] = bgColors[y - count]
                    attributes[y] = attributes[y - count]
                }
                for (y in top until (top + count).coerceAtMost(bottom + 1)) {
                    buffer[y] = CharArray(cols) { ' ' }
                    fgColors[y] = IntArray(cols) { currentFg }
                    bgColors[y] = IntArray(cols) { currentBg }
                    attributes[y] = IntArray(cols) { 0 }
                }
            }
            'M' -> {
                // Delete lines
                val count = p(0, 1).coerceAtLeast(1)
                val top = cursorY.coerceIn(regionTop, regionBottom)
                val bottom = regionBottom.coerceIn(0, rows - 1)
                for (y in top..bottom - count) {
                    buffer[y] = buffer[y + count]
                    fgColors[y] = fgColors[y + count]
                    bgColors[y] = bgColors[y + count]
                    attributes[y] = attributes[y + count]
                }
                for (y in (bottom - count + 1).coerceAtLeast(top)..bottom) {
                    buffer[y] = CharArray(cols) { ' ' }
                    fgColors[y] = IntArray(cols) { currentFg }
                    bgColors[y] = IntArray(cols) { currentBg }
                    attributes[y] = IntArray(cols) { 0 }
                }
            }
            'P' -> {
                // Delete characters
                val count = p(0, 1).coerceAtLeast(1)
                val row = cursorY
                if (row in buffer.indices) {
                    for (x in cursorX until cols - count) {
                        buffer[row][x] = buffer[row][x + count]
                        fgColors[row][x] = fgColors[row][x + count]
                        bgColors[row][x] = bgColors[row][x + count]
                        attributes[row][x] = attributes[row][x + count]
                    }
                    for (x in (cols - count).coerceAtLeast(cursorX) until cols) {
                        buffer[row][x] = ' '
                        fgColors[row][x] = currentFg
                        bgColors[row][x] = currentBg
                        attributes[row][x] = 0
                    }
                }
            }
            'S' -> {
                // Scroll up
                val count = p(0, 1).coerceAtLeast(1)
                if (isRegionActive()) {
                    repeat(count) { scrollRegion() }
                } else {
                    repeat(count) { scrollUp() }
                }
            }
            'T' -> {
                // Scroll down
                scrollRegionDown(p(0, 1).coerceAtLeast(1))
            }
            'X' -> {
                // Erase characters (overwrite with space)
                val count = p(0, 1).coerceAtLeast(1)
                val row = cursorY
                if (row in buffer.indices) {
                    for (x in cursorX until (cursorX + count).coerceAtMost(cols)) {
                        buffer[row][x] = ' '
                        fgColors[row][x] = currentFg
                        bgColors[row][x] = currentBg
                        attributes[row][x] = 0
                    }
                }
            }
            'd' -> {
                // Line Position Absolute (1-based)
                val top = if (isRegionActive()) regionTop else 0
                val bottom = if (isRegionActive()) regionBottom else rows - 1
                cursorY = (p(0, 1) - 1).coerceIn(top, bottom.coerceAtLeast(top))
            }
            'h', 'l' -> {
                val set = finalChar == 'h'
                if (isPrivate) {
                    for (mode in params) {
                        when (mode) {
                            7 -> autoWrap = set
                            25 -> cursorVisible = set
                            2004 -> bracketedPaste = set
                            1049 -> {
                                // Alternate screen: approximate with a clear.
                                clear()
                            }
                        }
                    }
                }
                // Non-private modes (e.g. insert mode) are ignored.
            }
            'm' -> {
                // SGR - only when there is no intermediate (e.g. ">...m"
                // is Kitty keyboard metadata, not colors).
                if (inter.isEmpty()) executeSgr(params)
            }
            'r' -> {
                // Set scroll region (DECSTBM, 1-based, inclusive)
                val top = p(0, 1) - 1
                val bottom = if (params.size > 1) p(1, rows) - 1 else rows - 1
                if (top < 0 || bottom >= rows || top >= bottom) {
                    regionTop = 0
                    regionBottom = rows - 1
                } else {
                    regionTop = top
                    regionBottom = bottom
                }
                cursorX = 0
                cursorY = regionTop
            }
            's' -> {
                // Save cursor (CSI s)
                savedX = cursorX
                savedY = cursorY
                savedFg = currentFg
                savedBg = currentBg
                savedAttr = currentAttributes
            }
            'u' -> {
                // Restore cursor (CSI u)
                cursorX = savedX.coerceIn(0, (cols - 1).coerceAtLeast(0))
                cursorY = savedY.coerceIn(0, (rows - 1).coerceAtLeast(0))
                currentFg = savedFg
                currentBg = savedBg
                currentAttributes = savedAttr
            }
            // 'c' (DA), 'n' (DSR), 't' (window ops), 'q' (cursor style),
            // 'e' etc. are intentionally ignored.
        }
    }

    private fun executeSgr(params: List<Int>) {
        val p = if (params.isEmpty()) listOf(0) else params
        var i = 0
        while (i < p.size) {
            when (val v = p[i]) {
                0 -> {
                    currentFg = Color.WHITE
                    currentBg = Color.BLACK
                    currentAttributes = 0
                }
                1 -> currentAttributes = currentAttributes or ATTR_BOLD
                2 -> currentAttributes = currentAttributes or ATTR_DIM
                3 -> currentAttributes = currentAttributes or ATTR_ITALIC
                4 -> currentAttributes = currentAttributes or ATTR_UNDERLINE
                5 -> currentAttributes = currentAttributes or ATTR_BLINK
                7 -> {
                    val tmp = currentFg
                    currentFg = currentBg
                    currentBg = tmp
                }
                8 -> currentAttributes = currentAttributes or ATTR_HIDDEN
                22 -> currentAttributes = currentAttributes and (ATTR_BOLD or ATTR_DIM).inv()
                23 -> currentAttributes = currentAttributes and ATTR_ITALIC.inv()
                24 -> currentAttributes = currentAttributes and ATTR_UNDERLINE.inv()
                25 -> currentAttributes = currentAttributes and ATTR_BLINK.inv()
                27 -> {
                    val tmp = currentFg
                    currentFg = currentBg
                    currentBg = tmp
                }
                28 -> currentAttributes = currentAttributes and ATTR_HIDDEN.inv()
                in 30..37 -> currentFg = ansiColors[v - 30]
                39 -> currentFg = Color.WHITE
                in 40..47 -> currentBg = ansiColors[v - 40]
                49 -> currentBg = Color.BLACK
                in 90..97 -> currentFg = brightColors[v - 90]
                in 100..107 -> currentBg = brightColors[v - 100]
                38, 48 -> {
                    val isFg = v == 38
                    fun apply(color: Int) {
                        if (isFg) currentFg = color else currentBg = color
                    }
                    if (i + 1 < p.size) {
                        when (p[i + 1]) {
                            5 -> {
                                if (i + 2 < p.size) {
                                    apply(palette256(p[i + 2]))
                                    i += 2
                                }
                            }
                            2 -> {
                                if (i + 4 < p.size) {
                                    apply(Color.rgb(p[i + 2], p[i + 3], p[i + 4]))
                                    i += 4
                                }
                            }
                        }
                    }
                }
            }
            i++
        }
    }

    private fun parseOscSequence(text: String, startIndex: Int): ParseResult {
        return parseUntilSt(text, startIndex)
    }

    private fun parseUntilSt(text: String, startIndex: Int): ParseResult {
        var i = startIndex
        while (i < text.length) {
            when {
                text[i] == '\u0007' -> return ParseResult(i + 1)
                text[i] == '\u001B' && i + 1 < text.length && text[i + 1] == '\\' ->
                    return ParseResult(i + 2)
                else -> i++
            }
        }
        return ParseResult(i)
    }

    fun clear() {
        for (y in 0 until rows) {
            if (y < buffer.size) {
                buffer[y] = CharArray(cols) { ' ' }
                fgColors[y] = IntArray(cols) { currentFg }
                bgColors[y] = IntArray(cols) { currentBg }
                attributes[y] = IntArray(cols) { 0 }
            }
        }
        cursorX = 0
        cursorY = 0
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        // Shrinking rows must not kill lines: the top rows that no
        // longer fit move into the scrollback (chronological order kept,
        // cursor stays on the newest content).
        if (newRows < rows) {
            val drop = (buffer.size - newRows).coerceIn(0, buffer.size)
            for (y in 0 until drop) {
                scrollback.addLast(
                    ScrollLine(
                        buffer[y].copyOf(),
                        fgColors[y].copyOf(),
                        bgColors[y].copyOf()
                    )
                )
            }
            while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            buffer = buffer.drop(drop).toTypedArray()
            fgColors = fgColors.drop(drop).toTypedArray()
            bgColors = bgColors.drop(drop).toTypedArray()
            attributes = attributes.drop(drop).toTypedArray()
            rows = buffer.size
            cursorY = (cursorY - drop).coerceAtLeast(0)
        }
        // Copy content into the new grid using real array bounds
        // (never the cols/rows fields, which may already disagree).
        val newBuffer = Array(newRows) { y ->
            CharArray(newCols) { x ->
                if (y < buffer.size && x < buffer[y].size) buffer[y][x] else ' '
            }
        }
        val newFg = Array(newRows) { y ->
            IntArray(newCols) { x ->
                if (y < fgColors.size && x < fgColors[y].size) fgColors[y][x] else currentFg
            }
        }
        val newBg = Array(newRows) { y ->
            IntArray(newCols) { x ->
                if (y < bgColors.size && x < bgColors[y].size) bgColors[y][x] else currentBg
            }
        }
        val newAttributes = Array(newRows) { y ->
            IntArray(newCols) { x ->
                if (y < attributes.size && x < attributes[y].size) attributes[y][x] else 0
            }
        }

        buffer = newBuffer
        fgColors = newFg
        bgColors = newBg
        attributes = newAttributes
        cols = newCols
        rows = newRows
        regionTop = 0
        regionBottom = rows - 1
        cursorX = cursorX.coerceIn(0, (cols - 1).coerceAtLeast(0))
        cursorY = cursorY.coerceIn(0, (rows - 1).coerceAtLeast(0))
    }

    /**
     * Visible window of grid + scrollback, [offsetFromBottom] lines up
     * from the bottom (0 = live view).
     */
    fun getWindow(offsetFromBottom: Int): EmulatorWindow {
        val total = scrollback.size + rows
        val maxOff = (total - rows).coerceAtLeast(0)
        val off = offsetFromBottom.coerceIn(0, maxOff)
        val start = total - rows - off
        val chars = Array(rows) { r ->
            val abs = start + r
            if (abs < scrollback.size) scrollback[abs].chars.copyOf()
            else buffer[(abs - scrollback.size).coerceIn(0, (buffer.size - 1).coerceAtLeast(0))].copyOf()
        }
        val fg = Array(rows) { r ->
            val abs = start + r
            if (abs < scrollback.size) scrollback[abs].fg.copyOf()
            else fgColors[(abs - scrollback.size).coerceIn(0, (fgColors.size - 1).coerceAtLeast(0))].copyOf()
        }
        val bg = Array(rows) { r ->
            val abs = start + r
            if (abs < scrollback.size) scrollback[abs].bg.copyOf()
            else bgColors[(abs - scrollback.size).coerceIn(0, (bgColors.size - 1).coerceAtLeast(0))].copyOf()
        }
        val cursorAbs = scrollback.size + cursorY
        val cy = if (cursorVisible && cursorAbs in start until start + rows) {
            cursorAbs - start
        } else {
            -1
        }
        return EmulatorWindow(chars, fg, bg, start, total, off, maxOff, cursorX, cy)
    }

    fun getScrollbackSize(): Int = scrollback.size

    /** Absolute line content (scrollback + grid), or null if out of range. */
    fun getAbsoluteLine(absLine: Int): CharArray? {
        if (absLine < 0) return null
        return if (absLine < scrollback.size) scrollback[absLine].chars
        else buffer.getOrNull(absLine - scrollback.size)
    }

    fun getBuffer(): Array<CharArray> = buffer
    fun getColors(): Array<IntArray> = fgColors
    fun getCursorX(): Int = cursorX
    fun getCursorY(): Int = cursorY
    fun getCols(): Int = cols
    fun getRows(): Int = rows

    companion object {
        const val MAX_SCROLLBACK = 500
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
