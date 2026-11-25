package com.mremotedroid.app.remote.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.mremotedroid.app.R
import com.mremotedroid.app.remote.RemoteSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * A file browser over SFTP on its own SSH connection (through the jump host when the
 * connection has one). ChannelSftp is not thread-safe, so every operation runs on one
 * worker thread, in order.
 */
class SftpSession(
    context: Context,
    nodeId: String,
    title: String,
    tag: String,
    private val target: SshTarget,
    private val jump: SshTarget?
) : RemoteSession(nodeId, title, tag) {

    data class Entry(
        val name: String,
        val directory: Boolean,
        val link: Boolean,
        val size: Long,
        val modified: Long,
        val permissions: String
    )

    sealed interface State {
        data class Connecting(val message: String) : State
        data class Ready(val path: String, val entries: List<Entry>) : State
        data class Ended(val message: String) : State
    }

    /** A running upload or download; [total] is -1 when unknown. */
    data class Transfer(val name: String, val upload: Boolean, val done: Long, val total: Long)

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var session: Session? = null
    @Volatile private var tunnel: SshTunnel? = null
    @Volatile private var sftp: ChannelSftp? = null
    @Volatile private var closed = false
    @Volatile private var cancelTransfer = false

    private val _state = MutableStateFlow<State>(State.Connecting(appContext.getString(R.string.remote_connecting, "${target.host}:${target.port}")))
    val state: StateFlow<State> = _state
    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer
    /** One-shot messages: errors of single operations, finished transfers. */
    val message = MutableStateFlow<String?>(null)

    private var path = "."

    fun connect() = worker.execute {
        try {
            val s = if (jump != null) {
                _state.value = State.Connecting(appContext.getString(R.string.tunnel_connecting, jump.title))
                val t = SshTunnel(appContext, jump).also { tunnel = it }
                try {
                    t.open(ask = { ask(it) })
                } catch (e: Exception) {
                    throw TunnelException(e)
                }
                SshConnect.open(appContext, target, { ask(it) }, connectHost = "127.0.0.1", connectPort = t.forward(target.host, target.port))
            } else {
                SshConnect.open(appContext, target, { ask(it) })
            }
            session = s
            if (closed) return@execute
            val channel = s.openChannel("sftp") as ChannelSftp
            channel.connect(SshConnect.CONNECT_TIMEOUT_MS)
            sftp = channel
            path = runCatching { channel.home }.getOrNull() ?: channel.pwd()
            list()
        } catch (e: Exception) {
            Log.w(TAG, "sftp connect failed", e)
            end(
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

    /** Lists [path]: folders first, then files, by name; "." and ".." left out. */
    private fun list() {
        val channel = sftp ?: return
        @Suppress("UNCHECKED_CAST")
        val raw = channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
        val entries = raw.filter { it.filename != "." && it.filename != ".." }.map { e ->
            val a = e.attrs
            Entry(
                name = e.filename,
                directory = a.isDir || (a.isLink && isDirectoryLink(channel, e.filename)),
                link = a.isLink,
                size = a.size,
                modified = a.mTime * 1000L,
                permissions = a.permissionsString
            )
        }.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
        _state.value = State.Ready(path, entries)
    }

    private fun isDirectoryLink(channel: ChannelSftp, name: String): Boolean =
        runCatching { channel.stat(child(name)).isDir }.getOrDefault(false)

    private fun child(name: String) = if (path.endsWith("/")) path + name else "$path/$name"

    /** Runs [block] on the worker, reporting a failure as a message and refreshing after. */
    private fun op(refresh: Boolean = true, block: (ChannelSftp) -> Unit) = worker.execute {
        val channel = sftp ?: return@execute
        try {
            block(channel)
        } catch (e: Exception) {
            Log.w(TAG, "sftp operation failed", e)
            message.value = describe(e)
            if (!channel.isConnected) return@execute end(appContext.getString(R.string.remote_closed))
        }
        if (refresh) runCatching { list() }.onFailure { message.value = describe(it as Exception) }
    }

    fun open(name: String) = op(refresh = false) { channel ->
        val next = channel.realpath(child(name))
        val previous = path
        path = next
        try {
            list()
        } catch (e: Exception) {
            path = previous
            throw e
        }
    }

    fun up() = op(refresh = false) { channel ->
        if (path == "/") return@op
        path = channel.realpath(child(".."))
        list()
    }

    fun goTo(newPath: String) = op(refresh = false) { channel ->
        val previous = path
        path = channel.realpath(newPath.ifBlank { "/" })
        try {
            list()
        } catch (e: Exception) {
            path = previous
            throw e
        }
    }

    fun refresh() = op { }

    fun mkdir(name: String) = op { it.mkdir(child(name)) }

    fun rename(from: String, to: String) = op { it.rename(child(from), child(to)) }

    /** Deletes a file, or an empty folder. */
    fun delete(entry: Entry) = op {
        if (entry.directory && !entry.link) {
            try {
                it.rmdir(child(entry.name))
            } catch (e: SftpException) {
                // servers answer a plain "Failure" for a folder that still has files in it
                if (e.id == ChannelSftp.SSH_FX_FAILURE) throw SftpException(e.id, appContext.getString(R.string.sftp_folder_not_empty))
                throw e
            }
        } else {
            it.rm(child(entry.name))
        }
    }

    fun exists(name: String): Boolean =
        (state.value as? State.Ready)?.entries?.any { it.name == name } == true

    /** Copies the remote file [name] into [output] (closed afterwards). */
    fun download(entry: Entry, output: () -> OutputStream?) = op(refresh = false) { channel ->
        val out = output() ?: return@op
        out.use { channel.get(child(entry.name), it, monitor(entry.name, upload = false, total = entry.size)) }
        finishTransfer(entry.name)
    }

    /** Uploads [input] (closed afterwards) as [name] into the current folder. */
    fun upload(name: String, size: Long, input: () -> InputStream?) = op { channel ->
        val stream = input() ?: return@op
        stream.use { channel.put(it, child(name), monitor(name, upload = true, total = size), ChannelSftp.OVERWRITE) }
        finishTransfer(name)
    }

    fun cancelTransfer() {
        cancelTransfer = true
    }

    private fun finishTransfer(name: String) {
        val cancelled = cancelTransfer
        _transfer.value = null
        cancelTransfer = false
        message.value = appContext.getString(if (cancelled) R.string.sftp_cancelled else R.string.sftp_done, name)
    }

    private fun monitor(name: String, upload: Boolean, total: Long) = object : SftpProgressMonitor {
        private var done = 0L
        private var shown = 0L

        override fun init(op: Int, src: String?, dest: String?, max: Long) {
            cancelTransfer = false
            _transfer.value = Transfer(name, upload, 0, if (total > 0) total else max)
        }

        override fun count(count: Long): Boolean {
            done += count
            if (done - shown > 64 * 1024) {
                shown = done
                _transfer.value = _transfer.value?.copy(done = done)
            }
            return !cancelTransfer && !closed
        }

        override fun end() {
            _transfer.value = _transfer.value?.copy(done = done)
        }
    }

    private fun describe(e: Exception): String = when {
        e is SftpException && e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> appContext.getString(R.string.sftp_denied)
        e is SftpException && e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> appContext.getString(R.string.sftp_not_found)
        e is SftpException && e.id == ChannelSftp.SSH_FX_FAILURE ->
            e.message?.takeIf { it != "Failure" && !it.startsWith("SSH_FX") } ?: appContext.getString(R.string.sftp_failed, "Failure")
        else -> appContext.getString(R.string.sftp_failed, e.message ?: e.javaClass.simpleName)
    }

    private fun end(message: String) {
        closeConnections()
        _transfer.value = null
        _state.value = State.Ended(message)
    }

    private fun closeConnections() {
        runCatching { sftp?.disconnect() }
        runCatching { session?.disconnect() }
        runCatching { tunnel?.close() }
    }

    override fun disconnect() {
        closed = true
        cancelTransfer = true
        answer(null)
        Thread { closeConnections() }.start()
        worker.shutdown()
    }

    private class TunnelException(cause: Exception) : Exception(cause)

    private companion object {
        const val TAG = "SftpSession"
    }
}
