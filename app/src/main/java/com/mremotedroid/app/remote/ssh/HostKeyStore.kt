package com.mremotedroid.app.remote.ssh

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.security.MessageDigest

/**
 * Trusted SSH server keys, one "host type base64key" line each, in the app's private
 * storage. JSch asks before a new or changed key is trusted ("StrictHostKeyChecking=ask");
 * [lastCheck] tells the prompt which case it is.
 */
class HostKeyStore(context: Context) : HostKeyRepository {
    private val file = File(context.filesDir, "known_hosts")

    data class Check(val host: String, val type: String, val fingerprint: String, val changed: Boolean)

    @Volatile var lastCheck: Check? = null
        private set

    private data class Entry(val host: String, val type: String, val key: String)

    @Synchronized
    private fun read(): List<Entry> =
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { line ->
            val p = line.trim().split(' ')
            if (p.size >= 3) Entry(p[0], p[1], p[2]) else null
        }

    @Synchronized
    private fun write(entries: List<Entry>) {
        file.writeText(entries.joinToString("") { "${it.host} ${it.type} ${it.key}\n" })
    }

    override fun check(host: String, key: ByteArray): Int {
        val type = typeOf(key)
        val encoded = Base64.encodeToString(key, Base64.NO_WRAP)
        val sameType = read().filter { it.host == host && it.type == type }
        val result = when {
            sameType.any { it.key == encoded } -> HostKeyRepository.OK
            sameType.isNotEmpty() -> HostKeyRepository.CHANGED
            else -> HostKeyRepository.NOT_INCLUDED
        }
        lastCheck = Check(host, type, fingerprint(key), result == HostKeyRepository.CHANGED)
        return result
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        val entries = read().filterNot { it.host == hostkey.host && it.type == hostkey.type }
        write(entries + Entry(hostkey.host, hostkey.type, hostkey.key))
    }

    override fun remove(host: String, type: String?) {
        write(read().filterNot { it.host == host && (type == null || it.type == type) })
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        val encoded = key?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        write(read().filterNot {
            it.host == host && (type == null || it.type == type) && (encoded == null || it.key == encoded)
        })
    }

    override fun getKnownHostsRepositoryID(): String = "mremotedroid"

    override fun getHostKey(): Array<HostKey> = toHostKeys(read())

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        toHostKeys(read().filter { (host == null || it.host == host) && (type == null || it.type == type) })

    private fun toHostKeys(entries: List<Entry>): Array<HostKey> = entries.mapNotNull {
        runCatching { HostKey(it.host, Base64.decode(it.key, Base64.NO_WRAP)) }.getOrNull()
    }.toTypedArray()

    companion object {
        /** The key type is the first string inside the SSH wire-format key blob. */
        fun typeOf(key: ByteArray): String {
            if (key.size < 4) return "unknown"
            val len = ((key[0].toInt() and 0xFF) shl 24) or ((key[1].toInt() and 0xFF) shl 16) or
                ((key[2].toInt() and 0xFF) shl 8) or (key[3].toInt() and 0xFF)
            return if (len in 1..(key.size - 4)) String(key, 4, len, Charsets.US_ASCII) else "unknown"
        }

        /** The same "SHA256:…" form that ssh-keygen -l prints. */
        fun fingerprint(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}
