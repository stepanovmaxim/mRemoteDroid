package com.mremotedroid.app.ui.tree

import com.mremotedroid.app.R
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mremotedroid.app.data.db.CredentialEntity
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.importer.ExternalImporter
import com.mremotedroid.app.data.importer.MRemoteNgImporter
import com.mremotedroid.app.data.repo.ConnectionRepository
import com.mremotedroid.app.data.repo.ResolvedLogin
import com.mremotedroid.app.data.repo.TreeRow
import com.mremotedroid.app.launch.ConnectionShortcuts
import com.mremotedroid.app.launch.EmbeddedRdpLauncher
import com.mremotedroid.app.launch.Reachability
import com.mremotedroid.app.launch.WakeOnLan
import com.mremotedroid.app.remote.ssh.SshTarget
import com.mremotedroid.app.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class SortMode(@StringRes val labelRes: Int) {
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc)
}

/** One-shot result messages; the screen turns them into localized text. */
sealed interface TreeMessage {
    data class ImportError(val detail: String?) : TreeMessage
    data class Imported(
        val connections: Int,
        val folders: Int,
        val passwordsDecrypted: Int,
        val passwordsFailed: Int
    ) : TreeMessage
    data class ImportedExternal(
        val connections: Int,
        val folders: Int,
        val duplicates: Int,
        val passwordsNotTransferred: Int
    ) : TreeMessage
    data object UnknownFormat : TreeMessage
    data object FileTooLarge : TreeMessage
    data object Exported : TreeMessage
    data class ExportError(val detail: String?) : TreeMessage
}

class TreeViewModel(
    private val repo: ConnectionRepository,
    private val settings: AppSettings
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _sort = MutableStateFlow(SortMode.NAME_ASC)
    val sort: StateFlow<SortMode> = _sort

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    val rows: StateFlow<List<TreeRow>> =
        combine(repo.observeAll(), _query, _sort) { nodes, q, sort ->
            computeRows(nodes, q, sort)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Connection names by id, to show which SSH connection a tunnel goes through. */
    val names: StateFlow<Map<String, String>> = repo.observeAll()
        .map { nodes -> nodes.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _message = MutableStateFlow<TreeMessage?>(null)
    val message: StateFlow<TreeMessage?> = _message

    val biometricLockEnabled: Boolean get() = settings.biometricLock

    val credentials: StateFlow<List<CredentialEntity>> =
        repo.observeCredentials().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recent: StateFlow<List<NodeEntity>> =
        repo.observeRecent(RECENT_IN_LIST).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A confCons.xml waiting for its file password. */
    private val _pendingConfCons = MutableStateFlow<ByteArray?>(null)
    val pendingConfCons: StateFlow<ByteArray?> = _pendingConfCons

    fun setQuery(q: String) { _query.value = q }
    fun setSort(mode: SortMode) { _sort.value = mode }
    fun clearMessage() { _message.value = null }
    fun markUnlocked() { _unlocked.value = true }
    fun setBiometricLock(enabled: Boolean) { settings.biometricLock = enabled }

    suspend fun loginFor(node: NodeEntity): ResolvedLogin = repo.resolveLogin(node)

    suspend fun nodeById(id: String): NodeEntity? = repo.getById(id)

    /** Whether using this login's stored passwords should require an unlock first. */
    fun needsUnlock(login: ResolvedLogin): Boolean =
        settings.biometricLock && login.hasStoredPassword && !_unlocked.value

    fun decrypted(login: ResolvedLogin) = EmbeddedRdpLauncher.Login(
        username = login.username,
        domain = login.domain,
        password = repo.decrypt(login.credentialBlob),
        gatewayPassword = repo.decrypt(login.gatewayCredentialBlob),
        privateKey = repo.decrypt(login.privateKeyBlob).ifEmpty { null },
        tunnel = login.tunnel?.let { t ->
            SshTarget(
                title = t.node.name.ifBlank { t.node.hostname },
                host = t.node.hostname,
                port = if (t.node.port in 1..65535) t.node.port else 22,
                username = t.login.username,
                password = repo.decrypt(t.login.credentialBlob).ifEmpty { null },
                privateKey = repo.decrypt(t.login.privateKeyBlob).ifEmpty { null }
            )
        }
    )

    private val reachability = Reachability(viewModelScope)
    val reachable: StateFlow<Map<String, Reachability.Status>> = reachability.status

    private val _showReachability = MutableStateFlow(settings.showReachability)
    val showReachability: StateFlow<Boolean> = _showReachability

    fun setShowReachability(enabled: Boolean) {
        settings.showReachability = enabled
        _showReachability.value = enabled
        if (enabled) refreshReachability(force = true)
    }

    /** Checks which connections answer; recent results are reused unless [force]. */
    fun refreshReachability(force: Boolean = false) {
        if (!_showReachability.value) return
        viewModelScope.launch { reachability.check(repo.allNodes(), force) }
    }

    /** Sends Wake-on-LAN packets for [node]; [done] gets whether the MAC address was valid. */
    fun wake(node: NodeEntity, done: (Boolean) -> Unit) = viewModelScope.launch {
        val mac = WakeOnLan.parseMac(node.macAddress)
        if (mac == null) {
            done(false)
            return@launch
        }
        withContext(Dispatchers.IO) { runCatching { WakeOnLan.send(mac, node.hostname) } }
        done(true)
        // give the machine time to boot, then look again
        kotlinx.coroutines.delay(WAKE_RECHECK_MS)
        refreshReachability(force = true)
    }

    fun markConnected(context: Context, node: NodeEntity) {
        val appContext = context.applicationContext
        settings.countLaunch()
        viewModelScope.launch {
            repo.markConnected(node.id)
            ConnectionShortcuts.publishRecent(appContext, repo.recent(RECENT_SHORTCUTS))
        }
    }

    fun setFolderCredential(folder: NodeEntity, credentialId: String?, applyToChildren: Boolean) =
        viewModelScope.launch { repo.setFolderCredential(folder.id, credentialId, applyToChildren) }

    fun toggleExpand(node: NodeEntity) = viewModelScope.launch {
        if (node.nodeType == NodeEntity.TYPE_FOLDER) repo.setExpanded(node.id, !node.expanded)
    }

    fun delete(node: NodeEntity) = viewModelScope.launch { repo.deleteRecursive(node.id) }

    fun createFolder(parentId: String?, name: String) = viewModelScope.launch {
        repo.createFolder(parentId, name)
    }

    /** Imports the chosen files; a confCons.xml first waits for its file password. */
    fun importFiles(resolver: ContentResolver, uris: List<Uri>) = viewModelScope.launch {
        var connections = 0
        var folders = 0
        var duplicates = 0
        var noPasswords = 0
        var imported = false
        for (uri in uris) {
            val result = runCatching {
                val file = withContext(Dispatchers.IO) { readFile(resolver, uri) }
                    ?: run { _message.value = TreeMessage.FileTooLarge; return@launch }
                val format = ExternalImporter.detect(file.second)
                if (format == ExternalImporter.Format.MREMOTENG) {
                    _pendingConfCons.value = file.second
                    null
                } else {
                    withContext(Dispatchers.Default) { ExternalImporter.parse(format, file.second, file.first) }
                }
            }.getOrElse {
                _message.value =
                    if (it is ExternalImporter.UnsupportedFormatException) TreeMessage.UnknownFormat
                    else TreeMessage.ImportError(it.message)
                return@launch
            } ?: continue
            val merged = repo.mergeImported(result.nodes)
            connections += merged.connections
            folders += merged.folders
            duplicates += merged.duplicates
            noPasswords += result.passwordsNotTransferred
            imported = true
        }
        if (imported) {
            _message.value = TreeMessage.ImportedExternal(connections, folders, duplicates, noPasswords)
        }
    }

    /** Returns the display name and content, or null if the file is too large. */
    private fun readFile(resolver: ContentResolver, uri: Uri): Pair<String?, ByteArray>? {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytesUpTo(MAX_IMPORT_BYTES + 1) } ?: ByteArray(0)
        return if (bytes.size > MAX_IMPORT_BYTES) null else name to bytes
    }

    private fun InputStream.readBytesUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun cancelConfCons() { _pendingConfCons.value = null }

    fun importConfCons(filePassword: String, replace: Boolean) = viewModelScope.launch {
        val bytes = _pendingConfCons.value ?: return@launch
        _pendingConfCons.value = null
        val result = runCatching {
            MRemoteNgImporter.parse(bytes.inputStream(), filePassword.ifBlank { "mR3m" })
        }.getOrElse {
            _message.value = TreeMessage.ImportError(it.message)
            return@launch
        }
        if (replace) repo.replaceAll(result.nodes) else repo.addAll(result.nodes)
        _message.value = TreeMessage.Imported(
            result.connections, result.folders, result.passwordsDecrypted, result.passwordsFailed
        )
    }

    fun export(filePassword: String, output: OutputStream) = viewModelScope.launch {
        runCatching {
            val xml = repo.buildExportXml(filePassword)
            withContext(Dispatchers.IO) {
                output.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            }
        }.onSuccess {
            _message.value = TreeMessage.Exported
        }.onFailure {
            _message.value = TreeMessage.ExportError(it.message)
        }
    }

    private fun computeRows(nodes: List<NodeEntity>, query: String, sort: SortMode): List<TreeRow> {
        val q = query.trim()
        if (q.isNotEmpty()) {
            // Search: flat list of matching nodes, ignoring hierarchy.
            val matches = nodes.filter { n ->
                n.name.contains(q, true) ||
                    n.hostname.contains(q, true) ||
                    n.username.contains(q, true)
            }
            return sortNodes(matches, sort).map { TreeRow(it, depth = 0, hasChildren = false) }
        }
        // Tree view, honoring collapsed folders and the chosen sort order.
        val byParent = nodes.groupBy { it.parentId }
            .mapValues { (_, v) -> sortNodes(v, sort) }
        val rows = mutableListOf<TreeRow>()
        fun walk(parentId: String?, depth: Int) {
            byParent[parentId]?.forEach { node ->
                val children = byParent[node.id].orEmpty()
                rows += TreeRow(node, depth, children.isNotEmpty())
                if (node.nodeType == NodeEntity.TYPE_FOLDER && node.expanded) walk(node.id, depth + 1)
            }
        }
        walk(null, 0)
        return rows
    }

    private fun sortNodes(nodes: List<NodeEntity>, sort: SortMode): List<NodeEntity> {
        // Folders first, then by name in the chosen direction.
        val byName = compareBy<NodeEntity> { it.name.lowercase() }
        val cmp = when (sort) {
            SortMode.NAME_ASC -> byName
            SortMode.NAME_DESC -> byName.reversed()
        }
        return nodes.sortedWith(
            compareByDescending<NodeEntity> { it.nodeType == NodeEntity.TYPE_FOLDER }.then(cmp)
        )
    }

    private companion object {
        const val RECENT_IN_LIST = 3
        const val RECENT_SHORTCUTS = 4
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val WAKE_RECHECK_MS = 30_000L
    }

    class Factory(
        private val repo: ConnectionRepository,
        private val settings: AppSettings
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TreeViewModel(repo, settings) as T
    }
}
