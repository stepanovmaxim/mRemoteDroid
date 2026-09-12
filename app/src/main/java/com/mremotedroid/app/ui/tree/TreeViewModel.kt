package com.mremotedroid.app.ui.tree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.importer.MRemoteNgImporter
import com.mremotedroid.app.data.repo.ConnectionRepository
import com.mremotedroid.app.data.repo.TreeRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream

class TreeViewModel(private val repo: ConnectionRepository) : ViewModel() {

    val rows: StateFlow<List<TreeRow>> = repo.observeAll()
        .map { ConnectionRepository.flatten(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() { _message.value = null }

    fun toggleExpand(node: NodeEntity) = viewModelScope.launch {
        if (node.nodeType == NodeEntity.TYPE_FOLDER) {
            repo.setExpanded(node.id, !node.expanded)
        }
    }

    fun delete(node: NodeEntity) = viewModelScope.launch {
        repo.deleteRecursive(node.id)
    }

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

    class Factory(private val repo: ConnectionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TreeViewModel(repo) as T
    }
}
