package com.kisshkitty.ui.screens.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kisshkitty.core.kitty.KittyImage
import com.kisshkitty.core.kitty.KittyImageRenderer
import com.kisshkitty.core.terminal.TerminalEmulator
import com.kisshkitty.core.ssh.SshConnectionManager
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
    private val sshConnectionManager: SshConnectionManager
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

    fun connect(config: com.kisshkitty.core.ssh.SshConfig) {
        viewModelScope.launch {
            _terminalState.value = TerminalState.Connecting
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
    sessionId: String,
    onDisconnect: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val terminalState by viewModel.terminalState.collectAsState()
    val terminalBuffer by viewModel.terminalBuffer.collectAsState()
    val cursorPosition by viewModel.cursorPosition.collectAsState()
    val terminalColors by viewModel.terminalColors.collectAsState()
    val kittyImages by viewModel.kittyImages.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (terminalState) {
                            is TerminalState.Connected -> "Connected to ${(terminalState as TerminalState.Connected).host}"
                            is TerminalState.Connecting -> "Connecting..."
                            is TerminalState.Error -> "Error"
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            // Terminal display
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
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

            // Input area - always visible
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            viewModel.sendInput(inputText + "\r")
                            inputText = ""
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        viewModel.sendInput(inputText + "\r")
                        inputText = ""
                    }
                ),
                placeholder = { Text("Type command...", color = Color.Gray) }
            )

            // Special keys row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SpecialKeyButton("Tab") { viewModel.sendSpecialKey(SpecialKey.TAB) }
                SpecialKeyButton("Esc") { viewModel.sendSpecialKey(SpecialKey.ESC) }
                SpecialKeyButton("Ctrl+C") { viewModel.sendSpecialKey(SpecialKey.CTRL_C) }
                SpecialKeyButton("Ctrl+D") { viewModel.sendSpecialKey(SpecialKey.CTRL_D) }
            }

            // Arrow keys
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
    Canvas(modifier = modifier) {
        val cellWidth = size.width / buffer[0].size
        val cellHeight = size.height / buffer.size

        // Draw terminal content
        for (y in buffer.indices) {
            for (x in buffer[y].indices) {
                val char = buffer[y][x]
                if (char != ' ') {
                    val color = colors[y][x]
                    drawRect(
                        color = Color(color),
                        topLeft = Offset(x * cellWidth, y * cellHeight),
                        size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight)
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
