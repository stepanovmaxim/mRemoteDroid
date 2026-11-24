package com.mremotedroid.app.remote.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.mremotedroid.app.R
import com.mremotedroid.app.remote.Prompt
import com.mremotedroid.app.remote.RemoteSession
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An SSH shell session: JSch for the connection, a Termux [TerminalSession] for the
 * terminal. Host keys are checked against [HostKeyStore]; passwords and key passphrases
 * the server still needs are asked through [Prompt]s. The server can be reached through
 * a jump host ([jump]) and the session can carry [forwards].
 */
class SshSession(
    context: Context,
    nodeId: String,
    title: String,
    tag: String,
    private val target: SshTarget,
    private val jump: SshTarget?,
    private val forwards: List<PortForward>,
    client: TerminalSessionClient
) : RemoteSession(nodeId, title, tag) {

    private val appContext = context.applicationContext
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var tunnel: SshTunnel? = null
    @Volatile private var forwarder: Forwarder? = null
    @Volatile private var closed = false

    @Volatile private var columns = 80
    @Volatile private var rows = 24
    private val sizeKnown = CountDownLatch(1)

    val terminal = TerminalSession(object : TerminalSession.Transport {
        override fun send(data: ByteArray, offset: Int, count: Int) {
            output?.apply {
                write(data, offset, count)
                flush()
            }
        }

        override fun resize(columns: Int, rows: Int) {
            this@SshSession.columns = columns
            this@SshSession.rows = rows
            sizeKnown.countDown()
            channel?.setPtySize(columns, rows, 0, 0)
        }
    }, TRANSCRIPT_ROWS, client)

    private fun status(text: String) {
        val bytes = "$text\r\n".toByteArray()
        terminal.feed(bytes, 0, bytes.size)
    }

    fun connect() {
        Thread({ run() }, "ssh-${target.host}").start()
    }

    private fun run() {
        try {
            val s = if (jump != null) {
                status(appContext.getString(R.string.tunnel_connecting, jump.title))
                val t = SshTunnel(appContext, jump)
                tunnel = t
                try {
                    t.open(ask = { ask(it) }, status = ::status)
                } catch (e: Exception) {
                    throw TunnelException(e)
                }
                if (closed) return
                val localPort = t.forward(target.host, target.port)
                status(appContext.getString(R.string.remote_connecting, "${target.host}:${target.port}"))
                SshConnect.open(appContext, target, { ask(it) }, ::status, "127.0.0.1", localPort)
            } else {
                status(appContext.getString(R.string.remote_connecting, "${target.host}:${target.port}"))
                SshConnect.open(appContext, target, { ask(it) }, ::status)
            }
            session = s
            if (closed) return
            startForwards(s)

            // the first layout gives the real size; don't open an 80x24 shell needlessly
            sizeKnown.await(2, TimeUnit.SECONDS)
            val shell = s.openChannel("shell") as ChannelShell
            shell.setPtyType("xterm-256color", columns, rows, 0, 0)
            val input = shell.inputStream
            output = shell.outputStream
            shell.connect(SshConnect.CONNECT_TIMEOUT_MS)
            channel = shell

            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                terminal.feed(buffer, 0, n)
            }
            finish(appContext.getString(R.string.remote_closed))
        } catch (e: Exception) {
            Log.w(TAG, "ssh session ended", e)
            finish(
                when {
                    closed -> appContext.getString(R.string.remote_closed)
                    e is TunnelException -> appContext.getString(
                        R.string.tunnel_failed, jump?.title.orEmpty(), SshConnect.describe(appContext, e.cause as Exception)
                    )
                    else -> SshConnect.describe(appContext, e)
                }
            )
        }
    }

    /** Starts the port forwards; one that fails is reported and the others still work. */
    private fun startForwards(s: Session) {
        if (forwards.isEmpty()) return
        val f = Forwarder(target.host) { s }
        forwarder = f
        forwards.forEach { forward ->
            try {
                when (forward) {
                    is PortForward.Local -> {
                        f.local(forward.localPort, forward.host, forward.port)
                        status(appContext.getString(R.string.forward_local, forward.localPort, forward.host, forward.port))
                    }
                    is PortForward.Dynamic -> {
                        f.socks(forward.localPort)
                        status(appContext.getString(R.string.forward_socks, forward.localPort))
                    }
                    is PortForward.Remote -> {
                        s.setPortForwardingR(forward.remotePort, forward.host, forward.port)
                        status(appContext.getString(R.string.forward_remote, forward.remotePort, forward.host, forward.port))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "forward failed: $forward", e)
                status(appContext.getString(R.string.forward_failed, describe(forward), e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun describe(forward: PortForward): String = when (forward) {
        is PortForward.Local -> "L ${forward.localPort}:${forward.host}:${forward.port}"
        is PortForward.Remote -> "R ${forward.remotePort}:${forward.host}:${forward.port}"
        is PortForward.Dynamic -> "D ${forward.localPort}"
    }

    private fun closeConnections() {
        runCatching { forwarder?.close() }
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        runCatching { tunnel?.close() }
    }

    private fun finish(message: String) {
        closeConnections()
        terminal.onRemoteClosed(message)
    }

    override fun disconnect() {
        closed = true
        answer(null)
        Thread { closeConnections() }.start()
        terminal.finishIfRunning()
    }

    private class TunnelException(cause: Exception) : Exception(cause)

    private companion object {
        const val TAG = "SshSession"
        const val TRANSCRIPT_ROWS = 5000
    }
}
