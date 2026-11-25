package com.mremotedroid.app.remote.ssh

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mremotedroid.app.R
import com.mremotedroid.app.remote.RemoteSessions
import com.mremotedroid.app.remote.SessionUi
import com.mremotedroid.app.ui.theme.MRemoteTheme
import kotlinx.coroutines.launch

/** File browser for one SSH connection: browse, download, upload, rename, delete. */
class SftpActivity : ComponentActivity() {
    private var session: SftpSession? = null
    private var promptDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val (node, login) = RemoteSessions.takePending(intent.getStringExtra(RemoteSessions.EXTRA_PENDING))
            ?: run { finish(); return }
        val title = node.name.ifBlank { node.hostname }
        val s = SftpSession(
            context = this,
            nodeId = node.id,
            title = title,
            tag = "sftp:${node.id}",
            target = SshTarget(
                title = title,
                host = node.hostname,
                port = if (node.port in 1..65535) node.port else 22,
                username = login.username,
                password = login.password,
                privateKey = login.privateKey
            ),
            jump = login.tunnel
        )
        session = s
        s.connect()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { s.prompt.collect { promptDialog = SessionUi.showPrompt(this@SftpActivity, s, it, promptDialog) } }
                launch {
                    s.message.collect { text ->
                        if (text != null) {
                            Toast.makeText(this@SftpActivity, text, Toast.LENGTH_LONG).show()
                            s.message.value = null
                        }
                    }
                }
            }
        }

        setContent { MRemoteTheme { SftpScreen(s, title, onClose = { finish() }) } }
    }

    override fun onDestroy() {
        promptDialog?.dismiss()
        if (isFinishing) session?.disconnect()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpScreen(s: SftpSession, title: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by s.state.collectAsStateWithLifecycle()
    val transfer by s.transfer.collectAsStateWithLifecycle()
    var downloading by remember { mutableStateOf<SftpSession.Entry?>(null) }
    var renaming by remember { mutableStateOf<SftpSession.Entry?>(null) }
    var deleting by remember { mutableStateOf<SftpSession.Entry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var editPath by remember { mutableStateOf(false) }
    var overwrite by remember { mutableStateOf<Triple<String, Long, Uri>?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = downloading
        downloading = null
        if (uri != null && entry != null) s.download(entry) { context.contentResolver.openOutputStream(uri) }
    }
    fun upload(name: String, size: Long, uri: Uri) = s.upload(name, size) { context.contentResolver.openInputStream(uri) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (s.exists(name)) overwrite = Triple(name, size, uri) else upload(name, size, uri)
    }
    LaunchedEffect(downloading) { downloading?.let { save.launch(it.name) } }

    val ready = state as? SftpSession.State.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable(enabled = ready != null) { editPath = true }) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ready?.path ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { s.up() }, enabled = ready.path != "/") {
                            Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.sftp_up))
                        }
                        IconButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = transfer == null) {
                            Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.sftp_upload))
                        }
                        IconButton(onClick = { newFolder = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.menu_new_folder))
                        }
                        IconButton(onClick = { s.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.sftp_refresh))
                        }
                    }
                }
            )
        },
        bottomBar = {
            transfer?.let { t ->
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (t.upload) R.string.sftp_uploading else R.string.sftp_downloading, t.name),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { s.cancelTransfer() }) { Text(stringResource(R.string.action_cancel)) }
                        }
                        if (t.total > 0) {
                            LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${Formatter.formatShortFileSize(context, t.done)} / ${Formatter.formatShortFileSize(context, t.total)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val st = state) {
                is SftpSession.State.Connecting -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(st.message)
                }
                is SftpSession.State.Ended -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(st.message, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
                is SftpSession.State.Ready -> if (st.entries.isEmpty()) {
                    Text(stringResource(R.string.sftp_empty), Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(st.entries, key = { it.name }) { entry ->
                            EntryRow(
                                entry = entry,
                                onOpen = { if (entry.directory) s.open(entry.name) else if (transfer == null) downloading = entry },
                                onDownload = { if (transfer == null) downloading = entry },
                                onRename = { renaming = entry },
                                onDelete = { deleting = entry }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(stringResource(R.string.menu_new_folder), "", onDismiss = { newFolder = false }) {
            s.mkdir(it)
            newFolder = false
        }
    }
    renaming?.let { entry ->
        NameDialog(stringResource(R.string.sftp_rename), entry.name, onDismiss = { renaming = null }) {
            if (it != entry.name) s.rename(entry.name, it)
            renaming = null
        }
    }
    if (editPath && ready != null) {
        NameDialog(stringResource(R.string.sftp_go_to), ready.path, onDismiss = { editPath = false }) {
            s.goTo(it)
            editPath = false
        }
    }
    deleting?.let { entry ->
        M3AlertDialog(
            onDismissRequest = { deleting = null },
            text = {
                Text(
                    stringResource(
                        if (entry.directory && !entry.link) R.string.sftp_delete_folder else R.string.sftp_delete_file,
                        entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { s.delete(entry); deleting = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    overwrite?.let { (name, size, uri) ->
        M3AlertDialog(
            onDismissRequest = { overwrite = null },
            text = { Text(stringResource(R.string.sftp_overwrite, name)) },
            confirmButton = {
                TextButton(onClick = { upload(name, size, uri); overwrite = null }) { Text(stringResource(R.string.sftp_replace)) }
            },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun EntryRow(
    entry: SftpSession.Entry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.directory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.directory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name + if (entry.link) " →" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    if (!entry.directory) append(Formatter.formatShortFileSize(context, entry.size)).append("  ")
                    if (entry.modified > 0) {
                        append(DateUtils.formatDateTime(context, entry.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE))
                    }
                    append("  ").append(entry.permissions)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!entry.directory) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sftp_download)) },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = { menu = false; onDownload() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_rename)) },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    onClick = { menu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    M3AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
