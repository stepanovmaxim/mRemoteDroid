package com.mremotedroid.app.ui.tree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.importer.MRemoteNgImporter
import com.mremotedroid.app.data.repo.ConnectionRepository
import com.mremotedroid.app.data.repo.TreeRow
import com.mremotedroid.app.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

enum class SortMode(val label: String) {
    NAME_ASC("Имя A→Я"),
    NAME_DESC("Имя Я→A")
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val biometricLockEnabled: Boolean get() = settings.biometricLock

    fun setQuery(q: String) { _query.value = q }
    fun setSort(mode: SortMode) { _sort.value = mode }
    fun clearMessage() { _message.value = null }
    fun markUnlocked() { _unlocked.value = true }
    fun setBiometricLock(enabled: Boolean) { settings.biometricLock = enabled }

    /** Whether using this connection's stored password should require an unlock first. */
    fun needsUnlock(node: NodeEntity): Boolean =
        settings.biometricLock && node.credentialBlob != null && !_unlocked.value

    fun toggleExpand(node: NodeEntity) = viewModelScope.launch {
        if (node.nodeType == NodeEntity.TYPE_FOLDER) repo.setExpanded(node.id, !node.expanded)
    }

    fun delete(node: NodeEntity) = viewModelScope.launch { repo.deleteRecursive(node.id) }

    fun createFolder(parentId: String?, name: String) = viewModelScope.launch {
        repo.createFolder(parentId, name)
    }

    fun passwordFor(node: NodeEntity): String = repo.decryptPassword(node)

    fun import(input: InputStream, filePassword: String, replace: Boolean) = viewModelScope.launch {
        val result = runCatching {
            input.use { MRemoteNgImporter.parse(it, filePassword.ifBlank { "mR3m" }) }
        }.getOrElse {
            _message.value = "Ошибка импорта: ${it.message}"
            return@launch
        }
        if (replace) repo.replaceAll(result.nodes) else repo.addAll(result.nodes)
        _message.value = buildString {
            append("Импортировано: ${result.connections} подключений, ${result.folders} папок.")
            if (result.passwordsDecrypted + result.passwordsFailed > 0) {
                append(" Пароли: ${result.passwordsDecrypted} ок")
                if (result.passwordsFailed > 0) append(", ${result.passwordsFailed} не удалось")
                append(".")
            }
        }
    }

    fun export(filePassword: String, output: OutputStream) = viewModelScope.launch {
        runCatching {
            val xml = repo.buildExportXml(filePassword)
            withContext(Dispatchers.IO) {
                output.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            }
        }.onSuccess {
            _message.value = "Экспортировано в confCons.xml."
        }.onFailure {
            _message.value = "Ошибка экспорта: ${it.message}"
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

    class Factory(
        private val repo: ConnectionRepository,
        private val settings: AppSettings
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TreeViewModel(repo, settings) as T
    }
}
