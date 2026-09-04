package com.kisshkitty.ui.screens.terminal

import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val sshConnectionManager: SshConnectionManager,
    private val hostRepository: HostRepository
) : ViewModel() {

    private val _terminalState = MutableStateFlow<TerminalState>(TerminalState.Disconnected)
    val terminalState: StateFlow<TerminalState> = _terminalState

    private val _terminalBuffer = MutableStateFlow(Array(24) { CharArray(80) { ' ' } })
    val terminalBuffer: StateFlow<Array<CharArray>> = _terminalBuffer

    private val _cursorPosition = MutableStateFlow(Pair(0, 0))
    val cursorPosition: StateFlow<Pair<Int, Int>> = _cursorPosition

    private val _terminalColors = MutableStateFlow(Array(24) { IntArray(80) { android.graphics.Color.WHITE } })
    val terminalColors: StateFlow<Array<IntArray>> = _terminalColors

    private val _kittyImages = MutableStateFlow<List<KittyImage>>(emptyList())
    val kittyImages: StateFlow<List<KittyImage>> = _kittyImages

    private val terminalEmulator = TerminalEmulator()
    private val kittyRenderer = KittyImageRenderer()
    private var readingJob: kotlinx.coroutines.Job? = null
    // Raw bytes not yet processed. Incomplete trailing Kitty sequences
    // stay here until the rest arrives.
    private var pendingRaw = StringBuilder()

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
            var raw = pendingRaw.toString()
            // A Kitty APC sequence can be split across reads. Hold back a
            // trailing unterminated sequence until the rest arrives.
            // (Cap the hold-back so garbage can never grow unbounded.)
            var holdBack = ""
            val start = raw.lastIndexOf(KittyProtocolParser.APC_START)
            val end = raw.lastIndexOf(KittyProtocolParser.APC_END)
            if (start != -1 && start > end) {
                if (raw.length - start > 2_000_000) {
                    // Too long to be a real sequence: flush it as text.
                } else {
                    holdBack = raw.substring(start)
                    raw = raw.substring(0, start)
                }
            }
            if (raw.isNotEmpty()) {
                if (kittyRenderer.getParser().containsKittySequence(raw)) {
                    val output = kittyRenderer.processOutput(raw)
                    terminalEmulator.processOutput(output.text)
                    val newImages = output.images.map { it.image }
                    if (newImages.isNotEmpty()) {
                        _kittyImages.value = _kittyImages.value + newImages
                    }
                } else {
                    terminalEmulator.processOutput(raw)
                }

                _terminalBuffer.value = terminalEmulator.getBuffer()
                _cursorPosition.value = Pair(terminalEmulator.getCursorX(), terminalEmulator.getCursorY())
                _terminalColors.value = terminalEmulator.getColors()
            }
            pendingRaw = StringBuilder(holdBack)
        } catch (e: Exception) {
            // Log error but don't crash
            e.printStackTrace()
        }
    }

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

    fun resize(cols: Int, rows: Int) {
        terminalEmulator.resize(cols, rows)
        sshConnectionManager.resizeTerminal(cols, rows)
    }

    fun disconnect() {
        readingJob?.cancel()
        sshConnectionManager.disconnect()
        _terminalState.value = TerminalState.Disconnected
    }

    fun resizeIfNeeded(cols: Int, rows: Int) {
        if (cols != terminalEmulator.getCols() || rows != terminalEmulator.getRows()) {
            terminalEmulator.resize(cols, rows)
            sshConnectionManager.resizeTerminal(cols, rows)
            _terminalBuffer.value = terminalEmulator.getBuffer()
            _cursorPosition.value = Pair(terminalEmulator.getCursorX(), terminalEmulator.getCursorY())
            _terminalColors.value = terminalEmulator.getColors()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    hostId: String,
    onDisconnect: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val terminalState by viewModel.terminalState.collectAsState()
    val terminalBuffer by viewModel.terminalBuffer.collectAsState()
    val cursorPosition by viewModel.cursorPosition.collectAsState()
    val terminalColors by viewModel.terminalColors.collectAsState()
    val kittyImages by viewModel.kittyImages.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var inputText by remember { mutableStateOf(INPUT_SENTINEL) }
    var isTextFieldPlaced by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Pair<Pair<Int, Int>, Pair<Int, Int>>?>(null) }

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

    val terminalCols = if (terminalBuffer.isNotEmpty()) terminalBuffer[0].size else 80
    val terminalRows = terminalBuffer.size.coerceAtLeast(1)

    fun cellOf(offset: Offset): Pair<Int, Int> {
        val x = (offset.x / cellMetrics.charWidth).toInt()
            .coerceIn(0, (terminalCols - 1).coerceAtLeast(0))
        val y = (offset.y / cellMetrics.lineHeight).toInt()
            .coerceIn(0, (terminalRows - 1).coerceAtLeast(0))
        return x to y
    }

    fun selectedText(): String {
        val sel = selection ?: return ""
        val (ax, ay) = sel.first
        val (bx, by) = sel.second
        val (sx, sy, ex, ey) = if (ay < by || (ay == by && ax <= bx)) {
            listOf(ax, ay, bx, by)
        } else {
            listOf(bx, by, ax, ay)
        }
        return buildString {
            for (y in sy..ey) {
                val row = terminalBuffer.getOrNull(y) ?: continue
                val fromX = if (y == sy) sx.coerceIn(0, row.size) else 0
                val toX = if (y == ey) ex.coerceIn(0, row.size) else row.size
                if (fromX < toX && fromX < row.size) {
                    append(row.slice(fromX until toX.coerceAtMost(row.size)).joinToString("").trimEnd())
                }
                if (y != ey) append('\n')
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
                .padding(paddingValues)
                .imePadding()
                .background(Color.Black)
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
                    .pointerInput(terminalCols, terminalRows) {
                        detectTapGestures(
                            onTap = {
                                if (selection != null) {
                                    selection = null
                                } else {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            },
                            onLongPress = { offset ->
                                val cell = cellOf(offset)
                                selection = cell to cell
                            }
                        )
                    }
                    .pointerInput(terminalCols, terminalRows) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val cell = cellOf(offset)
                                selection = cell to cell
                            },
                            onDrag = { change, _ ->
                                val anchor = selection?.first ?: cellOf(change.position)
                                selection = anchor to cellOf(change.position)
                            }
                        )
                    }
            ) {
                TerminalCanvas(
                    buffer = terminalBuffer,
                    colors = terminalColors,
                    cursorPosition = cursorPosition,
                    kittyImages = kittyImages,
                    selection = selection,
                    textSizePx = cellMetrics.textSizePx,
                    cellWidth = cellMetrics.charWidth,
                    cellHeight = cellMetrics.lineHeight,
                    baselineOffset = cellMetrics.baselineOffset,
                    modifier = Modifier.fillMaxSize()
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
                    val common = newValue.commonPrefixWith(inputText).length
                    val deleted = inputText.length - common
                    val added = newValue.substring(common)
                    repeat(deleted) {
                        viewModel.sendInput("\b")
                    }
                    if (added.contains("\n") || added.contains("\r")) {
                        viewModel.sendInput("\r")
                        inputText = INPUT_SENTINEL
                    } else {
                        if (added.isNotEmpty()) {
                            viewModel.sendInput(added)
                        }
                        inputText = if (newValue.isEmpty()) INPUT_SENTINEL else newValue
                    }
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
                if (selection != null) {
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
                            selection = null
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { selection = null }) {
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
                    horizontalArrangement = Arrangement.End
                ) {
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
    cursorPosition: Pair<Int, Int>,
    kittyImages: List<KittyImage>,
    selection: Pair<Pair<Int, Int>, Pair<Int, Int>>?,
    textSizePx: Float,
    cellWidth: Float,
    cellHeight: Float,
    baselineOffset: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (buffer.isEmpty() || buffer[0].isEmpty()) return@Canvas
        val cols = buffer[0].size

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

        // Draw text selection
        selection?.let { sel ->
            val (ax, ay) = sel.first
            val (bx, by) = sel.second
            val (sx, sy, ex, ey) = if (ay < by || (ay == by && ax <= bx)) {
                listOf(ax, ay, bx, by)
            } else {
                listOf(bx, by, ax, ay)
            }
            for (y in sy.coerceIn(0, buffer.size - 1)..ey.coerceIn(0, buffer.size - 1)) {
                val fromX = (if (y == sy) sx else 0).coerceIn(0, cols)
                val toX = (if (y == ey) ex else cols).coerceIn(0, cols)
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

        // Draw cursor
        val cursorX = cursorPosition.first.coerceIn(0, (cols - 1).coerceAtLeast(0))
        val cursorY = cursorPosition.second.coerceIn(0, (buffer.size - 1).coerceAtLeast(0))
        drawRect(
            color = Color.White,
            topLeft = Offset(cursorX * cellWidth, cursorY * cellHeight),
            size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
            alpha = 0.5f
        )

        // Draw Kitty images, scaled to fit the screen
        for (image in kittyImages) {
            val bw = image.bitmap.width
            val bh = image.bitmap.height
            if (bw > 0 && bh > 0) {
                val scale = minOf(size.width / bw, size.height / bh).coerceIn(0.1f, 4f)
                drawImage(
                    image = image.bitmap.asImageBitmap(),
                    dstSize = IntSize(
                        (bw * scale).toInt().coerceAtLeast(1),
                        (bh * scale).toInt().coerceAtLeast(1)
                    )
                )
            }
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
