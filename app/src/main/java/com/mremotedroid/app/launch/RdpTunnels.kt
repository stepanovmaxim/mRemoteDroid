package com.mremotedroid.app.launch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.mremotedroid.app.R
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.remote.RemoteSession
import com.mremotedroid.app.remote.ssh.SshConnect
import com.mremotedroid.app.remote.ssh.SshTunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH tunnels of open RDP sessions. FreeRDP connects to the tunnel's local port; the
 * tunnel is closed once its session is gone (or never appeared).
 */
object RdpTunnels {
    private class Entry(val tunnel: SshTunnel, val created: Long) {
        @Volatile var seen = false
    }

    private val tunnels = ConcurrentHashMap<String, Entry>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listening = false

    fun attach(tag: String, tunnel: SshTunnel) {
        if (!listening) {
            listening = true
            GlobalApp.addSessionListener { check() }
        }
        tunnels.put(tag, Entry(tunnel, System.currentTimeMillis()))?.tunnel?.close()
        main.postDelayed({ check() }, START_TIMEOUT_MS + 1000)
    }

    private fun check() {
        val now = System.currentTimeMillis()
        tunnels.forEach { (tag, entry) ->
            if (GlobalApp.findSessionByTag(tag) != null) {
                entry.seen = true
            } else if (entry.seen || now - entry.created > START_TIMEOUT_MS) {
                if (tunnels.remove(tag, entry)) entry.tunnel.close()
            }
        }
    }

    private const val START_TIMEOUT_MS = 60_000L
}

/**
 * Opens the SSH tunnel of an RDP connection, then starts the session through it. Host key
 * and password questions come through [prompt]; the connection list shows them.
 */
class RdpTunnelStart(
    context: Context,
    private val node: NodeEntity,
    private val login: EmbeddedRdpLauncher.Login
) : RemoteSession(node.id, node.name, ActiveSessions.tagFor(node.id)) {

    sealed interface State {
        data object Connecting : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state
    val tunnelName: String get() = login.tunnel?.title.orEmpty()
    @Volatile private var tunnel: SshTunnel? = null
    @Volatile private var cancelled = false

    fun start() {
        Thread({ run() }, "rdp-tunnel").start()
    }

    private fun run() {
        val target = login.tunnel ?: return
        val t = SshTunnel(appContext, target)
        tunnel = t
        try {
            t.open(ask = { ask(it) })
            if (cancelled) return t.close()
            // with an RD gateway, the gateway is what has to be reached through SSH
            val gateway = node.gatewayHostname.trim()
            val localPort: Int
            val gatewayOverride: String?
            if (gateway.isNotEmpty()) {
                val (host, port) = splitHostPort(gateway, 443)
                localPort = t.forward(host, port)
                gatewayOverride = "127.0.0.1:$localPort"
            } else {
                localPort = t.forward(node.hostname, if (node.port in 1..65535) node.port else 3389)
                gatewayOverride = null
            }
            RdpTunnels.attach(tag, t)
            Handler(Looper.getMainLooper()).post {
                if (cancelled) return@post
                val result = if (gatewayOverride != null) {
                    EmbeddedRdpLauncher.startRdp(appContext, node, login, node.hostname, node.port, gatewayOverride)
                } else {
                    EmbeddedRdpLauncher.startRdp(appContext, node, login, "127.0.0.1", localPort, null)
                }
                _state.value = when (result) {
                    is EmbeddedRdpLauncher.Result.Error -> State.Failed(result.message)
                    else -> State.Done
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tunnel failed", e)
            t.close()
            if (!cancelled) {
                _state.value = State.Failed(
                    appContext.getString(R.string.tunnel_failed, target.title, SshConnect.describe(appContext, e))
                )
            }
        }
    }

    override fun disconnect() {
        cancelled = true
        answer(null)
        if (_state.value !is State.Done) tunnel?.close()
    }

    private companion object {
        const val TAG = "RdpTunnel"

        fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
            val colon = value.lastIndexOf(':')
            val port = if (colon > 0 && !value.endsWith("]")) value.substring(colon + 1).toIntOrNull() else null
            return if (port != null && port in 1..65535) value.substring(0, colon).removeSurrounding("[", "]") to port
            else value.removeSurrounding("[", "]") to defaultPort
        }
    }
}
