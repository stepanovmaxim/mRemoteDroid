package com.mremotedroid.app.ui.tree

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import com.mremotedroid.app.launch.RdpLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionTreeScreen(
    vm: TreeViewModel,
    onAddConnection: (parentId: String?) -> Unit,
    onEditConnection: (nodeId: String) -> Unit
) {
    val context = LocalContext.current
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var overflowOpen by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var launchTarget by remember { mutableStateOf<NodeEntity?>(null) }
    var pendingImport by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val opts = pendingImport
        if (uri != null && opts != null) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                vm.import(stream, opts.first, opts.second)
            }
        }
        pendingImport = null
    }

    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("mRemoteDroid") },
                actions = {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Новая папка") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                            onClick = { overflowOpen = false; showFolderDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Импорт confCons.xml") },
                            onClick = { overflowOpen = false; showImportDialog = true }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAddConnection(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить подключение")
            }
        }
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Нет подключений.\nДобавьте через + или импортируйте confCons.xml.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rows, key = { it.node.id }) { row ->
                    TreeRowItem(
                        node = row.node,
                        depth = row.depth,
                        hasChildren = row.hasChildren,
                        onToggle = { vm.toggleExpand(row.node) },
                        onOpen = {
                            if (row.node.nodeType == NodeEntity.TYPE_CONNECTION) launchTarget = row.node
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

    if (showFolderDialog) {
        TextPromptDialog(
            title = "Новая папка",
            label = "Название",
            onConfirm = { vm.createFolder(null, it); showFolderDialog = false },
            onDismiss = { showFolderDialog = false }
        )
    }

    if (showImportDialog) {
        ImportDialog(
            onConfirm = { pwd, replace ->
                showImportDialog = false
                pendingImport = pwd to replace
                openDocument.launch(arrayOf("text/xml", "application/xml", "*/*"))
            },
            onDismiss = { showImportDialog = false }
        )
    }

    launchTarget?.let { node ->
        LaunchDialog(
            node = node,
            installed = RdpLauncher.installedClients(context),
            onFile = {
                val res = RdpLauncher.launchViaRdpFile(context, node, vm.passwordFor(node))
                reportLaunch(context, res)
                launchTarget = null
            },
            onUri = {
                val res = RdpLauncher.launchViaUri(context, node, vm.passwordFor(node))
                reportLaunch(context, res)
                launchTarget = null
            },
            onDismiss = { launchTarget = null }
        )
    }
}

private fun reportLaunch(context: android.content.Context, res: RdpLauncher.LaunchResult) {
    val msg = when (res) {
        RdpLauncher.LaunchResult.Ok -> "Запуск клиента…"
        is RdpLauncher.LaunchResult.NoHandler -> res.detail
        is RdpLauncher.LaunchResult.Error -> res.message
    }
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

@Composable
private fun TreeRowItem(
    node: NodeEntity,
    depth: Int,
    hasChildren: Boolean,
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
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
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
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Действия")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (isFolder) {
                    DropdownMenuItem(
                        text = { Text("Добавить сюда") },
                        leadingIcon = { Icon(Icons.Default.Add, null) },
                        onClick = { menuOpen = false; onAddHere() }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Изменить") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onEdit() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menuOpen = false; confirmDelete = true }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить?") },
            text = {
                Text(
                    if (isFolder) "Папка «${node.name}» и всё её содержимое будут удалены."
                    else "Подключение «${node.name}» будет удалено."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
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
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ImportDialog(
    onConfirm: (password: String, replace: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("mR3m") }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт confCons.xml") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Пароль файла mRemoteNG. По умолчанию «mR3m», если файл не защищён паролем.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль файла") },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replace, onCheckedChange = { replace = it })
                    Text("Заменить текущий список")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password, replace) }) { Text("Выбрать файл") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun LaunchDialog(
    node: NodeEntity,
    installed: List<Pair<String, String>>,
    onFile: () -> Unit,
    onUri: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${node.hostname}:${node.port}", style = MaterialTheme.typography.bodyMedium)
                if (installed.isEmpty()) {
                    Text(
                        "RDP-клиент не найден. Установите Microsoft Remote Desktop или aFreeRDP.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Найдены клиенты: " + installed.joinToString { it.second },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (node.credentialBlob != null) {
                    Text(
                        "При открытии через .rdp пароль скопируется в буфер для вставки.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onFile) { Text("Открыть (.rdp)") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onUri) { Text("rdp://") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}
