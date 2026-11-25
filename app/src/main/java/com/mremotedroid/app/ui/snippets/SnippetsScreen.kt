package com.mremotedroid.app.ui.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mremotedroid.app.R
import com.mremotedroid.app.data.settings.Snippet
import com.mremotedroid.app.data.settings.SnippetStore

/** List of saved terminal commands with add, edit and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(onBack: () -> Unit) {
    val store = SnippetStore.get(LocalContext.current)
    val snippets by store.snippets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.snippets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.snippet_add))
            }
        }
    ) { padding ->
        if (snippets.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.snippets_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(snippets, key = { it.id }) { snippet ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = snippet }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(snippet.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                snippet.command,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { deleting = snippet }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding || editing != null) {
        SnippetDialog(
            snippet = editing,
            onSave = { name, command, enter ->
                store.put(name, command, enter, editing?.id)
                adding = false
                editing = null
            },
            onDismiss = { adding = false; editing = null }
        )
    }

    deleting?.let { snippet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.snippet_delete_message, snippet.name)) },
            confirmButton = {
                TextButton(onClick = { store.delete(snippet.id); deleting = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
fun SnippetDialog(snippet: Snippet?, onSave: (String, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(snippet?.name.orEmpty()) }
    var command by remember { mutableStateOf(snippet?.command.orEmpty()) }
    var enter by remember { mutableStateOf(snippet?.enter ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (snippet == null) R.string.snippet_add else R.string.snippet_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.snippet_command)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.clickable { enter = !enter }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enter, onCheckedChange = { enter = it })
                    Text(stringResource(R.string.snippet_enter))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (command.isNotBlank()) onSave(name.trim(), command, enter) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
