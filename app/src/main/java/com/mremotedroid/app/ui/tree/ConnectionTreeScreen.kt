package com.mremotedroid.app.ui.tree

import android.content.Context
import com.mremotedroid.app.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import androidx.compose.material3.Button
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mremotedroid.app.launch.ActiveSessions
import com.mremotedroid.app.launch.EmbeddedRdpLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import com.mremotedroid.app.security.BiometricGate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionTreeScreen(
    vm: TreeViewModel,
    onAddConnection: (parentId: String?) -> Unit,
    onEditConnection: (nodeId: String) -> Unit,
    onAbout: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val openSessions by ActiveSessions.nodeIds.collectAsStateWithLifecycle()

    var overflowOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var biometricOn by remember { mutableStateOf(vm.biometricLockEnabled) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val opts = pendingImport
        if (uri != null && opts != null) {
            context.contentResolver.openInputStream(uri)?.let { vm.import(it, opts.first, opts.second) }
        }
        pendingImport = null
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml")
    ) { uri ->
        val pwd = pendingExportPassword
        if (uri != null && pwd != null) {
            context.contentResolver.openOutputStream(uri)?.let { vm.export(pwd, it) }
        }
        pendingExportPassword = null
    }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it.toText(context), Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    // Sessions always run in the embedded FreeRDP engine.
    fun connect(node: NodeEntity) {
        val res = EmbeddedRdpLauncher.launch(context, node, vm.passwordFor(node))
        if (res is EmbeddedRdpLauncher.Result.Error) {
            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
        }
    }

    fun connectWithGate(node: NodeEntity) {
        // an open session needs no password: just switch to its window
        if (EmbeddedRdpLauncher.reopen(context, node)) return
        if (vm.needsUnlock(node) && activity != null) {
            BiometricGate.authenticate(
                activity = activity,
                title = context.getString(R.string.unlock_title),
                subtitle = node.name,
                onSuccess = { vm.markUnlocked(); connect(node) },
                onFailure = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            )
        } else {
            connect(node)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("mRemoteDroid") },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.SortByAlpha, contentDescription = stringResource(R.string.cd_sort))
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(mode.labelRes)) },
                                    trailingIcon = {
                                        if (mode == sort) Icon(Icons.Default.Check, null)
                                    },
                                    onClick = { vm.setSort(mode); sortMenuOpen = false }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu))
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_new_folder)) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = { overflowOpen = false; showFolderDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_import)) },
                                leadingIcon = { Icon(Icons.Default.Upload, null) },
                                onClick = { overflowOpen = false; showImportDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_export)) },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { overflowOpen = false; showExportDialog = true }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_biometric)) },
                                leadingIcon = { Icon(Icons.Default.Fingerprint, null) },
                                trailingIcon = { if (biometricOn) Icon(Icons.Default.Check, null) },
                                onClick = {
                                    biometricOn = !biometricOn
                                    vm.setBiometricLock(biometricOn)
                                    overflowOpen = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_about)) },
                                leadingIcon = { Icon(Icons.Default.Info, null) },
                                onClick = { overflowOpen = false; onAbout() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddConnection(null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_connection))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_clear))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank())
                            stringResource(R.string.empty_list)
                        else stringResource(R.string.nothing_found),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.node.id }) { row ->
                        TreeRowItem(
                            node = row.node,
                            depth = row.depth,
                            hasChildren = row.hasChildren,
                            sessionOpen = row.node.id in openSessions,
                            onToggle = { vm.toggleExpand(row.node) },
                            onOpen = {
                                if (row.node.nodeType == NodeEntity.TYPE_CONNECTION) connectWithGate(row.node)
                                else vm.toggleExpand(row.node)
                            },
                            onEdit = { onEditConnection(row.node.id) },
                            onDelete = { vm.delete(row.node) },
                            onAddHere = { onAddConnection(row.node.id) }
                        )
                    }
                }
            }
        }
    }

    if (showFolderDialog) {
        TextPromptDialog(
            title = stringResource(R.string.menu_new_folder),
            label = stringResource(R.string.field_name),
            onConfirm = { vm.createFolder(null, it); showFolderDialog = false },
            onDismiss = { showFolderDialog = false }
        )
    }

    if (showImportDialog) {
        PasswordActionDialog(
            title = stringResource(R.string.import_title),
            hint = stringResource(R.string.import_hint),
            confirmLabel = stringResource(R.string.import_choose_file),
            showReplace = true,
            onConfirm = { pwd, replace ->
                showImportDialog = false
                pendingImport = pwd to (replace == true)
                openDocument.launch(arrayOf("text/xml", "application/xml", "*/*"))
            },
            onDismiss = { showImportDialog = false }
        )
    }

    if (showExportDialog) {
        PasswordActionDialog(
            title = stringResource(R.string.export_title),
            hint = stringResource(R.string.export_hint),
            confirmLabel = stringResource(R.string.export_save_file),
            showReplace = false,
            onConfirm = { pwd, _ ->
                showExportDialog = false
                pendingExportPassword = pwd
                createDocument.launch("confCons.xml")
            },
            onDismiss = { showExportDialog = false }
        )
    }
}

private val SessionOpenColor = Color(0xFF2E7D32)

private fun TreeMessage.toText(context: Context): String = when (this) {
    is TreeMessage.ImportError -> context.getString(R.string.msg_import_error, detail.orEmpty())
    is TreeMessage.ExportError -> context.getString(R.string.msg_export_error, detail.orEmpty())
    TreeMessage.Exported -> context.getString(R.string.msg_export_done)
    is TreeMessage.Imported -> buildString {
        append(context.getString(R.string.msg_import_done, connections, folders))
        if (passwordsDecrypted > 0) append(' ').append(context.getString(R.string.msg_import_passwords, passwordsDecrypted))
        if (passwordsFailed > 0) append(' ').append(context.getString(R.string.msg_import_passwords_failed, passwordsFailed))
        if (passwordsFailed > 0 && passwordsDecrypted == 0) append(' ').append(context.getString(R.string.msg_import_password_hint))
    }
}

@Composable
private fun TreeRowItem(
    node: NodeEntity,
    depth: Int,
    hasChildren: Boolean,
    sessionOpen: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddHere: () -> Unit
) {
    val isFolder = node.nodeType == NodeEntity.TYPE_FOLDER
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp)
            .padding(start = (12 + depth * 20).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFolder) {
            Icon(
                if (node.expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp).clickable(onClick = onToggle)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (node.expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        } else {
            Spacer(Modifier.width(24.dp))
            Box {
                Icon(
                    Icons.Default.Computer,
                    contentDescription = null,
                    tint = if (sessionOpen) SessionOpenColor else MaterialTheme.colorScheme.primary
                )
                if (sessionOpen) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(10.dp)
                            .background(SessionOpenColor, CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge)
            if (!isFolder) {
                val proto = runCatching { Protocol.valueOf(node.protocol) }.getOrDefault(Protocol.RDP)
                Text(
                    "${proto.displayName}  ${node.hostname}:${node.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (sessionOpen) {
                    Text(
                        stringResource(R.string.session_open_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = SessionOpenColor
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_actions))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (isFolder) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_here)) },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { menuOpen = false; onAddHere() }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menuOpen = false; confirmDelete = true }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(
                    if (isFolder) stringResource(R.string.delete_folder_message, node.name)
                    else stringResource(R.string.delete_connection_message, node.name)
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Shared dialog for import/export: a file-password field and an optional "replace" checkbox. */
@Composable
private fun PasswordActionDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    showReplace: Boolean,
    onConfirm: (password: String, replace: Boolean?) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("mR3m") }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.file_password)) },
                    singleLine = true
                )
                if (showReplace) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replace, onCheckedChange = { replace = it })
                        Text(stringResource(R.string.import_replace))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password, if (showReplace) replace else null) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

