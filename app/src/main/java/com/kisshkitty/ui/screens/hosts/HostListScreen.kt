package com.kisshkitty.ui.screens.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kisshkitty.data.Host
import com.kisshkitty.data.HostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HostListViewModel @Inject constructor(
    private val hostRepository: HostRepository
) : ViewModel() {

    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    val hosts: StateFlow<List<Host>> = _hosts

    init {
        viewModelScope.launch {
            hostRepository.getAllHosts().collect { hosts ->
                _hosts.value = hosts
            }
        }
    }

    fun deleteHost(host: Host) {
        viewModelScope.launch {
            hostRepository.deleteHost(host)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onConnectClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: HostListViewModel = hiltViewModel()
) {
    val hosts by viewModel.hosts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<Host?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KisshKitty") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Host")
            }
        }
    ) { paddingValues ->
        if (hosts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No hosts configured.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(hosts) { host ->
                    HostCard(
                        host = host,
                        onClick = { onConnectClick(host.id) },
                        onEdit = { editingHost = host },
                        onDelete = { viewModel.deleteHost(host) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditHostDialog(
            onDismiss = { showAddDialog = false },
            onSave = { host ->
                viewModel.hostRepository.addHost(host)
                showAddDialog = false
            }
        )
    }

    editingHost?.let { host ->
        AddEditHostDialog(
            host = host,
            onDismiss = { editingHost = null },
            onSave = { updatedHost ->
                viewModel.hostRepository.updateHost(updatedHost)
                editingHost = null
            }
        )
    }
}

@Composable
fun HostCard(
    host: Host,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${host.username}@${host.host}:${host.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (host.kittyEnabled) {
                    Text(
                        text = "Kitty Image Protocol",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun AddEditHostDialog(
    host: Host? = null,
    onDismiss: () -> Unit,
    onSave: (Host) -> Unit
) {
    var name by remember { mutableStateOf(host?.name ?: "") }
    var hostAddress by remember { mutableStateOf(host?.host ?: "") }
    var port by remember { mutableStateOf(host?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(host?.username ?: "") }
    var password by remember { mutableStateOf(host?.password ?: "") }
    var keyPath by remember { mutableStateOf(host?.keyPath ?: "") }
    var kittyEnabled by remember { mutableStateOf(host?.kittyEnabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (host == null) "Add Host" else "Edit Host") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hostAddress,
                    onValueChange = { hostAddress = it },
                    label = { Text("Host") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keyPath,
                    onValueChange = { keyPath = it },
                    label = { Text("Key Path (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = kittyEnabled,
                        onCheckedChange = { kittyEnabled = it }
                    )
                    Text("Enable Kitty Image Protocol")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newHost = Host(
                        id = host?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        host = hostAddress,
                        port = port.toIntOrNull() ?: 22,
                        username = username,
                        password = password.ifBlank { null },
                        keyPath = keyPath.ifBlank { null },
                        kittyEnabled = kittyEnabled
                    )
                    onSave(newHost)
                },
                enabled = name.isNotBlank() && hostAddress.isNotBlank() && username.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
