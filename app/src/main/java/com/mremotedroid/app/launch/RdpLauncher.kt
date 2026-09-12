package com.mremotedroid.app.launch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.mremotedroid.app.data.db.NodeEntity
import java.io.File
import java.net.URLEncoder

/**
 * Launches a connection in whatever RDP client the user has installed. Two paths:
 *
 *  - [launchViaRdpFile] writes a standard `.rdp` file and fires ACTION_VIEW. Both the
 *    Microsoft Remote Desktop client and aFreeRDP can open these, and Android shows a
 *    chooser when several are installed. Passwords are not written to the file (the
 *    Microsoft client only accepts a machine-hashed password blob), so we optionally
 *    copy the password to the clipboard for a quick paste.
 *
 *  - [launchViaUri] builds an `rdp://user:pass@host:port` URI that aFreeRDP understands
 *    directly, including the password.
 */
object RdpLauncher {

    const val MIME_RDP = "application/x-rdp"

    val KNOWN_CLIENTS = listOf(
        "com.microsoft.rdc.androidx" to "Microsoft Remote Desktop",
        "com.microsoft.rdc.android" to "Microsoft Remote Desktop (legacy)",
        "com.freerdp.afreerdp" to "aFreeRDP"
    )

    sealed interface LaunchResult {
        data object Ok : LaunchResult
        data class NoHandler(val detail: String) : LaunchResult
        data class Error(val message: String) : LaunchResult
    }

    fun installedClients(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        return KNOWN_CLIENTS.filter { (pkg, _) ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    fun buildRdpFileContent(node: NodeEntity): String {
        val sb = StringBuilder()
        val address = if (node.port in intArrayOf(0, 3389)) node.hostname
        else "${node.hostname}:${node.port}"
        sb.appendLine("full address:s:$address")
        if (node.username.isNotBlank()) sb.appendLine("username:s:${node.username}")
        if (node.domain.isNotBlank()) sb.appendLine("domain:s:${node.domain}")
        sb.appendLine("session bpp:i:${node.colorDepth.coerceIn(8, 32)}")
        sb.appendLine("screen mode id:i:${if (node.fullscreen) 2 else 1}")
        sb.appendLine("connection type:i:7")
        sb.appendLine("administrative session:i:${if (node.consoleSession) 1 else 0}")
        if (node.gatewayHostname.isNotBlank()) {
            sb.appendLine("gatewayhostname:s:${node.gatewayHostname}")
            sb.appendLine("gatewayusagemethod:i:1")
        }
        sb.appendLine("redirectclipboard:i:1")
        sb.appendLine("audiomode:i:0")
        return sb.toString()
    }

    fun launchViaRdpFile(
        context: Context,
        node: NodeEntity,
        plainPassword: String?
    ): LaunchResult {
        return try {
            val dir = File(context.cacheDir, "rdp").apply { mkdirs() }
            val safeName = node.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "connection" }
            val file = File(dir, "$safeName.rdp")
            file.writeText(buildRdpFileContent(node), Charsets.UTF_8)

            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_RDP)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return LaunchResult.NoHandler("Не найден клиент, открывающий .rdp файлы.")
            }
            if (!plainPassword.isNullOrEmpty()) copyPasswordToClipboard(context, plainPassword)
            context.startActivity(intent)
            LaunchResult.Ok
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Не удалось запустить клиент")
        }
    }

    fun launchViaUri(
        context: Context,
        node: NodeEntity,
        plainPassword: String?
    ): LaunchResult {
        return try {
            val userInfo = buildString {
                if (node.domain.isNotBlank()) append(enc(node.domain)).append("%5C") // "\"
                if (node.username.isNotBlank()) append(enc(node.username))
                if (!plainPassword.isNullOrEmpty()) append(":").append(enc(plainPassword))
            }
            val authority = buildString {
                if (userInfo.isNotEmpty()) append(userInfo).append("@")
                append(node.hostname)
                if (node.port !in intArrayOf(0, 3389)) append(":").append(node.port)
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("rdp://$authority")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return LaunchResult.NoHandler("Нет клиента, поддерживающего rdp:// ссылки (например aFreeRDP).")
            }
            context.startActivity(intent)
            LaunchResult.Ok
        } catch (e: Exception) {
            LaunchResult.Error(e.message ?: "Не удалось запустить клиент")
        }
    }

    private fun copyPasswordToClipboard(context: Context, password: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("RDP password", password).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
