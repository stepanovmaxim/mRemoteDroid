package com.mremotedroid.app.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import com.mremotedroid.app.data.repo.ConnectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class EditState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val name: String = "",
    val protocol: Protocol = Protocol.RDP,
    val hostname: String = "",
    val port: String = "3389",
    val username: String = "",
    val domain: String = "",
    /** null = unchanged, "" = cleared, other = new password */
    val password: String? = null,
    val hasStoredPassword: Boolean = false,
    val colorDepth: Int = 32,
    val fullscreen: Boolean = true,
    val consoleSession: Boolean = false,
    val gatewayHostname: String = "",
    val description: String = ""
)

class EditViewModel(
    private val repo: ConnectionRepository,
    private val nodeId: String?,
    private val parentId: String?
) : ViewModel() {

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state
    private var existing: NodeEntity? = null

    init {
        viewModelScope.launch {
            val node = nodeId?.let { repo.getById(it) }
            existing = node
            _state.value = if (node != null) {
                EditState(
                    loading = false,
                    isNew = false,
                    name = node.name,
                    protocol = runCatching { Protocol.valueOf(node.protocol) }.getOrDefault(Protocol.RDP),
                    hostname = node.hostname,
                    port = node.port.toString(),
                    username = node.username,
                    domain = node.domain,
                    password = null,
                    hasStoredPassword = node.credentialBlob != null,
                    colorDepth = node.colorDepth,
                    fullscreen = node.fullscreen,
                    consoleSession = node.consoleSession,
                    gatewayHostname = node.gatewayHostname,
                    description = node.description
                )
            } else {
                EditState(loading = false, isNew = true)
            }
        }
    }

    fun update(transform: (EditState) -> EditState) { _state.value = transform(_state.value) }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val s = _state.value
        repo.saveConnection(
            existing = existing,
            parentId = existing?.parentId ?: parentId,
            name = s.name.ifBlank { s.hostname.ifBlank { "Новое подключение" } },
            protocol = s.protocol.name,
            hostname = s.hostname.trim(),
            port = s.port.toIntOrNull() ?: s.protocol.defaultPort,
            username = s.username.trim(),
            domain = s.domain.trim(),
            plainPassword = s.password,
            colorDepth = s.colorDepth,
            fullscreen = s.fullscreen,
            consoleSession = s.consoleSession,
            gatewayHostname = s.gatewayHostname.trim(),
            description = s.description.trim()
        )
        onDone()
    }

    class Factory(
        private val repo: ConnectionRepository,
        private val nodeId: String?,
        private val parentId: String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EditViewModel(repo, nodeId, parentId) as T
    }
}
