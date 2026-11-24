package com.mremotedroid.app.remote.ssh

import android.content.Context
import com.jcraft.jsch.Session
import com.mremotedroid.app.remote.Prompt
import java.io.Closeable
import java.io.IOException

/**
 * An SSH connection that only carries other connections (an RDP or VNC session, or the
 * SSH connection of a server behind a jump host). After the first, interactive [open]
 * it reconnects on its own when it dropped, without asking: the host key is trusted by
 * then and the stored login is used.
 */
class SshTunnel(context: Context, private val target: SshTarget) : Closeable {
    private val appContext = context.applicationContext
    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    private val forwarder = Forwarder(target.host) { session() }

    val title: String get() = target.title

    /** Connects for the first time; [ask] answers host key and password questions. */
    fun open(ask: (Prompt) -> Any?, status: (String) -> Unit = {}) {
        val s = SshConnect.open(appContext, target, ask, status)
        synchronized(this) {
            session = s
            if (closed) Thread { s.disconnect() }.start()
        }
    }

    @Synchronized
    private fun session(): Session {
        if (closed) throw IOException("tunnel closed")
        session?.takeIf { it.isConnected }?.let { return it }
        runCatching { session?.disconnect() }
        return SshConnect.open(appContext, target, ask = { null }).also { session = it }
    }

    /** A local port leading to [host]:[port] as seen from the SSH server. */
    fun forward(host: String, port: Int): Int = forwarder.local(0, host, port)

    override fun close() {
        closed = true
        forwarder.close()
        val s = session
        Thread { runCatching { s?.disconnect() } }.start()
    }
}
