package com.mremotedroid.app.launch

import com.mremotedroid.app.R
import android.content.Context
import android.net.Uri
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionIntents
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import com.mremotedroid.app.remote.RemoteSessions
import com.mremotedroid.app.remote.ssh.SshActivity
import com.mremotedroid.app.remote.vnc.VncActivity
import java.net.URLEncoder

/**
 * Launches a session inside the app: RDP with the embedded FreeRDP engine (the bundled
 * freeRDPCore module), SSH and VNC with their own windows.
 *
 * It builds a `freerdp://user@host:port/connect?...` URI that
 * [com.freerdp.freerdpcore.services.LibFreeRDP.setConnectionInfo] turns into
 * FreeRDP command-line arguments, and starts SessionActivity with it.
 */
object EmbeddedRdpLauncher {

    sealed interface Result {
        data object Ok : Result
        data class Error(val message: String) : Result
    }

    /** Brings the already open session for [node] to the front; false if none is open. */
    fun reopen(context: Context, node: NodeEntity): Boolean {
        if (!ActiveSessions.isOpen(node.id)) return false
        val tag = ActiveSessions.tagFor(node.id)
        val window = RemoteSessions.find(tag)?.let { activityFor(node) }
        context.startActivity(
            if (window != null) RemoteSessions.intent(context, window, tag) else SessionIntents.reopen(context, tag)
        )
        return true
    }

    /** The login to use, already decrypted. */
    class Login(
        val username: String,
        val domain: String,
        val password: String?,
        val gatewayPassword: String?,
        /** OpenSSH/PEM private key for SSH; the password then unlocks it. */
        val privateKey: String? = null
    )

    private fun activityFor(node: NodeEntity): Class<*>? = when (node.protocol) {
        Protocol.SSH.name -> SshActivity::class.java
        Protocol.VNC.name -> VncActivity::class.java
        else -> null
    }

    fun launch(context: Context, node: NodeEntity, login: Login): Result {
        if (reopen(context, node)) return Result.Ok
        if (ActiveSessions.count() >= GlobalApp.MAX_SESSIONS) {
            return Result.Error(context.getString(R.string.msg_max_sessions, GlobalApp.MAX_SESSIONS))
        }
        activityFor(node)?.let { window ->
            if (node.hostname.isBlank()) return Result.Error(context.getString(R.string.msg_launch_failed))
            val tag = ActiveSessions.tagFor(node.id)
            context.startActivity(
                RemoteSessions.intent(context, window, tag)
                    .putExtra(RemoteSessions.EXTRA_PENDING, RemoteSessions.putPending(node, login))
            )
            return Result.Ok
        }
        return try {
            // No explicit size: SessionActivity picks a landscape resolution matching
            // the screen and fits it to the current orientation.
            val port = if (node.port in 1..65535) node.port else 3389
            val authority = buildString {
                if (login.username.isNotBlank()) append(enc(login.username)).append("@")
                append(node.hostname)
                append(":").append(port)
            }

            val builder = Uri.Builder()
                .scheme("freerdp")
                .encodedAuthority(authority)
                .path("connect")
                .appendQueryParameter("cert", "ignore")
                .appendQueryParameter("clipboard", "")           // -> /clipboard

            if (login.domain.isNotBlank()) builder.appendQueryParameter("d", login.domain)
            if (!login.password.isNullOrEmpty()) builder.appendQueryParameter("p", login.password)
            if (node.consoleSession) builder.appendQueryParameter("admin", "") // -> /admin
            if (node.gatewayHostname.isNotBlank()) {
                builder.appendQueryParameter("gateway", gatewayOptions(node, login.gatewayPassword))
            }
            when (node.soundMode) {
                NodeEntity.SOUND_DEVICE -> builder.appendQueryParameter("sound", "")
                NodeEntity.SOUND_SERVER -> builder.appendQueryParameter("audio-mode", "1")
            }
            if (node.redirectFolder) {
                SharedFiles.directory(context)?.let {
                    builder.appendQueryParameter("drive", "${SharedFiles.DRIVE_NAME},${it.path}")
                }
            }

            // Each session gets its own window (task / Recents card). The connection URI
            // carries the password, so it is handed over in memory, not in the intent.
            context.startActivity(
                SessionIntents.connect(context, ActiveSessions.tagFor(node.id), builder.build())
            )
            Result.Ok
        } catch (e: Exception) {
            Result.Error(context.getString(R.string.msg_launch_failed))
        }
    }

    /** Value of FreeRDP's /gateway option; no login means the connection's own is reused. */
    private fun gatewayOptions(node: NodeEntity, password: String?): String {
        val options = mutableListOf(quoted("g:", node.gatewayHostname.trim()))
        if (node.gatewayUsername.isNotBlank()) {
            options += quoted("u:", node.gatewayUsername)
            if (node.gatewayDomain.isNotBlank()) options += quoted("d:", node.gatewayDomain)
            if (!password.isNullOrEmpty()) options += quoted("p:", password)
        }
        options += "usage-method:direct"
        return options.filterNotNull().joinToString(",")
    }

    /**
     * Quotes one comma-separated element the way FreeRDP's parser expects. A value holding
     * both quote characters cannot be written and is left out: FreeRDP then asks for it.
     */
    private fun quoted(prefix: String, value: String): String? {
        val element = prefix + value
        return when {
            element.none { it == ',' || it == '"' || it == '\'' } -> element
            '"' !in element -> "\"$element\""
            '\'' !in element -> "'$element'"
            else -> null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
