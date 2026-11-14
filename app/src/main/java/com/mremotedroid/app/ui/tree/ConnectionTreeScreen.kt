package com.mremotedroid.app.ui.tree

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
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
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ZoomIn
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mremotedroid.app.data.db.CredentialEntity
import com.mremotedroid.app.data.repo.ResolvedLogin
import com.mremotedroid.app.launch.ActiveSessions
import com.mremotedroid.app.launch.ConnectionShortcuts
import com.mremotedroid.app.launch.SharedFiles
import com.freerdp.freerdpcore.presentation.SessionIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    onCredentials: () -> Unit,
    onAbout: () -> Unit,
    incomingIntent: MutableStateFlow<Intent?>
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val openSessions by ActiveSessions.nodeIds.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val credentials by vm.credentials.collectAsStateWithLifecycle()
    val pendingConfCons by vm.pendingConfCons.collectAsStateWithLifecycle()
    val incoming by incomingIntent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var overflowOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderLoginFor by remember { mutableStateOf<NodeEntity?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }
    var biometricOn by remember { mutableStateOf(vm.biometricLockEnabled) }

    val openDocuments = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(context.contentResolver, uris)
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
            // Android cuts toasts to two lines, too short for import summaries.
            if (it is TreeMessage.Exported) Toast.makeText(context, it.toText(context), Toast.LENGTH_LONG).show()
            else resultText = it.toText(context)
            vm.clearMessage()
        }
    }

    // Sessions always run in the embedded FreeRDP engine.
    fun connect(node: NodeEntity, login: ResolvedLogin) {
        val res = EmbeddedRdpLauncher.launch(context, node, vm.decrypted(login))
        if (res is EmbeddedRdpLauncher.Result.Error) {
            Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
        } else {
            vm.markConnected(context, node)
        }
    }

    fun connectWithGate(node: NodeEntity) {
        // an open session needs no password: just switch to its window
        if (EmbeddedRdpLauncher.reopen(context, node)) return
        scope.launch {
            val login = vm.loginFor(node)
            if (vm.needsUnlock(login) && activity != null) {
                BiometricGate.authenticate(
                    activity = activity,
                    title = context.getString(R.string.unlock_title),
                    subtitle = node.name,
                    onSuccess = { vm.markUnlocked(); connect(node, login) },
                    onFailure = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                )
            } else {
                connect(node, login)
            }
        }
    }

    LaunchedEffect(incoming) {
        val intent = incoming ?: return@LaunchedEffect
        incomingIntent.value = null
        when (intent.action) {
            ConnectionShortcuts.ACTION_CONNECT -> {
                val node = intent.getStringExtra(ConnectionShortcuts.EXTRA_NODE_ID)?.let { vm.nodeById(it) }
                if (node != null) connectWithGate(node)
                else Toast.makeText(context, R.string.msg_connection_missing, Toast.LENGTH_LONG).show()
            }
            Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" }?.let {
                vm.importFiles(context.contentResolver, listOf(it))
            }
        }
    }

    fun openSharedFolder() {
        val authority = SharedFiles.authority(context)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                DocumentsContract.buildRootUri(authority, SharedFiles.ROOT_ID),
                DocumentsContract.Root.MIME_TYPE_ITEM
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.msg_no_files_app, Toast.LENGTH_LONG).show()
        }
    }

    fun addShortcut(node: NodeEntity) {
        if (!ConnectionShortcuts.pin(context, node)) {
            Toast.makeText(context, R.string.msg_shortcut_unsupported, Toast.LENGTH_LONG).show()
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
                                onClick = { overflowOpen = false; openDocuments.launch(arrayOf("*/*")) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_export)) },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { overflowOpen = false; showExportDialog = true }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.credentials_title)) },
                                leadingIcon = { Icon(Icons.Default.Key, null) },
                                onClick = { overflowOpen = false; onCredentials() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_shared_folder)) },
                                leadingIcon = { Icon(Icons.Default.Folder, null) },
                                onClick = { overflowOpen = false; openSharedFolder() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_default_scale)) },
                                leadingIcon = { Icon(Icons.Default.ZoomIn, null) },
                                onClick = { overflowOpen = false; showScaleDialog = true }
                            )
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
                    if (query.isBlank() && rows.size > RECENT_MIN_ROWS && recent.isNotEmpty()) {
                        item(key = "recent-header") {
                            Text(
                                stringResource(R.string.section_recent),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(recent, key = { "recent-${it.id}" }) { node ->
                            TreeRowItem(
                                node = node,
                                depth = 0,
                                hasChildren = false,
                                sessionOpen = node.id in openSessions,
                                onToggle = {},
                                onOpen = { connectWithGate(node) },
                                onEdit = { onEditConnection(node.id) },
                                onDelete = { vm.delete(node) },
                                onAddHere = {},
                                onFolderLogin = {},
                                onAddShortcut = { addShortcut(node) }
                            )
                        }
                        item(key = "recent-divider") { HorizontalDivider() }
                    }
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
                            onAddHere = { onAddConnection(row.node.id) },
                            onFolderLogin = { folderLoginFor = row.node },
                            onAddShortcut = { addShortcut(row.node) }
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

    if (showScaleDialog) {
        DefaultScaleDialog(onDismiss = { showScaleDialog = false })
    }

    resultText?.let { text ->
        AlertDialog(
            onDismissRequest = { resultText = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { resultText = null }) { Text(stringResource(R.string.action_ok)) } }
        )
    }

    if (pendingConfCons != null) {
        PasswordActionDialog(
            title = stringResource(R.string.import_title),
            hint = stringResource(R.string.import_hint),
            confirmLabel = stringResource(R.string.menu_import),
            showReplace = true,
            onConfirm = { pwd, replace -> vm.importConfCons(pwd, replace == true) },
            onDismiss = { vm.cancelConfCons() }
        )
    }

    folderLoginFor?.let { folder ->
        FolderLoginDialog(
            folder = folder,
            credentials = credentials,
            onManage = { folderLoginFor = null; onCredentials() },
            onConfirm = { id, apply ->
                vm.setFolderCredential(folder, id, apply)
                folderLoginFor = null
            },
            onDismiss = { folderLoginFor = null }
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

/** The recent section only helps when the list is longer than a screenful of glances. */
private const val RECENT_MIN_ROWS = 5

private fun TreeMessage.toText(context: Context): String = when (this) {
    is TreeMessage.ImportError -> context.getString(R.string.msg_import_error, detail.orEmpty())
    is TreeMessage.ExportError -> context.getString(R.string.msg_export_error, detail.orEmpty())
    TreeMessage.Exported -> context.getString(R.string.msg_export_done)
    TreeMessage.UnknownFormat -> context.getString(R.string.msg_import_unknown)
    TreeMessage.FileTooLarge -> context.getString(R.string.msg_import_too_large)
    is TreeMessage.ImportedExternal -> buildString {
        append(context.getString(R.string.msg_import_done, connections, folders))
        if (duplicates > 0) append(' ').append(context.getString(R.string.msg_import_duplicates, duplicates))
        if (passwordsNotTransferred > 0) append(' ').append(context.getString(R.string.msg_import_no_passwords))
    }
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
    onAddHere: () -> Unit,
    onFolderLogin: () -> Unit,
    onAddShortcut: () -> Unit
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_login)) },
                        leadingIcon = { Icon(Icons.Default.Key, null) },
                        onClick = { menuOpen = false; onFolderLogin() }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_shortcut)) },
                        leadingIcon = { Icon(Icons.Default.AddToHomeScreen, null) },
                        onClick = { menuOpen = false; onAddShortcut() }
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

@Composable
private fun DefaultScaleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(SessionIntents.getDesktopScale(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_default_scale)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.default_scale_hint), style = MaterialTheme.typography.bodySmall)
                SessionIntents.SCALE_OPTIONS.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == option, onClick = { selected = option })
                        Text(if (option == 0) stringResource(R.string.scale_auto) else "$option%")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { SessionIntents.setDesktopScale(context, selected); onDismiss() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun FolderLoginDialog(
    folder: NodeEntity,
    credentials: List<CredentialEntity>,
    onManage: () -> Unit,
    onConfirm: (credentialId: String?, applyToChildren: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(folder.credentialId) }
    var apply by remember { mutableStateOf(true) }

    @Composable
    fun Option(id: String?, label: String) {
        Row(
            Modifier.fillMaxWidth().clickable { selected = id },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected == id, onClick = { selected = id })
            Text(label)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_login)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.folder_login_hint, folder.name), style = MaterialTheme.typography.bodySmall)
                Option(null, stringResource(R.string.folder_login_none))
                credentials.forEach { Option(it.id, it.name) }
                if (credentials.isEmpty()) {
                    TextButton(onClick = onManage) { Text(stringResource(R.string.credential_add)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = apply, onCheckedChange = { apply = it })
                    Text(stringResource(R.string.folder_login_apply))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, apply && selected != null) }) {
                Text(stringResource(R.string.action_save))
            }
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

