package com.mremotedroid.app.data.importer

import android.util.Xml
import com.mremotedroid.app.data.crypto.CredentialCrypto
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID

/**
 * Imports connections from formats other than confCons.xml: Microsoft `.rdp` files,
 * Remote Desktop Connection Manager `.rdg` files and CSV lists.
 *
 * `.rdp` and `.rdg` passwords are protected with Windows DPAPI, bound to the user on
 * the source computer, so they cannot be read here and are only counted.
 */
object ExternalImporter {

    enum class Format { MREMOTENG, RDP, RDCMAN, CSV }

    data class Result(
        val nodes: List<NodeEntity>,
        val passwordsNotTransferred: Int
    )

    class UnsupportedFormatException : Exception()

    fun detect(bytes: ByteArray): Format {
        val head = decode(bytes).take(4096)
        val isXml = head.trimStart('﻿', ' ', '\r', '\n').startsWith("<")
        return when {
            isXml && head.contains("<RDCMan", ignoreCase = true) -> Format.RDCMAN
            isXml && head.contains("Connections", ignoreCase = true) -> Format.MREMOTENG
            RDP_MARKER.containsMatchIn(head) -> Format.RDP
            else -> Format.CSV
        }
    }

    fun parse(format: Format, bytes: ByteArray, fileName: String?): Result = when (format) {
        Format.RDP -> parseRdp(decode(bytes), fileName)
        Format.RDCMAN -> parseRdcMan(decode(bytes))
        Format.CSV -> parseCsvAnyEncoding(bytes)
        Format.MREMOTENG -> throw UnsupportedFormatException()
    }

    /** An ANSI file may come from a PC with another code page than this device's language. */
    private fun parseCsvAnyEncoding(bytes: ByteArray): Result {
        val texts = (ansiCharsets().map { decode(bytes, it) }).distinct()
        texts.dropLast(1).forEach { text ->
            try {
                return parseCsv(text)
            } catch (_: UnsupportedFormatException) {
            }
        }
        return parseCsv(texts.last())
    }

    // --- .rdp ---

    private val RDP_MARKER = Regex(
        "^\\s*(full address|alternate full address):s:",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
    )

    private fun parseRdp(text: String, fileName: String?): Result {
        val values = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val parts = line.trim().trimStart('﻿').split(":", limit = 3)
            if (parts.size == 3) values[parts[0].trim().lowercase(Locale.ROOT)] = parts[2].trim()
        }
        val address = values["full address"]?.ifBlank { null } ?: values["alternate full address"]
        val (host, addressPort) = splitHostPort(address.orEmpty())
        if (!isValidHost(host)) throw UnsupportedFormatException()

        val (username, userDomain) = splitDomain(values["username"].orEmpty())
        // gatewayusagemethod: 1 and 2 use the gateway, 0 and 4 connect directly.
        val gatewayUsage = values["gatewayusagemethod"]?.toIntOrNull()
        val gateway = if (gatewayUsage == null || gatewayUsage == 1 || gatewayUsage == 2) {
            values["gatewayhostname"].orEmpty()
        } else ""
        val hasPassword = values["password 51"].orEmpty().isNotEmpty()

        // Drive redirection is deliberately not taken from the file: a crafted .rdp that
        // shares local files with an attacker's server is a known phishing technique.
        val node = connection(
            parentId = null,
            sortIndex = 0,
            name = fileName?.substringBeforeLast('.')?.ifBlank { null } ?: host,
            host = host,
            port = addressPort ?: values["server port"]?.toIntOrNull() ?: Protocol.RDP.defaultPort
        ).copy(
            username = username,
            domain = values["domain"]?.ifBlank { null } ?: userDomain,
            gatewayHostname = gateway,
            consoleSession = values["administrative session"] == "1" || values["connect to console"] == "1",
            colorDepth = values["session bpp"]?.toIntOrNull()?.takeIf { it in setOf(8, 15, 16, 24, 32) } ?: 32,
            soundMode = when (values["audiomode"]) {
                "1" -> NodeEntity.SOUND_SERVER
                "2" -> NodeEntity.SOUND_OFF
                else -> NodeEntity.SOUND_DEVICE
            }
        )
        return Result(listOf(node), if (hasPassword) 1 else 0)
    }

    // --- .rdg ---

    private class Element(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<Element>()
        var text = ""
        fun child(n: String) = children.firstOrNull { it.name.equals(n, ignoreCase = true) }
        fun value(n: String) = child(n)?.text?.trim().orEmpty()
        val inherits get() = attrs["inherit"].equals("FromParent", ignoreCase = true)
    }

    private fun parseXml(text: String): Element {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(text.trimStart('﻿')))
        }
        var root: Element? = null
        val stack = ArrayDeque<Element>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (stack.size >= MAX_XML_DEPTH) throw UnsupportedFormatException()
                    val attrs = (0 until parser.attributeCount)
                        .associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    val element = Element(parser.name, attrs)
                    stack.lastOrNull()?.children?.add(element)
                    if (root == null) root = element
                    stack.addLast(element)
                }
                XmlPullParser.TEXT -> stack.lastOrNull()?.let { it.text += parser.text }
                XmlPullParser.END_TAG -> stack.removeLast()
            }
            event = parser.next()
        }
        return root ?: throw UnsupportedFormatException()
    }

    private class RdgLogon(val user: String, val domain: String, val hasPassword: Boolean)
    private class RdgGateway(val host: String, val user: String, val domain: String)
    private class RdgSettings(
        val logon: RdgLogon? = null,
        val gateway: RdgGateway? = null,
        val console: Boolean = false,
        val port: Int = Protocol.RDP.defaultPort,
        val sound: Int = NodeEntity.SOUND_OFF
    )

    private fun parseRdcMan(text: String): Result {
        val file = parseXml(text).child("file") ?: throw UnsupportedFormatException()
        val nodes = ArrayList<NodeEntity>()
        var noPasswords = 0
        var sort = 0

        val profiles = HashMap<String, RdgLogon>()
        file.child("credentialsProfiles")?.children.orEmpty().forEach { p ->
            profiles[p.value("profileName")] = logonOf(p)
        }

        fun settingsOf(e: Element, parent: RdgSettings): RdgSettings {
            val logonEl = e.child("logonCredentials")
            val logon = when {
                logonEl == null || logonEl.inherits -> parent.logon
                else -> profiles[logonEl.value("profileName")] ?: logonOf(logonEl)
            }
            val gatewayEl = e.child("gatewaySettings")
            val gateway = when {
                gatewayEl == null || gatewayEl.inherits -> parent.gateway
                !gatewayEl.value("enabled").equals("True", ignoreCase = true) -> null
                else -> {
                    val port = gatewayEl.value("port")
                    val host = gatewayEl.value("hostName")
                    val (user, domain) = splitDomain(gatewayEl.value("userName"))
                    RdgGateway(
                        if (port.isEmpty() || port == "443") host else "$host:$port",
                        user,
                        gatewayEl.value("domain").ifEmpty { domain }
                    )
                }
            }
            val connection = e.child("connectionSettings")?.takeUnless { it.inherits }
            val resources = e.child("localResources")?.takeUnless { it.inherits }
            return RdgSettings(
                logon = logon,
                gateway = gateway,
                console = connection?.value("connectToConsole")?.equals("True", ignoreCase = true) ?: parent.console,
                port = connection?.value("port")?.toIntOrNull() ?: parent.port,
                sound = when (resources?.value("audioRedirection")?.lowercase(Locale.ROOT)) {
                    null -> parent.sound
                    "client" -> NodeEntity.SOUND_DEVICE
                    "remote", "server" -> NodeEntity.SOUND_SERVER
                    else -> NodeEntity.SOUND_OFF
                }
            )
        }

        fun walk(e: Element, parentId: String?, inherited: RdgSettings) {
            val settings = settingsOf(e, inherited)
            val folderId = UUID.randomUUID().toString()
            nodes += NodeEntity(
                id = folderId,
                parentId = parentId,
                nodeType = NodeEntity.TYPE_FOLDER,
                name = propertyOf(e, "name").ifEmpty { "RDCMan" },
                sortIndex = sort++
            )
            e.children.forEach { c ->
                when (c.name.lowercase(Locale.ROOT)) {
                    "group" -> walk(c, folderId, settings)
                    "server" -> {
                        val s = settingsOf(c, settings)
                        val (host, port) = splitHostPort(propertyOf(c, "name"))
                        if (isValidHost(host)) {
                            if (s.logon?.hasPassword == true) noPasswords++
                            val (user, userDomain) = splitDomain(s.logon?.user.orEmpty())
                            nodes += connection(
                                folderId,
                                sort++,
                                propertyOf(c, "displayName").ifEmpty { host },
                                host,
                                port ?: s.port
                            ).copy(
                                username = user,
                                domain = s.logon?.domain?.ifEmpty { null } ?: userDomain,
                                consoleSession = s.console,
                                gatewayHostname = s.gateway?.host.orEmpty(),
                                gatewayUsername = s.gateway?.user.orEmpty(),
                                gatewayDomain = s.gateway?.domain.orEmpty(),
                                soundMode = s.sound,
                                description = propertyOf(c, "comment")
                            )
                        }
                    }
                }
            }
        }
        walk(file, null, RdgSettings())
        return Result(nodes, noPasswords)
    }

    private fun logonOf(e: Element) =
        RdgLogon(e.value("userName"), e.value("domain"), e.value("password").isNotEmpty())

    /** RDCMan 2.7 keeps values under `<properties>`; older files put them on the element. */
    private fun propertyOf(e: Element, name: String): String =
        e.child("properties")?.value(name)?.ifEmpty { null } ?: e.value(name)

    // --- CSV ---

    private val COLUMN_ALIASES = mapOf(
        "name" to setOf("name", "title", "displayname", "display name", "label", "connection", "имя", "название"),
        "host" to setOf(
            "host", "hostname", "host name", "server", "address", "computer", "computername",
            "ip", "fqdn", "хост", "сервер", "адрес"
        ),
        "port" to setOf("port", "порт"),
        "user" to setOf("user", "username", "user name", "login", "account", "пользователь", "логин"),
        "domain" to setOf("domain", "домен"),
        "password" to setOf("password", "pass", "пароль"),
        "folder" to setOf("folder", "group", "path", "папка", "группа"),
        "description" to setOf("description", "descr", "comment", "notes", "note", "описание", "комментарий"),
        "protocol" to setOf("protocol", "type", "протокол"),
        "gateway" to setOf("gateway", "rdgateway", "шлюз")
    )

    private fun parseCsv(text: String): Result {
        val body = text.trimStart('﻿')
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() } ?: throw UnsupportedFormatException()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
        val records = splitCsv(body, delimiter)
        val header = records.first().map { it.trim().lowercase(Locale.ROOT) }
        val columns = COLUMN_ALIASES.mapValues { (_, aliases) -> header.indexOfFirst { it in aliases } }
        if (columns.getValue("host") < 0) throw UnsupportedFormatException()

        val nodes = ArrayList<NodeEntity>()
        val folders = HashMap<String, String>()
        var sort = 0

        fun folderFor(path: String): String? {
            var parent: String? = null
            var current = ""
            path.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                current += "/$part"
                parent = folders.getOrPut(current) {
                    UUID.randomUUID().toString().also { id ->
                        nodes += NodeEntity(
                            id = id,
                            parentId = parent,
                            nodeType = NodeEntity.TYPE_FOLDER,
                            name = part,
                            sortIndex = sort++
                        )
                    }
                }
            }
            return parent
        }

        records.drop(1).forEach { record ->
            fun cell(column: String): String =
                columns.getValue(column).takeIf { it >= 0 }?.let(record::getOrNull)?.trim().orEmpty()

            val (host, port) = splitHostPort(cell("host"))
            if (!isValidHost(host)) return@forEach
            val protocol = Protocol.fromMRemoteName(cell("protocol"))
            val (user, userDomain) = splitDomain(cell("user"))
            val password = cell("password")
            nodes += connection(
                folderFor(cell("folder")),
                sort++,
                cell("name").ifEmpty { host },
                host,
                port ?: cell("port").toIntOrNull() ?: protocol.defaultPort
            ).copy(
                protocol = protocol.name,
                username = user,
                domain = cell("domain").ifEmpty { userDomain },
                credentialBlob = password.ifEmpty { null }?.let { CredentialCrypto.encrypt(it) },
                gatewayHostname = cell("gateway"),
                description = cell("description")
            )
        }
        if (nodes.none { it.nodeType == NodeEntity.TYPE_CONNECTION }) throw UnsupportedFormatException()
        return Result(nodes, 0)
    }

    /** RFC 4180 splitting: quoted cells may hold delimiters, newlines and doubled quotes. */
    private fun splitCsv(text: String, delimiter: Char): List<List<String>> {
        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0

        fun endCell() { record.add(cell.toString()); cell.setLength(0) }
        fun endRecord() {
            endCell()
            if (record.any { it.isNotBlank() }) records.add(record)
            record = ArrayList()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == delimiter -> endCell()
                c == '\n' -> endRecord()
                c != '\r' -> cell.append(c)
            }
            i++
        }
        endRecord()
        return records
    }

    // --- shared helpers ---

    private const val MAX_XML_DEPTH = 64

    /** Host names end up in the authority of the session URI, so only host characters pass. */
    private val HOST_PATTERN = Regex("^[\\p{L}\\p{N}._\\-]+$|^[0-9A-Fa-f:.]+$")

    private fun isValidHost(host: String) = host.isNotEmpty() && HOST_PATTERN.matches(host)

    private fun splitHostPort(address: String): Pair<String, Int?> {
        val a = address.trim()
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end > 0) return a.substring(1, end) to a.substring(end + 1).removePrefix(":").toIntOrNull()
        }
        if (a.count { it == ':' } == 1) {
            return a.substringBefore(':') to a.substringAfter(':').toIntOrNull()
        }
        return a to null
    }

    /** Splits `DOMAIN\user` into user and domain; a plain user gets an empty domain. */
    private fun splitDomain(raw: String): Pair<String, String> {
        val value = raw.trim()
        val slash = value.indexOf('\\')
        return if (slash in 1 until value.length - 1) {
            value.substring(slash + 1) to value.substring(0, slash)
        } else value to ""
    }

    private fun connection(parentId: String?, sortIndex: Int, name: String, host: String, port: Int) = NodeEntity(
        id = UUID.randomUUID().toString(),
        parentId = parentId,
        nodeType = NodeEntity.TYPE_CONNECTION,
        name = name,
        sortIndex = sortIndex,
        hostname = host,
        port = port
    )

    /** mstsc saves UTF-16 with a BOM, Excel saves CSV in the Windows ANSI code page. */
    private fun decode(bytes: ByteArray, ansi: String = ansiCharsets().first()): String {
        fun b(i: Int) = bytes[i].toInt() and 0xFF
        if (bytes.size >= 2) {
            if (b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            if (b(1) == 0 && bytes.size >= 4 && b(3) == 0) return String(bytes, Charsets.UTF_16LE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName(ansi))
        }
    }

    private fun ansiCharsets(): List<String> {
        val preferred = when (Locale.getDefault().language) {
            "ru", "uk", "be", "bg", "sr" -> "windows-1251"
            "zh" -> "GBK"
            else -> "windows-1252"
        }
        return (listOf(preferred) + listOf("windows-1251", "windows-1252", "GBK")).distinct()
    }
}
