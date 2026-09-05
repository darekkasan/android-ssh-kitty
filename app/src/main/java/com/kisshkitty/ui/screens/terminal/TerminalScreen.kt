package com.kisshkitty.ui.screens.terminal

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.awaitEachGesture
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.awaitTouchSlopOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kisshkitty.core.kitty.KittyImage
import com.kisshkitty.core.kitty.KittyImageRenderer
import com.kisshkitty.core.kitty.KittyProtocolParser
import com.kisshkitty.core.terminal.TerminalEmulator
import com.kisshkitty.core.ssh.SshConfig
import com.kisshkitty.core.ssh.SshConnectionManager
import com.kisshkitty.data.HostRepository
import com.kisshkitty.service.SshForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sshConnectionManager: SshConnectionManager,
    private val hostRepository: HostRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _terminalState = MutableStateFlow<TerminalState>(TerminalState.Disconnected)
    val terminalState: StateFlow<TerminalState> = _terminalState

    private val terminalEmulator = TerminalEmulator()
    private val kittyRenderer = KittyImageRenderer()
    private var readingJob: kotlinx.coroutines.Job? = null

    private val _viewport = MutableStateFlow(terminalEmulator.getWindow(0))
    val viewport: StateFlow<TerminalEmulator.EmulatorWindow> = _viewport

    private val _placedImages = MutableStateFlow<List<PlacedImage>>(emptyList())
    val placedImages: StateFlow<List<PlacedImage>> = _placedImages

    private var viewportOffset = 0
    // Raw output not yet processed. An incomplete trailing escape stays
    // here until the rest arrives.
    private var pendingRaw = StringBuilder()
    // Cell metrics (px) reported by the UI for image cell resolution.
    private var cellW = 10f
    private var cellH = 20f

    fun connect(hostId: String) {
        viewModelScope.launch {
            _terminalState.value = TerminalState.Connecting
            val host = hostRepository.getHostById(hostId)
            if (host == null) {
                _terminalState.value = TerminalState.Error("Host not found")
                return@launch
            }

            val config = SshConfig(
                host = host.host,
                port = host.port,
                username = host.username,
                password = host.password,
                keyPath = host.keyPath
            )

            val result = sshConnectionManager.connect(config)
            result.fold(
                onSuccess = { connection ->
                    _terminalState.value = TerminalState.Connected(config.host)
                    startForegroundService()
                    startReadingOutput()
                },
                onFailure = { error ->
                    _terminalState.value = TerminalState.Error(error.message ?: "Connection failed")
                }
            )
        }
    }

    private fun startReadingOutput() {
        readingJob = viewModelScope.launch {
            while (sshConnectionManager.isConnected()) {
                val data = withContext(Dispatchers.IO) {
                    sshConnectionManager.readFromTerminal()
                }
                if (data != null) {
                    val text = String(data)
                    processTerminalOutput(text)
                }
                delay(16)
            }
            _terminalState.value = TerminalState.Disconnected
        }
    }

    private fun processTerminalOutput(text: String) {
        try {
            pendingRaw.append(text)
            val (complete, rest) = splitCompleteEscape(pendingRaw.toString())
            // Cap the hold-back so an abandoned escape can never grow
            // unbounded: flush it as plain text.
            pendingRaw = if (rest.length > 1_000_000) {
                StringBuilder()
            } else {
                StringBuilder(rest)
            }
            val raw = if (rest.length > 1_000_000) complete + rest else complete
            if (raw.isEmpty()) return
            for (event in kittyRenderer.processOutput(raw).events) {
                when (event) {
                    is KittyImageRenderer.OutputEvent.Text ->
                        terminalEmulator.processOutput(event.text)
                    is KittyImageRenderer.OutputEvent.Show ->
                        showImage(event.image, event.op)
                    is KittyImageRenderer.OutputEvent.Delete ->
                        applyDelete(event.selector)
                    is KittyImageRenderer.OutputEvent.Respond ->
                        sendInput(event.payload)
                }
            }
            refreshViewport()
        } catch (e: Exception) {
            // Log error but don't crash
            e.printStackTrace()
        }
    }

    /**
     * Split raw output into a complete prefix and a trailing incomplete
     * escape sequence (CSI/OSC/DCS/APC/etc. split across reads).
     */
    private fun splitCompleteEscape(raw: String): Pair<String, String> {
        val esc = raw.lastIndexOf('\u001B')
        if (esc == -1) return raw to ""
        val tail = raw.substring(esc)
        return if (isCompleteEscape(tail)) raw to ""
        else raw.substring(0, esc) to tail
    }

    private fun isCompleteEscape(tail: String): Boolean {
        if (tail.length < 2) return false
        return when (tail[1]) {
            '[' -> {
                var i = 2
                while (i < tail.length && tail[i] in '0'..'?') i++
                while (i < tail.length && tail[i] in ' '..'/') i++
                i < tail.length
            }
            ']', 'P', 'X', '^', '_' ->
                tail.contains('\u0007') || tail.contains("\u001B\\")
            '#', '(', ')', '%', '&' -> tail.length >= 3
            else -> true
        }
    }

    private fun showImage(image: KittyImage, op: KittyProtocolParser.ShowOp) {
        val anchorCol = terminalEmulator.getCursorX()
        val anchorLine = terminalEmulator.getScrollbackSize() + terminalEmulator.getCursorY()
        val bitmap = try {
            cropBitmap(image.bitmap, op)
        } catch (e: Exception) {
            null
        } ?: return
        val (placeCols, placeRows) = resolveCells(op, bitmap.width, bitmap.height)
        val placed = PlacedImage(
            imageId = image.id,
            bitmap = bitmap,
            col = anchorCol,
            absLine = anchorLine,
            cCells = placeCols,
            rCells = placeRows,
            xOffPx = op.xOffPx,
            yOffPx = op.yOffPx,
            zIndex = op.zIndex
        )
        _placedImages.value = (_placedImages.value + placed).takeLast(MAX_PLACED_IMAGES)
        if (!op.noCursorMove) {
            terminalEmulator.advanceForImage(placeCols, placeRows)
        }
    }

    private fun cropBitmap(
        src: android.graphics.Bitmap,
        op: KittyProtocolParser.ShowOp
    ): android.graphics.Bitmap {
        if (op.srcW <= 0 || op.srcH <= 0) return src
        val x = op.srcX.coerceIn(0, (src.width - 1).coerceAtLeast(0))
        val y = op.srcY.coerceIn(0, (src.height - 1).coerceAtLeast(0))
        val w = op.srcW.coerceIn(1, src.width - x)
        val h = op.srcH.coerceIn(1, src.height - y)
        if (w <= 0 || h <= 0) return src
        return android.graphics.Bitmap.createBitmap(src, x, y, w, h)
    }

    /** Resolve placement cells (spec: missing c/r derives from aspect). */
    private fun resolveCells(
        op: KittyProtocolParser.ShowOp,
        bmpW: Int,
        bmpH: Int
    ): Pair<Int, Int> {
        val w = bmpW.coerceAtLeast(1)
        val h = bmpH.coerceAtLeast(1)
        val cw = cellW.coerceAtLeast(1f)
        val ch = cellH.coerceAtLeast(1f)
        return when {
            op.destCols > 0 && op.destRows > 0 -> op.destCols to op.destRows
            op.destCols > 0 -> op.destCols to
                (op.destCols * cw * h / (w * ch)).roundToInt().coerceAtLeast(1)
            op.destRows > 0 ->
                (op.destRows * ch * w / (h * cw)).roundToInt().coerceAtLeast(1) to op.destRows
            else -> (w / cw).roundToInt().coerceAtLeast(1) to
                (h / ch).roundToInt().coerceAtLeast(1)
        }
    }

    private fun applyDelete(selector: KittyProtocolParser.DeleteSelector) {
        val parser = kittyRenderer.getParser()
        val cursorCol = terminalEmulator.getCursorX() + 1 // 1-based cells
        val cursorLine = terminalEmulator.getScrollbackSize() +
            terminalEmulator.getCursorY() + 1

        // Resolve "newest with number" to a concrete id first.
        var sel = selector
        if (sel.kind == 'n') {
            val newest = parser.newestIdForNumber(sel.number) ?: return
            sel = sel.copy(kind = 'i', imageId = newest)
        }

        fun cellMatch(p: PlacedImage, x1: Int, y1: Int): Boolean {
            return x1 in (p.col + 1)..(p.col + p.cCells) &&
                y1 in (p.absLine + 1)..(p.absLine + p.rCells)
        }

        val kept = _placedImages.value.filterNot { p ->
            when (sel.kind) {
                'a' -> true
                'i' -> p.imageId == sel.imageId
                'c' -> cellMatch(p, cursorCol, cursorLine)
                'p' -> cellMatch(p, sel.x, sel.y)
                'q' -> cellMatch(p, sel.x, sel.y) && p.zIndex == sel.z
                'x' -> sel.x in (p.col + 1)..(p.col + p.cCells)
                'y' -> sel.y in (p.absLine + 1)..(p.absLine + p.rCells)
                'z' -> p.zIndex == sel.z
                'r' -> p.imageId in sel.x..sel.y
                else -> false // frames and unknown selectors: no-op
            }
        }
        _placedImages.value = kept
        if (sel.freeData) {
            parser.freeUnreferenced(kept.map { it.imageId }.toSet())
        }
    }

    private fun refreshViewport() {
        val total = terminalEmulator.getScrollbackSize() + terminalEmulator.getRows()
        val maxOff = (total - terminalEmulator.getRows()).coerceAtLeast(0)
        viewportOffset = viewportOffset.coerceIn(0, maxOff)
        _viewport.value = terminalEmulator.getWindow(viewportOffset)
    }

    fun setViewportOffset(offset: Int) {
        viewportOffset = offset
        refreshViewport()
    }

    fun addViewportOffset(deltaLines: Int) {
        if (deltaLines != 0) setViewportOffset(viewportOffset + deltaLines)
    }

    fun setCellMetrics(charWidthPx: Float, lineHeightPx: Float) {
        cellW = charWidthPx
        cellH = lineHeightPx
    }

    fun getLine(absLine: Int): CharArray? = terminalEmulator.getAbsoluteLine(absLine)

    fun isBracketedPaste(): Boolean = terminalEmulator.bracketedPaste

    fun sendInput(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sshConnectionManager.writeToTerminal(text.toByteArray())
        }
    }

    fun sendSpecialKey(key: SpecialKey) {
        val bytes = when (key) {
            SpecialKey.ENTER -> byteArrayOf(0x0D)
            SpecialKey.TAB -> byteArrayOf(0x09)
            SpecialKey.ESC -> byteArrayOf(0x1B)
            SpecialKey.BACKSPACE -> byteArrayOf(0x7F)
            SpecialKey.CTRL_C -> byteArrayOf(0x03)
            SpecialKey.CTRL_D -> byteArrayOf(0x04)
            SpecialKey.CTRL_Z -> byteArrayOf(0x1A)
            SpecialKey.UP -> byteArrayOf(0x1B, 0x5B, 0x41)
            SpecialKey.DOWN -> byteArrayOf(0x1B, 0x5B, 0x42)
            SpecialKey.LEFT -> byteArrayOf(0x1B, 0x5B, 0x44)
            SpecialKey.RIGHT -> byteArrayOf(0x1B, 0x5B, 0x43)
            SpecialKey.HOME -> byteArrayOf(0x1B, 0x5B, 0x48)
            SpecialKey.END -> byteArrayOf(0x1B, 0x5B, 0x46)
            SpecialKey.PAGE_UP -> byteArrayOf(0x1B, 0x5B, 0x35, 0x7E)
            SpecialKey.PAGE_DOWN -> byteArrayOf(0x1B, 0x5B, 0x36, 0x7E)
        }
        sendInput(String(bytes))
    }

    fun disconnect() {
        readingJob?.cancel()
        stopForegroundService()
        sshConnectionManager.disconnect()
        _terminalState.value = TerminalState.Disconnected
    }

    /** Keep the process alive in the background so the session survives. */
    private fun startForegroundService() {
        try {
            val intent = Intent(appContext, SshForegroundService::class.java)
                .setAction(SshForegroundService.ACTION_START)
            ContextCompat.startForegroundService(appContext, intent)
        } catch (_: Exception) {
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(appContext, SshForegroundService::class.java)
                .setAction(SshForegroundService.ACTION_STOP)
            appContext.startService(intent)
        } catch (_: Exception) {
        }
    }

    fun resizeIfNeeded(cols: Int, rows: Int) {
        if (cols != terminalEmulator.getCols() || rows != terminalEmulator.getRows()) {
            terminalEmulator.resize(cols, rows)
            sshConnectionManager.resizeTerminal(cols, rows)
            refreshViewport()
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

sealed class TerminalState {
    data object Disconnected : TerminalState()
    data object Connecting : TerminalState()
    data class Connected(val host: String) : TerminalState()
    data class Error(val message: String) : TerminalState()
}

enum class SpecialKey {
    ENTER, TAB, ESC, BACKSPACE, CTRL_C, CTRL_D, CTRL_Z,
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN
}

// Terminal font size. The grid (cols x rows) is derived from this via
// monospace metrics, so glyphs never overlap and rows never stretch.
private val TERMINAL_FONT_SIZE = 14.sp

// Sentinel kept in the hidden input field so the keyboard Backspace key
// always has something to delete (an empty field produces no event).
// U+FEFF is invisible and is never sent: diffs only send the suffix.
private const val INPUT_SENTINEL = "\uFEFF"

private data class CellMetrics(
    val textSizePx: Float,
    val charWidth: Float,
    val lineHeight: Float,
    val baselineOffset: Float
)

/** An image anchored to an absolute terminal line. */
data class PlacedImage(
    val imageId: Int,
    val bitmap: android.graphics.Bitmap,
    val col: Int,
    val absLine: Int,
    val cCells: Int,
    val rCells: Int,
    val xOffPx: Int,
    val yOffPx: Int,
    val zIndex: Int
)

private const val MAX_PLACED_IMAGES = 24

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    hostId: String,
    onDisconnect: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val terminalState by viewModel.terminalState.collectAsState()
    val viewport by viewModel.viewport.collectAsState()
    val placedImages by viewModel.placedImages.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var inputText by remember { mutableStateOf(INPUT_SENTINEL) }
    var isTextFieldPlaced by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    // Selection in absolute lines (col, absLine) so it survives scrolling.
    var selectionAbs by remember { mutableStateOf<Pair<Pair<Int, Int>, Pair<Int, Int>>?>(null) }
    // Fresh read for gestures without relaunch churn.
    val selectionRef = rememberUpdatedState(selectionAbs)
    var scrollAcc by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    // Monospace cell metrics. The terminal grid is derived from the font,
    // so glyphs never overlap and rows never stretch to fill the screen.
    val cellMetrics = remember(density) {
        val paint = android.graphics.Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = with(density) { TERMINAL_FONT_SIZE.toPx() }
            isAntiAlias = true
        }
        val fm = paint.fontMetrics
        CellMetrics(
            textSizePx = paint.textSize,
            charWidth = paint.measureText("M"),
            lineHeight = fm.bottom - fm.top,
            baselineOffset = -fm.top
        )
    }

    val terminalCols = if (viewport.chars.isNotEmpty()) viewport.chars[0].size else 80
    val terminalRows = viewport.chars.size.coerceAtLeast(1)
    // Always-fresh window start for gestures (no relaunch churn).
    val windowStartRef = rememberUpdatedState(viewport.windowStart)

    // Report metrics for image cell resolution, then fit the grid.
    LaunchedEffect(cellMetrics) {
        viewModel.setCellMetrics(cellMetrics.charWidth, cellMetrics.lineHeight)
    }

    fun cellOfAbs(offset: Offset): Pair<Int, Int> {
        val x = (offset.x / cellMetrics.charWidth).toInt()
            .coerceIn(0, (terminalCols - 1).coerceAtLeast(0))
        val y = (offset.y / cellMetrics.lineHeight).toInt()
            .coerceIn(0, (terminalRows - 1).coerceAtLeast(0))
        return x to (windowStartRef.value + y)
    }

    fun selectedText(): String {
        val sel = selectionAbs ?: return ""
        val (ax, ay) = sel.first
        val (bx, by) = sel.second
        val (sx, sy, ex, ey) = if (ay < by || (ay == by && ax <= bx)) {
            listOf(ax, ay, bx, by)
        } else {
            listOf(bx, by, ax, ay)
        }
        return buildString {
            for (absY in sy..ey) {
                val row = viewModel.getLine(absY) ?: continue
                val fromX = if (absY == sy) sx.coerceIn(0, row.size) else 0
                val toX = if (absY == ey) ex.coerceIn(0, row.size) else row.size
                if (fromX < toX && fromX < row.size) {
                    append(row.slice(fromX until toX.coerceAtMost(row.size)).joinToString("").trimEnd())
                }
                if (absY != ey) append('\n')
            }
        }
    }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Auto-connect on first load
    LaunchedEffect(hostId) {
        viewModel.connect(hostId)
    }

    // Auto-focus only after the hidden text field is actually placed.
    // requestFocus() triggers BringIntoView asynchronously, and calling it
    // before placement crashes with "BringIntoViewRequester ... before
    // parents are placed" (a try-catch can't help since it is async).
    LaunchedEffect(terminalState, isTextFieldPlaced) {
        if (terminalState is TerminalState.Connected && isTextFieldPlaced) {
            kotlinx.coroutines.delay(200)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (terminalState) {
                            is TerminalState.Connected -> "Connected to ${(terminalState as TerminalState.Connected).host}"
                            is TerminalState.Connecting -> "Connecting..."
                            is TerminalState.Error -> "Error: ${(terminalState as TerminalState.Error).message}"
                            is TerminalState.Disconnected -> "Disconnected"
                        }
                    )
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onDisconnect()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Disconnect")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(paddingValues)
                .imePadding()
        ) {
            // Terminal display
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { px ->
                        // Fit the grid to the screen: no stretched rows,
                        // no overlapping glyphs.
                        val cols = (px.width / cellMetrics.charWidth).toInt()
                            .coerceIn(20, 256)
                        val rows = (px.height / cellMetrics.lineHeight).toInt()
                            .coerceIn(8, 200)
                        viewModel.resizeIfNeeded(cols, rows)
                    }
                    .pointerInput(terminalCols, terminalRows, cellMetrics) {
                        // One gesture loop: tap, plain-drag scroll,
                        // and long-press-hold select never fight.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // Race touch-slop (scroll) vs long-press
                            // (select) vs lift (tap).
                            var slop: PointerInputChange? = null
                            var longPressed = false
                            try {
                                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                    slop = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                        change.consume()
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                longPressed = true
                            }
                            when {
                                longPressed -> {
                                    val anchor = cellOfAbs(down.position)
                                    selectionAbs = anchor to anchor
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val move = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (!move.pressed) break
                                        selectionAbs = anchor to cellOfAbs(move.position)
                                        move.consume()
                                    }
                                }
                                slop != null -> {
                                    scrollAcc = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val move = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (!move.pressed) break
                                        scrollAcc += -move.positionChange().y
                                        val lines = (scrollAcc / cellMetrics.lineHeight).toInt()
                                        if (lines != 0) {
                                            scrollAcc -= lines * cellMetrics.lineHeight
                                            viewModel.addViewportOffset(lines)
                                        }
                                        move.consume()
                                    }
                                }
                                else -> {
                                    if (selectionRef.value != null) {
                                        selectionAbs = null
                                    } else {
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                    }
                                }
                            }
                        }
                    }
            ) {
                TerminalCanvas(
                    buffer = viewport.chars,
                    colors = viewport.fg,
                    bgColors = viewport.bg,
                    cursorPosition = viewport.cursorX to viewport.cursorY,
                    placedImages = placedImages,
                    windowStartLine = viewport.windowStart,
                    selectionAbs = selectionAbs,
                    textSizePx = cellMetrics.textSizePx,
                    cellWidth = cellMetrics.charWidth,
                    cellHeight = cellMetrics.lineHeight,
                    baselineOffset = cellMetrics.baselineOffset,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Scrollbar (only when there is scrollback to show).
            if (viewport.maxOffset > 0) {
                ScrollStrip(
                    offset = viewport.offset,
                    maxOffset = viewport.maxOffset,
                    visibleFraction = viewport.chars.size.toFloat() /
                        viewport.totalLines.toFloat().coerceAtLeast(1f),
                    onScrollTo = { viewModel.setViewportOffset(it) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(20.dp)
                )
            }

            // Hidden text field for keyboard input.
            // NOTE: must NOT be size(0.dp). A zero-size field breaks
            // BringIntoView on requestFocus() and crashes the app.
            // The field always holds INPUT_SENTINEL so the Backspace key
            // always deletes something (an empty field fires no event).
            BasicTextField(
                value = inputText,
                onValueChange = { newValue ->
                    // Consume-and-clear: everything still in the field was
                    // already sent, so only the fresh suffix is new input.
                    // (This also makes IME full-replacements harmless: no
                    // phantom backspaces, no re-sent history.)
                    var fresh = if (newValue.startsWith(INPUT_SENTINEL)) {
                        newValue.substring(INPUT_SENTINEL.length)
                    } else {
                        newValue
                    }
                    val newlineAt = fresh.indexOfFirst { it == '\n' || it == '\r' }
                    if (newlineAt != -1) {
                        val before = fresh.substring(0, newlineAt)
                        if (before.isNotEmpty()) viewModel.sendInput(before)
                        viewModel.sendInput("\r")
                    } else if (fresh.isNotEmpty()) {
                        viewModel.sendInput(fresh)
                    } else if (newValue.isEmpty()) {
                        // The sentinel itself was deleted: real Backspace.
                        viewModel.sendInput("\b")
                    }
                    inputText = INPUT_SENTINEL
                },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .size(1.dp)
                    .alpha(0.01f)
                    .onPlaced { isTextFieldPlaced = true },
                // Password mode: no suggestions / autocorrect on the terminal.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false
                ),
                cursorBrush = SolidColor(Color.Transparent),
                textStyle = TextStyle(color = Color.Transparent)
            )

            // Bottom controls: selection actions, collapsible key bar, toggle.
            // The bar is hidden by default so it never covers the terminal.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (selectionAbs != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            val text = selectedText()
                            if (text.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                            selectionAbs = null
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { selectionAbs = null }) {
                            Text("Cancel")
                        }
                    }
                }

                if (showKeys) {
                    // Main special keys
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SpecialKeyButton("Tab") { viewModel.sendSpecialKey(SpecialKey.TAB) }
                        SpecialKeyButton("Esc") { viewModel.sendSpecialKey(SpecialKey.ESC) }
                        SpecialKeyButton("Paste") {
                            val clip = clipboard.getText()?.text ?: ""
                            if (clip.isNotEmpty()) {
                                val text = if (viewModel.isBracketedPaste()) {
                                    "\u001B[200~$clip\u001B[201~"
                                } else {
                                    // Bare LFs would staircase: send CRs.
                                    clip.replace("\r\n", "\r").replace('\n', '\r')
                                }
                                viewModel.sendInput(text)
                            }
                        }
                        SpecialKeyButton("Ctrl+C") { viewModel.sendSpecialKey(SpecialKey.CTRL_C) }
                        SpecialKeyButton("Ctrl+D") { viewModel.sendSpecialKey(SpecialKey.CTRL_D) }
                    }

                    // Arrow keys
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpecialKeyButton("↑") { viewModel.sendSpecialKey(SpecialKey.UP) }
                        Spacer(modifier = Modifier.width(8.dp))
                        SpecialKeyButton("←") { viewModel.sendSpecialKey(SpecialKey.LEFT) }
                        SpecialKeyButton("↓") { viewModel.sendSpecialKey(SpecialKey.DOWN) }
                        SpecialKeyButton("→") { viewModel.sendSpecialKey(SpecialKey.RIGHT) }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Jump back to the live view when scrolled up.
                    if (viewport.offset > 0) {
                        SmallFloatingActionButton(
                            onClick = { viewModel.setViewportOffset(0) }
                        ) {
                            Text("↓")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    SmallFloatingActionButton(
                        onClick = { showKeys = !showKeys }
                    ) {
                        Icon(
                            if (showKeys) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                            contentDescription = "Toggle keys"
                        )
                    }
                }
            }

            // Error display
            if (terminalState is TerminalState.Error) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .pointerInput(Unit) {
                            detectTapGestures {
                                viewModel.connect(hostId)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = (terminalState as TerminalState.Error).message,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Tap to retry",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalCanvas(
    buffer: Array<CharArray>,
    colors: Array<IntArray>,
    bgColors: Array<IntArray>,
    cursorPosition: Pair<Int, Int>,
    placedImages: List<PlacedImage>,
    windowStartLine: Int,
    selectionAbs: Pair<Pair<Int, Int>, Pair<Int, Int>>?,
    textSizePx: Float,
    cellWidth: Float,
    cellHeight: Float,
    baselineOffset: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (buffer.isEmpty() || buffer[0].isEmpty()) return@Canvas
        val cols = buffer[0].size

        fun drawPlaced(p: PlacedImage) {
            val bw = p.bitmap.width
            val bh = p.bitmap.height
            if (bw <= 0 || bh <= 0) return
            val viewRow = p.absLine - windowStartLine
            val dstW = (p.cCells * cellWidth).coerceAtLeast(1f)
            val dstH = (p.rCells * cellHeight).coerceAtLeast(1f)
            drawImage(
                image = p.bitmap.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (p.col * cellWidth + p.xOffPx).toInt(),
                    (viewRow * cellHeight + p.yOffPx).toInt()
                ),
                dstSize = IntSize(dstW.toInt().coerceAtLeast(1), dstH.toInt().coerceAtLeast(1))
            )
        }

        // Images with negative z-index go under the text.
        for (p in placedImages.sortedWith(compareBy({ it.zIndex }, { it.imageId }))) {
            if (p.zIndex < 0) drawPlaced(p)
        }

        // Background fills.
        for (y in buffer.indices) {
            val bgRow = bgColors.getOrNull(y) ?: continue
            var x = 0
            while (x < buffer[y].size) {
                val bg = bgRow.getOrNull(x) ?: android.graphics.Color.BLACK
                if (bg != android.graphics.Color.BLACK) {
                    var x2 = x
                    while (x2 < buffer[y].size &&
                        (bgRow.getOrNull(x2) ?: android.graphics.Color.BLACK) == bg
                    ) {
                        x2++
                    }
                    drawRect(
                        color = Color(bg),
                        topLeft = Offset(x * cellWidth, y * cellHeight),
                        size = androidx.compose.ui.geometry.Size(
                            (x2 - x) * cellWidth,
                            cellHeight
                        )
                    )
                    x = x2
                } else {
                    x++
                }
            }
        }

        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = textSizePx
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            // Draw terminal content. Cell geometry comes from the font,
            // so glyphs fit their cells exactly.
            for (y in buffer.indices) {
                for (x in buffer[y].indices) {
                    val char = buffer[y][x]
                    if (char != ' ') {
                        paint.color = colors[y][x]
                        canvas.nativeCanvas.drawText(
                            char.toString(),
                            x * cellWidth,
                            y * cellHeight + baselineOffset,
                            paint
                        )
                    }
                }
            }
        }

        // Draw text selection (absolute lines mapped into the window).
        selectionAbs?.let { sel ->
            val (ax, ay) = sel.first
            val (bx, by) = sel.second
            val (sx, sy, ex, ey) = if (ay < by || (ay == by && ax <= bx)) {
                listOf(ax, ay, bx, by)
            } else {
                listOf(bx, by, ax, ay)
            }
            for (absY in sy..ey) {
                val y = absY - windowStartLine
                if (y !in buffer.indices) continue
                val fromX = (if (absY == sy) sx else 0).coerceIn(0, cols)
                val toX = (if (absY == ey) ex else cols).coerceIn(0, cols)
                if (fromX <= toX) {
                    drawRect(
                        color = Color(0xFF3390FF),
                        topLeft = Offset(fromX * cellWidth, y * cellHeight),
                        size = androidx.compose.ui.geometry.Size(
                            (toX - fromX + 1) * cellWidth,
                            cellHeight
                        ),
                        alpha = 0.35f
                    )
                }
            }
        }

        // Draw cursor (-1 hides it: scrolled away or cursor hidden).
        val cursorX = cursorPosition.first.coerceIn(0, (cols - 1).coerceAtLeast(0))
        val cursorY = cursorPosition.second
        if (cursorY in buffer.indices) {
            drawRect(
                color = Color.White,
                topLeft = Offset(cursorX * cellWidth, cursorY * cellHeight),
                size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                alpha = 0.5f
            )
        }

        // Images with non-negative z-index go over the text.
        for (p in placedImages.sortedWith(compareBy({ it.zIndex }, { it.imageId }))) {
            if (p.zIndex >= 0) drawPlaced(p)
        }
    }
}

@Composable
fun ScrollStrip(
    offset: Int,
    maxOffset: Int,
    visibleFraction: Float,
    onScrollTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var stripHeight by remember { mutableStateOf(0) }
    val thumbFraction = visibleFraction.coerceIn(0.05f, 1f)
    val thumbPos = if (maxOffset == 0) 0f else offset.toFloat() / maxOffset.toFloat()

    Box(
        modifier = modifier
            .onSizeChanged { stripHeight = it.height }
            .pointerInput(maxOffset) {
                detectTapGestures { offsetPos ->
                    if (stripHeight > 0) {
                        val frac = (offsetPos.y / stripHeight).coerceIn(0f, 1f)
                        onScrollTo(((1f - frac) * maxOffset).toInt().coerceIn(0, maxOffset))
                    }
                }
            }
            .pointerInput(maxOffset) {
                detectDragGestures(
                    onDragStart = { start ->
                        if (stripHeight > 0) {
                            val frac = (start.y / stripHeight).coerceIn(0f, 1f)
                            onScrollTo(((1f - frac) * maxOffset).toInt().coerceIn(0, maxOffset))
                        }
                    },
                    onDrag = { change, _ ->
                        if (stripHeight > 0) {
                            val frac = (change.position.y / stripHeight).coerceIn(0f, 1f)
                            onScrollTo(((1f - frac) * maxOffset).toInt().coerceIn(0, maxOffset))
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackW = 4.dp.toPx()
            drawRect(
                color = Color.White,
                topLeft = Offset((size.width - trackW) / 2, 0f),
                size = androidx.compose.ui.geometry.Size(trackW, size.height),
                alpha = 0.15f
            )
            val thumbH = (size.height * thumbFraction).coerceAtLeast(24.dp.toPx())
            val thumbY = ((size.height - thumbH) * (1f - thumbPos)).coerceIn(0f, size.height - thumbH)
            drawRect(
                color = Color.White,
                topLeft = Offset((size.width - trackW) / 2, thumbY),
                size = androidx.compose.ui.geometry.Size(trackW, thumbH),
                alpha = 0.5f
            )
        }
    }
}

@Composable
fun SpecialKeyButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
