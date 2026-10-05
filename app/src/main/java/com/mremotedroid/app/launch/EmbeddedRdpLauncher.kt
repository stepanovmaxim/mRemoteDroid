package com.mremotedroid.app.launch

import android.content.Context
import android.net.Uri
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.presentation.SessionIntents
import com.mremotedroid.app.data.db.NodeEntity
import java.net.URLEncoder

/**
 * Launches an RDP session inside the app using the embedded FreeRDP engine
 * (the bundled freeRDPCore module), rather than handing off to an external client.
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
        context.startActivity(SessionIntents.reopen(context, ActiveSessions.tagFor(node.id)))
        return true
    }

    fun launch(context: Context, node: NodeEntity, plainPassword: String?): Result {
        if (reopen(context, node)) return Result.Ok
        if (ActiveSessions.count() >= GlobalApp.MAX_SESSIONS) {
            return Result.Error("Открыто максимум ${GlobalApp.MAX_SESSIONS} сеансов. Закройте один из них.")
        }
        return try {
            // No explicit size: SessionActivity picks a landscape resolution matching
            // the screen and fits it to the current orientation.
            val port = if (node.port in 1..65535) node.port else 3389
            val authority = buildString {
                if (node.username.isNotBlank()) append(enc(node.username)).append("@")
                append(node.hostname)
                append(":").append(port)
            }

            val builder = Uri.Builder()
                .scheme("freerdp")
                .encodedAuthority(authority)
                .path("connect")
                .appendQueryParameter("cert", "ignore")
                .appendQueryParameter("clipboard", "")           // -> /clipboard

            if (node.domain.isNotBlank()) builder.appendQueryParameter("d", node.domain)
            if (!plainPassword.isNullOrEmpty()) builder.appendQueryParameter("p", plainPassword)
            if (node.consoleSession) builder.appendQueryParameter("admin", "") // -> /admin
            if (node.gatewayHostname.isNotBlank()) {
                builder.appendQueryParameter("g", node.gatewayHostname)
            }

            // Each session gets its own window (task / Recents card). The connection URI
            // carries the password, so it is handed over in memory, not in the intent.
            context.startActivity(
                SessionIntents.connect(context, ActiveSessions.tagFor(node.id), builder.build())
            )
            Result.Ok
        } catch (e: Exception) {
            Result.Error(e.message ?: "Не удалось запустить встроенный сеанс")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
