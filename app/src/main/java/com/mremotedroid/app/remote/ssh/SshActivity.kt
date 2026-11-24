package com.mremotedroid.app.remote.ssh

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mremotedroid.app.R
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.remote.SessionUi
import com.mremotedroid.app.remote.RemoteSessions
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/** The window of one SSH session: a terminal plus a row of keys phones lack. */
class SshActivity : ComponentActivity() {

    private lateinit var terminalView: TerminalView
    private var session: SshSession? = null
    private var ctrlDown = false
    private var altDown = false
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private var promptDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val tag = intent.dataString
        val existing = RemoteSessions.find(tag) as? SshSession
        val s = existing ?: run {
            val (node, login) = RemoteSessions.takePending(intent.getStringExtra(RemoteSessions.EXTRA_PENDING))
                ?: run { finish(); return }
            createSession(node, login, tag!!)
        }
        session = s
        s.terminal.updateTerminalSessionClient(sessionClient)

        setContentView(buildLayout())
        terminalView.attachSession(s.terminal)
        terminalView.setTextSize(prefs().getInt(PREF_TEXT_SIZE, defaultTextSize()))
        terminalView.requestFocus()
        if (existing == null) s.connect()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDisconnect()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                s.prompt.collect { promptDialog = SessionUi.showPrompt(this@SshActivity, s, it, promptDialog) }
            }
        }
    }

    private fun createSession(node: NodeEntity, login: com.mremotedroid.app.launch.EmbeddedRdpLauncher.Login, tag: String): SshSession {
        val s = SshSession(
            context = this,
            nodeId = node.id,
            title = node.name.ifBlank { node.hostname },
            tag = tag,
            target = SshTarget(
                title = node.name.ifBlank { node.hostname },
                host = node.hostname,
                port = if (node.port in 1..65535) node.port else 22,
                username = login.username,
                password = login.password,
                privateKey = login.privateKey
            ),
            jump = login.tunnel,
            forwards = PortForward.parse(node.portForwards).forwards,
            client = sessionClient
        )
        RemoteSessions.register(this, s, RemoteSessions.intent(this, SshActivity::class.java, tag))
        return s
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(viewClient)
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = false
        }
        root.addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildKeys(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // stay clear of system bars, the cutout and the on-screen keyboard
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

    private fun buildKeys(): View = SessionUi.keyRow(this) {
        fun k(label: String, onClick: () -> Unit) = key(label) { onClick(); terminalView.requestFocus() }
        k("⌨") { toggleKeyboard() }
        k("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        k("TAB") { sendKey(KeyEvent.KEYCODE_TAB) }
        ctrlKey = k("CTRL") { ctrlDown = !ctrlDown; updateModifiers() }
        altKey = k("ALT") { altDown = !altDown; updateModifiers() }
        k("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        k("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        k("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        k("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        k("HOME") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        k("END") { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        k("PGUP") { sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        k("PGDN") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        listOf("|", "/", "-", "~", "_", "\\").forEach { c ->
            k(c) { session?.terminal?.writeCodePoint(false, c.codePointAt(0)) }
        }
        k("F1-F12") { showFunctionKeys() }
    }

    private fun sendKey(keyCode: Int) {
        var meta = 0
        if (ctrlDown) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altDown) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        val now = System.currentTimeMillis()
        terminalView.onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        clearModifiers()
    }

    private fun showFunctionKeys() {
        val labels = Array(12) { "F${it + 1}" }
        AlertDialog.Builder(this)
            .setItems(labels) { _, i -> sendKey(KeyEvent.KEYCODE_F1 + i) }
            .show()
    }

    private fun updateModifiers() {
        SessionUi.setToggled(ctrlKey, ctrlDown)
        SessionUi.setToggled(altKey, altDown)
    }

    private fun clearModifiers() {
        if (ctrlDown || altDown) {
            ctrlDown = false
            altDown = false
            updateModifiers()
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        val visible = ViewCompat.getRootWindowInsets(terminalView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        else {
            terminalView.requestFocus()
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun confirmDisconnect() {
        SessionUi.confirmDisconnect(this, session?.terminal?.isRunning == true) { closeSession() }
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
        // swiped away from Recents or finished: the session goes with its window
        if (isFinishing) session?.let {
            it.disconnect()
            RemoteSessions.unregister(this, it)
        }
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("terminal", Context.MODE_PRIVATE)

    private fun defaultTextSize() = (14 * resources.displayMetrics.scaledDensity).toInt()

    private fun dp(v: Int) = SessionUi.dp(this, v)

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = terminalView.onScreenUpdated()
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) {
            session?.let { RemoteSessions.unregister(this@SshActivity, it) }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(this@SshActivity, R.string.ssh_copied, Toast.LENGTH_SHORT).show()
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(this@SshActivity)?.toString() ?: return
            this@SshActivity.session?.terminal?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val viewClient = object : TerminalViewClient {
        private var scale = 1f

        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                val size = (prefs().getInt(PREF_TEXT_SIZE, defaultTextSize()) * scale).toInt()
                    .coerceIn(dp(6), dp(40))
                prefs().edit().putInt(PREF_TEXT_SIZE, size).apply()
                terminalView.setTextSize(size)
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrlDown
        override fun readAltKey() = altDown
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            // the sticky CTRL/ALT apply to one character, then release
            terminalView.post { clearModifiers() }
            return false
        }

        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private companion object {
        const val PREF_TEXT_SIZE = "text_size"
    }
}
