package com.mremotedroid.app.data.importer

import android.util.Base64
import android.util.Xml
import com.mremotedroid.app.data.crypto.CredentialCrypto
import com.mremotedroid.app.data.db.NodeEntity
import com.mremotedroid.app.data.model.Protocol
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Parses a mRemoteNG `confCons.xml` into [NodeEntity] rows.
 *
 * Connection passwords in mRemoteNG >= 1.76 are encrypted with the BlockCipher
 * engine (AES-GCM key derived via PBKDF2-HMAC-SHA1). When the file itself is not
 * password-protected, mRemoteNG uses the built-in default password "mR3m".
 * We decrypt with [filePassword] (default "mR3m") and immediately re-encrypt with
 * the device Keystore before storing. Passwords that fail to decrypt are left empty.
 */
object MRemoteNgImporter {

    private const val DEFAULT_FILE_PASSWORD = "mR3m"
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 16
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    data class Result(
        val nodes: List<NodeEntity>,
        val folders: Int,
        val connections: Int,
        val passwordsDecrypted: Int,
        val passwordsFailed: Int
    )

    fun parse(input: InputStream, filePassword: String = DEFAULT_FILE_PASSWORD): Result {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        val nodes = mutableListOf<NodeEntity>()
        // Stack of parent ids; root connections/folders get parentId = null.
        val parentStack = ArrayDeque<String?>().apply { addLast(null) }
        var iterations = 1000
        var folders = 0
        var connections = 0
        var pwOk = 0
        var pwFail = 0
        var sortCounter = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "Connections" -> {
                            attr(parser, "KdfIterations")?.toIntOrNull()?.let { iterations = it }
                        }
                        "Node" -> {
                            val type = attr(parser, "Type").orEmpty()
                            val isContainer = type.equals("Container", ignoreCase = true)
                            val id = UUID.randomUUID().toString()
                            val parentId = parentStack.lastOrNull()

                            if (isContainer) {
                                folders++
                                nodes += NodeEntity(
                                    id = id,
                                    parentId = parentId,
                                    nodeType = NodeEntity.TYPE_FOLDER,
                                    name = attr(parser, "Name").orEmpty().ifEmpty { "Folder" },
                                    sortIndex = sortCounter++
                                )
                                // Children of a container nest under it.
                                parentStack.addLast(id)
                            } else {
                                connections++
                                val encPw = attr(parser, "Password")
                                val plain = decryptPassword(encPw, filePassword, iterations)
                                if (!encPw.isNullOrEmpty()) {
                                    if (plain != null) pwOk++ else pwFail++
                                }
                                val proto = Protocol.fromMRemoteName(attr(parser, "Protocol"))
                                nodes += NodeEntity(
                                    id = id,
                                    parentId = parentId,
                                    nodeType = NodeEntity.TYPE_CONNECTION,
                                    name = attr(parser, "Name").orEmpty().ifEmpty { "Connection" },
                                    sortIndex = sortCounter++,
                                    protocol = proto.name,
                                    hostname = attr(parser, "Hostname").orEmpty(),
                                    port = attr(parser, "Port")?.toIntOrNull() ?: proto.defaultPort,
                                    username = attr(parser, "Username").orEmpty(),
                                    domain = attr(parser, "Domain").orEmpty(),
                                    credentialBlob = plain?.let { CredentialCrypto.encrypt(it) },
                                    colorDepth = parseColors(attr(parser, "Colors")),
                                    consoleSession = attr(parser, "UseConsoleSession")
                                        ?.equals("true", ignoreCase = true) ?: false,
                                    gatewayHostname = attr(parser, "RDGatewayHostname").orEmpty(),
                                    description = attr(parser, "Descr").orEmpty()
                                )
                                // A connection node may still contain nested nodes in malformed
                                // files; push its id so any children attach sensibly.
                                parentStack.addLast(id)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (name == "Node" && parentStack.size > 1) {
                        parentStack.removeLast()
                    }
                }
            }
            event = parser.next()
        }

        return Result(nodes, folders, connections, pwOk, pwFail)
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseColors(raw: String?): Int = when (raw?.trim()) {
        "Colors256" -> 8
        "Colors15Bit" -> 15
        "Colors16Bit" -> 16
        "Colors24Bit" -> 24
        "Colors32Bit" -> 32
        else -> 32
    }

    /** Returns the decrypted password, or null if decryption failed or input was empty. */
    private fun decryptPassword(encrypted: String?, password: String, iterations: Int): String? {
        if (encrypted.isNullOrEmpty()) return null
        return try {
            val data = Base64.decode(encrypted, Base64.DEFAULT)
            if (data.size <= SALT_LEN + NONCE_LEN) return null
            val salt = data.copyOfRange(0, SALT_LEN)
            val nonce = data.copyOfRange(SALT_LEN, SALT_LEN + NONCE_LEN)
            val cipherText = data.copyOfRange(SALT_LEN + NONCE_LEN, data.size)

            val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(keySpec).encoded
            val secret = SecretKeySpec(key, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
