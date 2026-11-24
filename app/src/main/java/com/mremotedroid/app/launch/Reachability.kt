package com.mremotedroid.app.launch

import com.mremotedroid.app.data.db.NodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Whether connections answer on their port: a plain TCP connect, closed at once. A
 * tunnelled connection is checked at its SSH server, an RD-gateway one at the gateway.
 * Results are kept for a while so scrolling and reopening the list don't recheck.
 */
class Reachability(private val scope: CoroutineScope) {
    enum class Status { CHECKING, UP, DOWN }

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status

    private val checkedAt = HashMap<String, Long>()
    private val limit = Semaphore(PARALLEL)

    /** Checks the connections in [nodes] not checked recently ([force]: all of them). */
    fun check(nodes: List<NodeEntity>, force: Boolean = false) {
        val byId = nodes.associateBy { it.id }
        val now = System.currentTimeMillis()
        nodes.filter { it.nodeType == NodeEntity.TYPE_CONNECTION }.forEach { node ->
            val (host, port) = endpoint(node, byId) ?: return@forEach
            synchronized(checkedAt) {
                val last = checkedAt[node.id]
                if (!force && last != null && now - last < FRESH_MS) return@forEach
                checkedAt[node.id] = now
            }
            if (_status.value[node.id] == null) set(node.id, Status.CHECKING)
            scope.launch(Dispatchers.IO) {
                val up = limit.withPermit { probe(host, port) }
                set(node.id, if (up) Status.UP else Status.DOWN)
            }
        }
    }

    private fun set(id: String, status: Status) {
        synchronized(_status) { _status.value = _status.value + (id to status) }
    }

    private fun endpoint(node: NodeEntity, byId: Map<String, NodeEntity>): Pair<String, Int>? {
        node.sshTunnelId?.let(byId::get)?.let { tunnel ->
            return tunnel.hostname.trim().takeIf { it.isNotEmpty() }?.let { it to portOr(tunnel.port, 22) }
        }
        val gateway = node.gatewayHostname.trim()
        if (node.protocol == "RDP" && gateway.isNotEmpty()) {
            val colon = gateway.lastIndexOf(':')
            val port = if (colon > 0) gateway.substring(colon + 1).toIntOrNull() else null
            return if (port != null) gateway.substring(0, colon) to port else gateway to 443
        }
        val host = node.hostname.trim().takeIf { it.isNotEmpty() } ?: return null
        return host to portOr(node.port, if (node.protocol == "SSH") 22 else if (node.protocol == "VNC") 5900 else 3389)
    }

    private fun portOr(port: Int, default: Int) = if (port in 1..65535) port else default

    private fun probe(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), TIMEOUT_MS) }
    }.isSuccess

    private companion object {
        const val PARALLEL = 16
        const val TIMEOUT_MS = 2500
        const val FRESH_MS = 60_000L
    }
}
