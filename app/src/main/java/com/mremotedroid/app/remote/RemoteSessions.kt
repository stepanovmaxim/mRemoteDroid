package com.mremotedroid.app.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.freerdp.freerdpcore.services.SessionKeepAliveService
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.launch.EmbeddedRdpLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A question the connection thread needs the user to answer (host key, password). */
sealed interface Prompt {
    /** Unknown or changed server key: [fingerprint] is "SHA256:…". */
    data class HostKey(val host: String, val type: String, val fingerprint: String, val changed: Boolean) : Prompt

    /** A secret the server asks for; [label] is the server's own wording, if any. */
    data class Secret(val label: String?) : Prompt
}

/**
 * An SSH or VNC session. It outlives its window (the window can be recreated) and is
 * closed when the window is closed for good.
 */
abstract class RemoteSession(val nodeId: String, val title: String, val tag: String) {
    /** Negative ids keep these sessions apart from FreeRDP's instances in the keep-alive service. */
    val instanceId: Long = -NEXT_ID.incrementAndGet()

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt
    @Volatile private var answer: Any? = null
    @Volatile private var latch: CountDownLatch? = null

    /** Blocks the calling (connection) thread until the window answers; null = cancelled. */
    protected fun ask(prompt: Prompt): Any? {
        val l = CountDownLatch(1)
        latch = l
        answer = null
        _prompt.value = prompt
        val answered = l.await(PROMPT_TIMEOUT_MIN, TimeUnit.MINUTES)
        _prompt.value = null
        return if (answered) answer else null
    }

    /** Called by the window: true/false for host keys, a String (or null) for secrets. */
    fun answer(value: Any?) {
        answer = value
        latch?.countDown()
    }

    abstract fun disconnect()

    companion object {
        private val NEXT_ID = AtomicLong(0)
        private const val PROMPT_TIMEOUT_MIN = 5L
    }
}

/** Registry of open SSH/VNC sessions, the counterpart of FreeRDP's session map. */
object RemoteSessions {
    private val sessions = ConcurrentHashMap<String, RemoteSession>()
    private val pending = ConcurrentHashMap<String, Pair<NodeEntity, EmbeddedRdpLauncher.Login>>()
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) = synchronized(listeners) { listeners += listener }

    private fun notifyChanged() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun find(tag: String?): RemoteSession? = tag?.let(sessions::get)

    fun all(): Collection<RemoteSession> = sessions.values

    fun register(context: Context, session: RemoteSession, reopen: Intent) {
        sessions[session.tag] = session
        SessionKeepAliveService.start(context, session.instanceId, session.title, session.tag, reopen)
        notifyChanged()
    }

    fun unregister(context: Context, session: RemoteSession) {
        if (sessions.remove(session.tag, session)) {
            SessionKeepAliveService.stop(context, session.instanceId)
            notifyChanged()
        }
    }

    /** Hands the decrypted login to the session window in memory, never in the intent. */
    fun putPending(node: NodeEntity, login: EmbeddedRdpLauncher.Login): String =
        UUID.randomUUID().toString().also { pending[it] = node to login }

    fun takePending(token: String?): Pair<NodeEntity, EmbeddedRdpLauncher.Login>? = token?.let(pending::remove)

    fun intent(context: Context, activity: Class<*>, tag: String): Intent =
        Intent(context, activity)
            .setData(Uri.parse(tag))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)

    const val EXTRA_PENDING = "pending"
}
