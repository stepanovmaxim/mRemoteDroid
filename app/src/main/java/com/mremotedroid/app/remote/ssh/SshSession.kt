package com.mremotedroid.app.remote.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
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
 * the server still needs are asked through [Prompt]s.
 */
class SshSession(
    context: Context,
    nodeId: String,
    title: String,
    tag: String,
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val privateKey: String?,
    client: TerminalSessionClient
) : RemoteSession(nodeId, title, tag) {

    private val appContext = context.applicationContext
    private val hostKeys = HostKeyStore(appContext)
    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var output: OutputStream? = null
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
        Thread({ run() }, "ssh-$host").start()
    }

    private fun run() {
        try {
            status(appContext.getString(R.string.remote_connecting, "$host:$port"))
            val jsch = JSch()
            useBouncyCastle()
            jsch.hostKeyRepository = hostKeys
            if (!privateKey.isNullOrBlank()) {
                jsch.addIdentity(title, privateKey.toByteArray(), null, password?.toByteArray())
            }
            val s = jsch.getSession(username, host, port)
            session = s
            password?.takeIf { it.isNotEmpty() }?.let { s.setPassword(it) }
            s.userInfo = Prompts()
            s.setConfig("StrictHostKeyChecking", "ask")
            s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            s.setServerAliveInterval(KEEP_ALIVE_MS)
            s.connect(CONNECT_TIMEOUT_MS)
            if (closed) return

            // the first layout gives the real size; don't open an 80x24 shell needlessly
            sizeKnown.await(2, TimeUnit.SECONDS)
            val shell = s.openChannel("shell") as ChannelShell
            shell.setPtyType("xterm-256color", columns, rows, 0, 0)
            val input = shell.inputStream
            output = shell.outputStream
            shell.connect(CONNECT_TIMEOUT_MS)
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
            finish(if (closed) appContext.getString(R.string.remote_closed) else describe(e))
        }
    }

    private fun describe(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", true) || message.contains("Auth cancel", true) ->
                appContext.getString(R.string.remote_auth_failed)
            message.contains("reject HostKey", true) || message.contains("HostKey has been changed", true) ->
                appContext.getString(R.string.ssh_hostkey_rejected)
            message.contains("invalid privatekey", true) ->
                appContext.getString(R.string.ssh_bad_key)
            else -> appContext.getString(R.string.remote_failed, message.ifBlank { e.javaClass.simpleName })
        }
    }

    private fun finish(message: String) {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        terminal.onRemoteClosed(message)
    }

    override fun disconnect() {
        closed = true
        answer(null)
        Thread {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
        }.start()
        terminal.finishIfRunning()
    }

    /** JSch's callbacks, answered by the session window. */
    private inner class Prompts : UserInfo, UIKeyboardInteractive {
        private var secret: String? = null

        override fun getPassphrase(): String? = secret
        override fun getPassword(): String? = secret

        override fun promptPassword(message: String?): Boolean = askSecret(message)

        override fun promptPassphrase(message: String?): Boolean = askSecret(message)

        private fun askSecret(message: String?): Boolean {
            secret = ask(Prompt.Secret(message)) as? String
            return secret != null
        }

        override fun promptYesNo(message: String?): Boolean {
            val check = hostKeys.lastCheck ?: return false
            return ask(Prompt.HostKey(check.host, check.type, check.fingerprint, check.changed)) == true
        }

        override fun showMessage(message: String?) {
            if (!message.isNullOrBlank()) status(message)
        }

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>,
            echo: BooleanArray
        ): Array<String>? {
            if (!instruction.isNullOrBlank()) status(instruction)
            // a single hidden prompt is the password: use the stored one first
            val stored = password
            if (prompt.size == 1 && !echo[0] && !stored.isNullOrEmpty() && !usedStoredPassword) {
                usedStoredPassword = true
                return arrayOf(stored)
            }
            val answers = ArrayList<String>(prompt.size)
            for (p in prompt) answers += (ask(Prompt.Secret(p.trim())) as? String) ?: return null
            return answers.toTypedArray()
        }

        private var usedStoredPassword = false
    }

    companion object {
        private const val TAG = "SshSession"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val KEEP_ALIVE_MS = 30_000
        private const val TRANSCRIPT_ROWS = 5000

        /**
         * Android has no JCA providers for X25519 and Ed25519 before recent versions, and
         * D8 drops the jar's Java 11+ classes: use JSch's Bouncy Castle implementations.
         */
        private fun useBouncyCastle() {
            listOf(
                "xdh", "keypairgen.eddsa", "keypairgen_fromprivate.eddsa", "ssh-ed25519", "ssh-ed448",
                "mlkem768", "mlkem1024"
            ).forEach { key ->
                val current = JSch.getConfig(key) ?: return@forEach
                val bc = current.replace(".jce.", ".bc.")
                if (bc != current && runCatching { Class.forName(bc) }.isSuccess) JSch.setConfig(key, bc)
            }
        }
    }
}
