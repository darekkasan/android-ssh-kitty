package com.kisshkitty.ui.screens.connection

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
        }
    }

    fun connect() {
        val currentHost = _host.value ?: return
        
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            
            val config = SshConfig(
                host = currentHost.host,
                port = currentHost.port,
                username = currentHost.username,
                password = currentHost.password,
                keyPath = currentHost.keyPath
            )
            
            // Update last connected time
            hostRepository.updateHost(currentHost.copy(lastConnected = System.currentTimeMillis()))
            
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            host?.let { currentHost ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = currentHost.name,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "${currentHost.username}@${currentHost.host}:${currentHost.port}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (currentHost.kittyEnabled) {
                            Text(
                                text = "Kitty Image Protocol: Enabled",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                when (connectionState) {
                    is ConnectionState.Idle -> {
                        Button(
                            onClick = { viewModel.connect() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect")
                        }
                    }
                    is ConnectionState.Connecting -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Connecting...")
                    }
                    is ConnectionState.Error -> {
                        Text(
                            text = (connectionState as ConnectionState.Error).message,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.connect() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Retry")
                        }
                    }
                    is ConnectionState.Connected -> {
                        // Navigation will handle this
                    }
                }
            }
        }
    }
}
