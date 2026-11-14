package com.mremotedroid.app.ui.credentials

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mremotedroid.app.R
import com.mremotedroid.app.data.db.CredentialEntity
import com.mremotedroid.app.data.repo.ConnectionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CredentialsViewModel(private val repo: ConnectionRepository) : ViewModel() {
    val credentials: StateFlow<List<CredentialEntity>> =
        repo.observeCredentials().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(existing: CredentialEntity?, name: String, username: String, domain: String, password: String?) =
        viewModelScope.launch { repo.saveCredential(existing, name, username, domain, password) }

    fun delete(credential: CredentialEntity) = viewModelScope.launch { repo.deleteCredential(credential.id) }

    class Factory(private val repo: ConnectionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CredentialsViewModel(repo) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(vm: CredentialsViewModel, onBack: () -> Unit) {
    val credentials by vm.credentials.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CredentialEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CredentialEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credentials_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.credential_add))
            }
        }
    ) { padding ->
        if (credentials.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.credentials_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(credentials, key = { it.id }) { credential ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = credential }
                            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(credential.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (credential.domain.isBlank()) credential.username
                                else "${credential.domain}\\${credential.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { deleting = credential }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        val existing = editing
        CredentialDialog(
            existing = existing,
            onSave = { name, user, domain, password ->
                vm.save(existing, name, user, domain, password)
                adding = false
                editing = null
            },
            onDismiss = { adding = false; editing = null }
        )
    }

    deleting?.let { credential ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.credential_delete_message, credential.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(credential); deleting = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun CredentialDialog(
    existing: CredentialEntity?,
    onSave: (name: String, username: String, domain: String, password: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var password by remember { mutableStateOf<String?>(null) }
    val hasStored = existing?.credentialBlob != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.credential_add else R.string.credential_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.field_user)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.field_domain)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password.orEmpty(),
                    onValueChange = { password = it },
                    label = {
                        Text(
                            stringResource(
                                if (hasStored && password == null) R.string.field_password_stored
                                else R.string.field_password
                            )
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val label = name.trim().ifEmpty { username.trim() }
                    if (label.isNotEmpty()) onSave(label, username.trim(), domain.trim(), password)
                }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
