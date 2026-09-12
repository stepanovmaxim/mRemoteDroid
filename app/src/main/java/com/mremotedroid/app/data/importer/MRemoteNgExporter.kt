package com.mremotedroid.app.data.importer

import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import java.util.UUID

/**
 * Serializes the connection tree into a mRemoteNG `confCons.xml`.
 *
 * Passwords are encrypted with [MRemoteNgCrypto] (AES-GCM, PBKDF2) using [filePassword]
 * (default "mR3m", matching an unprotected mRemoteNG file), so the produced file can be
 * imported back both by this app and by mRemoteNG on the desktop. Every property is
 * written explicitly with all `Inherit*` flags set to false, so no node depends on a
 * parent for its values.
 */
object MRemoteNgExporter {

    fun export(
        nodes: List<NodeEntity>,
        plaintextPasswordOf: (NodeEntity) -> String,
        filePassword: String = MRemoteNgCrypto.DEFAULT_FILE_PASSWORD,
        iterations: Int = MRemoteNgCrypto.DEFAULT_ITERATIONS
    ): String {
        val byParent = nodes.groupBy { it.parentId }
            .mapValues { (_, v) -> v.sortedWith(compareBy({ it.sortIndex }, { it.name.lowercase() })) }

        val protectedAttr = MRemoteNgCrypto.encrypt(MRemoteNgCrypto.PROTECTED_SANITY, filePassword, iterations)

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<mrng:Connections xmlns:mrng=\"http://mremoteng.org\" ")
        sb.append("Name=\"Connections\" Export=\"false\" ")
        sb.append("EncryptionEngine=\"AES\" BlockCipherMode=\"GCM\" ")
        sb.append("KdfIterations=\"$iterations\" FullFileEncryption=\"false\" ")
        sb.append("Protected=\"${esc(protectedAttr)}\" ConfVersion=\"2.6\">\n")

        fun writeNode(node: NodeEntity, indent: String) {
            val isFolder = node.nodeType == NodeEntity.TYPE_FOLDER
            val children = byParent[node.id].orEmpty()
            val attrs = buildAttributes(node, plaintextPasswordOf, filePassword, iterations)
            val attrString = attrs.entries.joinToString(" ") { (k, v) -> "$k=\"${esc(v)}\"" }

            if (isFolder) {
                sb.append("$indent<Node $attrString>\n")
                children.forEach { writeNode(it, "$indent  ") }
                sb.append("$indent</Node>\n")
            } else {
                sb.append("$indent<Node $attrString />\n")
            }
        }

        byParent[null].orEmpty().forEach { writeNode(it, "  ") }
        sb.append("</mrng:Connections>\n")
        return sb.toString()
    }

    private fun buildAttributes(
        node: NodeEntity,
        plaintextPasswordOf: (NodeEntity) -> String,
        filePassword: String,
        iterations: Int
    ): LinkedHashMap<String, String> {
        val isFolder = node.nodeType == NodeEntity.TYPE_FOLDER
        val proto = runCatching { Protocol.valueOf(node.protocol) }.getOrDefault(Protocol.RDP)
        val plain = if (isFolder) "" else plaintextPasswordOf(node)
        val encPw = if (plain.isEmpty()) "" else MRemoteNgCrypto.encrypt(plain, filePassword, iterations)

        val a = LinkedHashMap<String, String>()
        a["Name"] = node.name
        a["Type"] = if (isFolder) "Container" else "Connection"
        if (isFolder) a["Expanded"] = node.expanded.toString()
        a["Descr"] = node.description
        a["Icon"] = "mRemoteNG"
        a["Panel"] = "General"
        a["Id"] = UUID.randomUUID().toString()
        a["Username"] = node.username
        a["Domain"] = node.domain
        a["Password"] = encPw
        a["Hostname"] = node.hostname
        a["Protocol"] = when (proto) {
            Protocol.RDP -> "RDP"
            Protocol.VNC -> "VNC"
            Protocol.SSH -> "SSH2"
        }
        a["PuttySession"] = "Default Settings"
        a["Port"] = node.port.toString()
        a["ConnectToConsole"] = node.consoleSession.toString()
        a["UseCredSsp"] = "true"
        a["UseRestrictedAdmin"] = "false"
        a["UseRCG"] = "false"
        a["UseVmId"] = "false"
        a["UseEnhancedMode"] = "false"
        a["VmId"] = ""
        a["RenderingEngine"] = "IEProcessor"
        a["ICAEncryptionStrength"] = "EncrBasic"
        a["RDPAuthenticationLevel"] = "NoAuth"
        a["RDPMinutesToIdleTimeout"] = "0"
        a["RDPAlertIdleTimeout"] = "false"
        a["LoadBalanceInfo"] = ""
        a["Colors"] = colorsToken(node.colorDepth)
        a["Resolution"] = "FitToWindow"
        a["AutomaticResize"] = "true"
        a["DisplayWallpaper"] = "false"
        a["DisplayThemes"] = "false"
        a["EnableFontSmoothing"] = "false"
        a["EnableDesktopComposition"] = "false"
        a["CacheBitmaps"] = "false"
        a["RedirectDiskDrives"] = "false"
        a["RedirectPorts"] = "false"
        a["RedirectPrinters"] = "false"
        a["RedirectClipboard"] = "true"
        a["RedirectSmartCards"] = "false"
        a["RedirectSound"] = "DoNotPlay"
        a["RedirectAudioCapture"] = "false"
        a["RedirectKeys"] = "false"
        a["Connected"] = "false"
        a["PreExtApp"] = ""
        a["PostExtApp"] = ""
        a["MacAddress"] = ""
        a["UserField"] = ""
        a["ExtApp"] = ""
        a["VNCCompression"] = "CompNone"
        a["VNCEncoding"] = "EncHextile"
        a["VNCAuthMode"] = "AuthVNC"
        a["VNCProxyType"] = "ProxyNone"
        a["VNCProxyIP"] = ""
        a["VNCProxyPort"] = "0"
        a["VNCProxyUsername"] = ""
        a["VNCProxyPassword"] = ""
        a["VNCColors"] = "ColNormal"
        a["VNCSmartSizeMode"] = "SmartSAspect"
        a["VNCViewOnly"] = "false"
        a["RDGatewayUsageMethod"] = if (node.gatewayHostname.isBlank()) "Never" else "Always"
        a["RDGatewayHostname"] = node.gatewayHostname
        a["RDGatewayUseConnectionCredentials"] = "Yes"
        a["RDGatewayUsername"] = ""
        a["RDGatewayDomain"] = ""
        a["RDGatewayPassword"] = ""
        a["Favorite"] = "false"
        INHERIT_FLAGS.forEach { a[it] = "false" }
        return a
    }

    private fun colorsToken(depth: Int): String = when (depth) {
        8 -> "Colors256"
        15 -> "Colors15Bit"
        16 -> "Colors16Bit"
        24 -> "Colors24Bit"
        else -> "Colors32Bit"
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val INHERIT_FLAGS = listOf(
        "InheritCacheBitmaps", "InheritColors", "InheritDescription", "InheritDisplayThemes",
        "InheritDisplayWallpaper", "InheritEnableFontSmoothing", "InheritEnableDesktopComposition",
        "InheritDomain", "InheritIcon", "InheritPanel", "InheritPassword", "InheritPort",
        "InheritProtocol", "InheritPuttySession", "InheritRedirectDiskDrives", "InheritRedirectKeys",
        "InheritRedirectPorts", "InheritRedirectPrinters", "InheritRedirectClipboard",
        "InheritRedirectSmartCards", "InheritRedirectSound", "InheritRedirectAudioCapture",
        "InheritResolution", "InheritAutomaticResize", "InheritUseConsoleSession", "InheritUseCredSsp",
        "InheritUseRestrictedAdmin", "InheritUseRCG", "InheritUseVmId", "InheritUseEnhancedMode",
        "InheritVmId", "InheritRenderingEngine", "InheritUsername", "InheritICAEncryptionStrength",
        "InheritRDPAuthenticationLevel", "InheritRDPMinutesToIdleTimeout", "InheritRDPAlertIdleTimeout",
        "InheritLoadBalanceInfo", "InheritPreExtApp", "InheritPostExtApp", "InheritMacAddress",
        "InheritUserField", "InheritExtApp", "InheritVNCCompression", "InheritVNCEncoding",
        "InheritVNCAuthMode", "InheritVNCProxyType", "InheritVNCProxyIP", "InheritVNCProxyPort",
        "InheritVNCProxyUsername", "InheritVNCProxyPassword", "InheritVNCColors",
        "InheritVNCSmartSizeMode", "InheritVNCViewOnly", "InheritRDGatewayUsageMethod",
        "InheritRDGatewayHostname", "InheritRDGatewayUseConnectionCredentials",
        "InheritRDGatewayUsername", "InheritRDGatewayDomain", "InheritRDGatewayPassword",
        "InheritFavorite"
    )
}
