package com.kisshkitty.ui.screens.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kisshkitty.core.ssh.SshConfig
import com.kisshkitty.data.Host
import com.kisshkitty.data.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val hostRepository: HostRepository
) : ViewModel() {

    private val _host = MutableStateFlow<Host?>(null)
    val host: StateFlow<Host?> = _host

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    fun loadHost(hostId: String) {
        viewModelScope.launch {
            _host.value = hostRepository.getHostById(hostId)
            // Auto-connect after loading host
            _host.value?.let { connectWithHost(it) }
        }
    }

    fun connect() {
        val currentHost = _host.value ?: return
        connectWithHost(currentHost)
    }

    private fun connectWithHost(host: Host) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting

            val config = SshConfig(
                host = host.host,
                port = host.port,
                username = host.username,
                password = host.password,
                keyPath = host.keyPath
            )

            // Update last connected time
            hostRepository.updateHost(host.copy(lastConnected = System.currentTimeMillis()))

            _connectionState.value = ConnectionState.Connected(
                sessionId = UUID.randomUUID().toString()
            )
        }
    }
}

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val sessionId: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

@Composable
fun ConnectionScreen(
    hostId: String,
    onConnected: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val host by viewModel.host.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    LaunchedEffect(hostId) {
        viewModel.loadHost(hostId)
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected((connectionState as ConnectionState.Connected).sessionId)
        }
    }

    // Show connecting screen
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = when (connectionState) {
                    is ConnectionState.Connecting -> "Connecting to ${host?.host ?: ""}..."
                    is ConnectionState.Error -> "Connection failed"
                    else -> "Loading..."
                }
            )
            if (connectionState is ConnectionState.Error) {
                TextButton(onClick = { viewModel.connect() }) {
                    Text("Retry")
                }
            }
        }
    }
}
