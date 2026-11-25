package com.mremotedroid.app.remote.ssh

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.mremotedroid.app.R
import com.mremotedroid.app.remote.Prompt

/** An SSH server and the login for it, already decrypted. */
class SshTarget(
    val title: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    /** OpenSSH/PEM private key; [password] then unlocks it. */
    val privateKey: String?
)

/** Opens authenticated JSch sessions; shared by SSH terminals and SSH tunnels. */
object SshConnect {
    const val CONNECT_TIMEOUT_MS = 20_000
    private const val KEEP_ALIVE_MS = 30_000

    /**
     * Connects and authenticates. Host keys are checked against [HostKeyStore]; [ask]
     * answers host key and password questions (returning null cancels them) and [status]
     * shows server messages. To reach the server through a tunnel, pass the local end as
     * [connectHost]/[connectPort]: host keys are still checked under the real name.
     */
    fun open(
        context: Context,
        target: SshTarget,
        ask: (Prompt) -> Any?,
        status: (String) -> Unit = {},
        connectHost: String = target.host,
        connectPort: Int = target.port
    ): Session {
        val hostKeys = HostKeyStore(context.applicationContext)
        val jsch = JSch()
        useBouncyCastle()
        jsch.hostKeyRepository = hostKeys
        if (!target.privateKey.isNullOrBlank()) {
            jsch.addIdentity(target.title, target.privateKey.toByteArray(), null, target.password?.toByteArray())
        }
        val s = jsch.getSession(target.username, connectHost, connectPort)
        if (connectHost != target.host || connectPort != target.port) {
            // the name JSch would use for a direct connection
            s.setHostKeyAlias(if (target.port == 22) target.host else "[${target.host}]:${target.port}")
        }
        target.password?.takeIf { it.isNotEmpty() }?.let { s.setPassword(it) }
        // JSch names the address it connects to; through a tunnel, show the real server instead
        val local = "$connectHost:$connectPort"
        val real = "${target.host}:${target.port}"
        val label: (String?) -> String? = { it?.replace(local, real) }
        s.userInfo = Prompts(hostKeys, target.password, { p ->
            ask(if (p is Prompt.Secret) Prompt.Secret(label(p.label)) else p)
        }, status)
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.setServerAliveInterval(KEEP_ALIVE_MS)
        s.setServerAliveCountMax(3)
        s.connect(CONNECT_TIMEOUT_MS)
        return s
    }

    /** A user-facing reason for a failed connection. */
    fun describe(context: Context, e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Auth fail", true) || message.contains("Auth cancel", true) ->
                context.getString(R.string.remote_auth_failed)
            message.contains("reject HostKey", true) || message.contains("HostKey has been changed", true) ->
                context.getString(R.string.ssh_hostkey_rejected)
            message.contains("invalid privatekey", true) ->
                context.getString(R.string.ssh_bad_key)
            else -> context.getString(R.string.remote_failed, message.ifBlank { e.javaClass.simpleName })
        }
    }

    /** JSch's callbacks, answered through [ask]. */
    private class Prompts(
        private val hostKeys: HostKeyStore,
        private val password: String?,
        private val ask: (Prompt) -> Any?,
        private val status: (String) -> Unit
    ) : UserInfo, UIKeyboardInteractive {
        private var secret: String? = null
        private var usedStoredPassword = false

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
    }

    /**
     * Android has no JCA providers for X25519 and Ed25519 before recent versions, and
     * D8 drops the jar's Java 11+ classes: use JSch's Bouncy Castle implementations.
     */
    fun useBouncyCastle() {
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
