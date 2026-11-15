package com.mremotedroid.app.remote.vnc

import android.content.Context
import android.util.Log
import com.mremotedroid.app.R
import com.mremotedroid.app.remote.Prompt
import com.mremotedroid.app.remote.RemoteSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A VNC session; the window attaches its [VncView] to receive the framebuffer. */
class VncSession(
    context: Context,
    nodeId: String,
    title: String,
    tag: String,
    host: String,
    port: Int,
    username: String,
    password: String?
) : RemoteSession(nodeId, title, tag) {

    sealed interface State {
        data object Connecting : State
        data object Connected : State
        data class Ended(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state

    /** Text the server put on its clipboard, for the window to copy. */
    val serverClipboard = MutableStateFlow<String?>(null)

    @Volatile var view: VncView? = null
        set(value) {
            field = value
            // a recreated window gets the whole desktop again
            val c = client
            if (value != null && c.width > 0) {
                value.post {
                    value.setFramebuffer(c.width, c.height)
                    value.update(c.pixels, c.width, 0, 0, c.width, c.height)
                }
            }
        }

    private val client: RfbClient = RfbClient(host, port, username, password, object : RfbClient.Listener {
        override fun onResize(width: Int, height: Int, pixels: IntArray, name: String) {
            view?.let { v ->
                // create the bitmap before any update for the new size is drawn
                val done = java.util.concurrent.CountDownLatch(1)
                v.post {
                    v.setFramebuffer(width, height)
                    done.countDown()
                }
                done.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            _state.value = State.Connected
        }

        override fun onUpdate(x: Int, y: Int, width: Int, height: Int) {
            view?.update(client.pixels, client.width, x, y, width, height)
        }

        override fun onClipboard(text: String) {
            serverClipboard.value = text
        }

        override fun requestPassword(): String? = ask(Prompt.Secret(null)) as? String
    })

    @Volatile private var closed = false

    fun connect() {
        Thread({
            try {
                client.run()
                end(appContext.getString(R.string.remote_closed))
            } catch (e: Exception) {
                Log.w(TAG, "vnc session ended", e)
                end(if (closed) appContext.getString(R.string.remote_closed) else describe(e))
            }
        }, "vnc").start()
    }

    private fun describe(e: Exception): String = when (e) {
        is RfbClient.AuthException -> appContext.getString(R.string.remote_auth_failed) +
            (e.message?.takeIf { it.isNotBlank() && it != "cancelled" }?.let { " ($it)" } ?: "")
        is RfbClient.UnsupportedSecurityException -> appContext.getString(R.string.vnc_unsupported_security, e.types.joinToString())
        is EOFException -> appContext.getString(R.string.remote_closed)
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            appContext.getString(R.string.remote_failed, e.message ?: e.javaClass.simpleName)
        else -> appContext.getString(R.string.remote_failed, e.message ?: e.javaClass.simpleName)
    }

    private fun end(message: String) {
        client.close()
        _state.value = State.Ended(message)
    }

    fun pointer(x: Int, y: Int, buttons: Int) = client.pointer(x, y, buttons)
    fun key(keysym: Int, down: Boolean) = client.key(keysym, down)
    fun sendClipboard(text: String) = client.clipboard(text)

    override fun disconnect() {
        closed = true
        answer(null)
        client.close()
    }

    private companion object {
        const val TAG = "VncSession"
    }
}
