package com.mremotedroid.app.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.freerdp.freerdpcore.presentation.SessionActivity
import com.mremotedroid.app.data.db.NodeEntity
import java.net.URLEncoder

/**
 * Launches an RDP session inside the app using the embedded FreeRDP engine
 * (the bundled freeRDPCore module), rather than handing off to an external client.
 *
 * It builds a `freerdp://user@host:port/connect?...` URI that
 * [com.freerdp.freerdpcore.services.LibFreeRDP.setConnectionInfo] turns into
 * FreeRDP command-line arguments, and starts [SessionActivity] with it.
 */
object EmbeddedRdpLauncher {

    sealed interface Result {
        data object Ok : Result
        data class Error(val message: String) : Result
    }

    fun launch(context: Context, node: NodeEntity, plainPassword: String?): Result {
        return try {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels.coerceAtLeast(640)
            val height = metrics.heightPixels.coerceAtLeast(480)

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
                .appendQueryParameter("dynamic-resolution", "")  // -> /dynamic-resolution
                .appendQueryParameter("size", "${width}x${height}")

            if (node.domain.isNotBlank()) builder.appendQueryParameter("d", node.domain)
            if (!plainPassword.isNullOrEmpty()) builder.appendQueryParameter("p", plainPassword)
            if (node.consoleSession) builder.appendQueryParameter("admin", "") // -> /admin
            if (node.gatewayHostname.isNotBlank()) {
                builder.appendQueryParameter("g", node.gatewayHostname)
            }

            val intent = Intent(context, SessionActivity::class.java).apply {
                data = builder.build()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.Ok
        } catch (e: Exception) {
            Result.Error(e.message ?: "Не удалось запустить встроенный сеанс")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
