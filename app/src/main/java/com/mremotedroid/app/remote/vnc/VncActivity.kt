package com.mremotedroid.app.remote.vnc

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mremotedroid.app.R
import com.mremotedroid.app.remote.RemoteSessions
import com.mremotedroid.app.remote.SessionUi
import kotlinx.coroutines.launch

/** The window of one VNC session: the remote desktop plus a row of extra keys. */
class VncActivity : ComponentActivity(), VncView.Listener {

    private lateinit var vncView: VncView
    private lateinit var status: TextView
    private var session: VncSession? = null
    private var promptDialog: AlertDialog? = null
    private var ctrl = false
    private var alt = false
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // black window: light system bar icons
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))

        val tag = intent.dataString
        val existing = RemoteSessions.find(tag) as? VncSession
        val s = existing ?: run {
            val (node, login) = RemoteSessions.takePending(intent.getStringExtra(RemoteSessions.EXTRA_PENDING))
                ?: run { finish(); return }
            VncSession(
                context = this,
                nodeId = node.id,
                title = node.name.ifBlank { node.hostname },
                tag = tag!!,
                host = node.hostname,
                port = if (node.port in 1..65535) node.port else 5900,
                username = login.username,
                password = login.password,
                tunnelTarget = login.tunnel
            ).also { RemoteSessions.register(this, it, RemoteSessions.intent(this, VncActivity::class.java, tag)) }
        }
        session = s

        setContentView(buildLayout())
        s.view = vncView
        if (existing == null) s.connect()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() =
                SessionUi.confirmDisconnect(this@VncActivity, s.state.value !is VncSession.State.Ended) { closeSession() }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { s.prompt.collect { promptDialog = SessionUi.showPrompt(this@VncActivity, s, it, promptDialog) } }
                launch {
                    s.state.collect { state ->
                        status.visibility = if (state is VncSession.State.Connected) android.view.View.GONE
                        else android.view.View.VISIBLE
                        status.text = when (state) {
                            VncSession.State.Connecting -> getString(R.string.remote_connecting, s.title)
                            VncSession.State.Connected -> ""
                            is VncSession.State.Ended -> {
                                RemoteSessions.unregister(this@VncActivity, s)
                                state.message
                            }
                        }
                    }
                }
                launch {
                    s.serverClipboard.collect { text ->
                        if (!text.isNullOrEmpty()) {
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("vnc", text))
                            s.serverClipboard.value = null
                        }
                    }
                }
            }
        }
    }

    private fun buildLayout(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val frame = FrameLayout(this)
        vncView = VncView(this).apply {
            listener = this@VncActivity
            isFocusable = true
            isFocusableInTouchMode = true
        }
        frame.addView(vncView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(SessionUi.dp(this@VncActivity, 24), 0, SessionUi.dp(this@VncActivity, 24), 0)
        }
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(SessionUi.keyRow(this) {
            key("⌨") { toggleKeyboard() }
            key("ESC") { onTyped(Keysyms.ESCAPE) }
            key("TAB") { onTyped(Keysyms.TAB) }
            ctrlKey = key("CTRL") { ctrl = !ctrl; SessionUi.setToggled(ctrlKey, ctrl) }
            altKey = key("ALT") { alt = !alt; SessionUi.setToggled(altKey, alt) }
            key("WIN") { onTyped(Keysyms.SUPER_L) }
            key("←") { onTyped(Keysyms.LEFT) }
            key("↑") { onTyped(Keysyms.UP) }
            key("↓") { onTyped(Keysyms.DOWN) }
            key("→") { onTyped(Keysyms.RIGHT) }
            key("DEL") { onTyped(Keysyms.DELETE) }
            key("F1-F12") { showFunctionKeys() }
            key("Ctrl+Alt+Del") { chord(Keysyms.CONTROL_L, Keysyms.ALT_L, Keysyms.DELETE) }
            key("⛶") { vncView.resetZoom() }
            key("📋") { sendClipboard() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SessionUi.dp(this, 44)))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private fun chord(vararg keys: Int) {
        val s = session ?: return
        keys.forEach { s.key(it, true) }
        keys.reversed().forEach { s.key(it, false) }
    }

    private fun showFunctionKeys() {
        AlertDialog.Builder(this)
            .setItems(Array(12) { "F${it + 1}" }) { _, i -> onTyped(Keysyms.F1 + i) }
            .show()
    }

    /** Puts the phone's clipboard on the server's clipboard (paste it there with Ctrl+V). */
    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.sendClipboard(text)
        android.widget.Toast.makeText(this, R.string.vnc_clipboard_sent, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        val visible = ViewCompat.getRootWindowInsets(vncView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) imm.hideSoftInputFromWindow(vncView.windowToken, 0)
        else {
            vncView.requestFocus()
            imm.showSoftInput(vncView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // --- VncView.Listener ---

    override fun onPointer(x: Int, y: Int, buttons: Int) {
        session?.pointer(x, y, buttons)
    }

    override fun onKeysym(keysym: Int, down: Boolean) {
        session?.key(keysym, down)
    }

    /** One key press, wrapped in the sticky CTRL/ALT if they are on. */
    override fun onTyped(keysym: Int) {
        val s = session ?: return
        if (ctrl) s.key(Keysyms.CONTROL_L, true)
        if (alt) s.key(Keysyms.ALT_L, true)
        s.key(keysym, true)
        s.key(keysym, false)
        if (alt) s.key(Keysyms.ALT_L, false)
        if (ctrl) s.key(Keysyms.CONTROL_L, false)
        if (ctrl || alt) {
            ctrl = false
            alt = false
            SessionUi.setToggled(ctrlKey, false)
            SessionUi.setToggled(altKey, false)
        }
    }

    private fun closeSession() {
        session?.let {
            it.disconnect()
            RemoteSessions.unregister(this, it)
        }
        session = null
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        promptDialog?.dismiss()
        session?.view = null
        if (isFinishing) session?.let {
            it.disconnect()
            RemoteSessions.unregister(this, it)
        }
        super.onDestroy()
    }
}
