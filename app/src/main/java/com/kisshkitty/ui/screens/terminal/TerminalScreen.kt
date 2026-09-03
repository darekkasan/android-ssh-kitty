package com.kisshkitty.ui.screens.terminal

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kisshkitty.core.kitty.KittyImage
import com.kisshkitty.core.kitty.KittyImageRenderer
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
        if (kittyRenderer.getParser().containsKittySequence(text)) {
            val output = kittyRenderer.processOutput(text)
            terminalEmulator.processOutput(output.text)
            val newImages = output.images.map { it.image }
            if (newImages.isNotEmpty()) {
                _kittyImages.value = _kittyImages.value + newImages
            }
        } else {
            terminalEmulator.processOutput(text)
        }

        _terminalBuffer.value = terminalEmulator.getBuffer()
        _cursorPosition.value = Pair(terminalEmulator.getCursorX(), terminalEmulator.getCursorY())
        _terminalColors.value = terminalEmulator.getColors()
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
    var inputText by remember { mutableStateOf("") }

    // Auto-connect on first load
    LaunchedEffect(hostId) {
        viewModel.connect(hostId)
    }

    // Auto-focus the hidden text field
    LaunchedEffect(terminalState) {
        if (terminalState is TerminalState.Connected) {
            focusRequester.requestFocus()
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
            // Hidden text field for keyboard input
            BasicTextField(
                value = inputText,
                onValueChange = { newValue ->
                    // Check if Enter was pressed (newlines)
                    if (newValue.contains("\n")) {
                        viewModel.sendInput(inputText + "\n")
                        inputText = ""
                    } else {
                        inputText = newValue
                    }
                },
                modifier = Modifier
                    .size(0.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Enter -> {
                                    viewModel.sendInput(inputText + "\r")
                                    inputText = ""
                                    true
                                }
                                Key.Backspace -> {
                                    if (inputText.isNotEmpty()) {
                                        inputText = inputText.dropLast(1)
                                        viewModel.sendInput("\b")
                                    } else {
                                        viewModel.sendSpecialKey(SpecialKey.BACKSPACE)
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    viewModel.sendSpecialKey(SpecialKey.UP)
                                    true
                                }
                                Key.DirectionDown -> {
                                    viewModel.sendSpecialKey(SpecialKey.DOWN)
                                    true
                                }
                                Key.DirectionLeft -> {
                                    viewModel.sendSpecialKey(SpecialKey.LEFT)
                                    true
                                }
                                Key.DirectionRight -> {
                                    viewModel.sendSpecialKey(SpecialKey.RIGHT)
                                    true
                                }
                                Key.Tab -> {
                                    viewModel.sendSpecialKey(SpecialKey.TAB)
                                    true
                                }
                                Key.Escape -> {
                                    viewModel.sendSpecialKey(SpecialKey.ESC)
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                cursorBrush = SolidColor(Color.Transparent),
                textStyle = TextStyle(color = Color.Transparent)
            )

            // Terminal display
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    }
            ) {
                TerminalCanvas(
                    buffer = terminalBuffer,
                    colors = terminalColors,
                    cursorPosition = cursorPosition,
                    kittyImages = kittyImages,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Special keys - floating at bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
            ) {
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

                Spacer(modifier = Modifier.height(4.dp))

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
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontSize = 12.sp
    val fontSizePx = with(density) { fontSize.toPx() }

    Canvas(modifier = modifier) {
        val cellWidth = size.width / buffer[0].size
        val cellHeight = size.height / buffer.size

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = fontSizePx
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        // Draw terminal content
        for (y in buffer.indices) {
            for (x in buffer[y].indices) {
                val char = buffer[y][x]
                if (char != ' ') {
                    val color = colors[y][x]
                    paint.color = color
                    drawContext.canvas.nativeCanvas.drawText(
                        char.toString(),
                        x * cellWidth,
                        (y + 1) * cellHeight - 2f,
                        paint
                    )
                }
            }
        }

        // Draw cursor
        val cursorX = cursorPosition.first
        val cursorY = cursorPosition.second
        drawRect(
            color = Color.White,
            topLeft = Offset(cursorX * cellWidth, cursorY * cellHeight),
            size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
            alpha = 0.5f
        )

        // Draw Kitty images
        for (image in kittyImages) {
            drawImage(
                image = image.bitmap.asImageBitmap(),
                topLeft = Offset(0f, 0f),
                alpha = 1f
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
