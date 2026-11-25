package com.mremotedroid.app.remote.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/** Creating SSH key pairs in the app, and the public half of a stored private key. */
object SshKeys {
    enum class Type(val label: String, val jschType: Int, val bits: Int) {
        ED25519("Ed25519", KeyPair.ED25519, 256),
        ECDSA("ECDSA P-256", KeyPair.ECDSA, 256),
        RSA("RSA 3072", KeyPair.RSA, 3072)
    }

    /** [privateKey] in OpenSSH format, [publicKey] as one authorized_keys line. */
    class Generated(val privateKey: String, val publicKey: String, val fingerprint: String)

    /** Generates a new pair without a passphrase (the app stores it encrypted). Slow for RSA: call off the main thread. */
    fun generate(type: Type, comment: String): Generated {
        SshConnect.useBouncyCastle()
        val pair = KeyPair.genKeyPair(JSch(), type.jschType, type.bits)
        try {
            val private = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }.toString("US-ASCII")
            return Generated(private, publicLine(pair, comment), HostKeyStore.fingerprint(pair.publicKeyBlob))
        } finally {
            pair.dispose()
        }
    }

    sealed interface PublicKey {
        class Ok(val line: String, val fingerprint: String) : PublicKey
        /** The key is protected and [passphrase] did not open it. */
        data object NeedsPassphrase : PublicKey
        data object Invalid : PublicKey
    }

    /** The authorized_keys line for [privateKey]; [passphrase] opens a protected key. */
    fun publicKey(privateKey: String, passphrase: String?, comment: String): PublicKey {
        SshConnect.useBouncyCastle()
        val pair = runCatching { KeyPair.load(JSch(), privateKey.toByteArray(), null) }.getOrNull()
            ?: return PublicKey.Invalid
        try {
            // OpenSSH keys keep the public half outside the encrypted part, older PEM keys do not
            val blob = runCatching { pair.publicKeyBlob }.getOrNull()
            if (blob == null || blob.isEmpty()) {
                if (pair.isEncrypted && (passphrase.isNullOrEmpty() || !pair.decrypt(passphrase))) {
                    return PublicKey.NeedsPassphrase
                }
            }
            val key = pair.publicKeyBlob ?: return PublicKey.Invalid
            return PublicKey.Ok(publicLine(pair, comment), HostKeyStore.fingerprint(key))
        } catch (e: Exception) {
            return PublicKey.Invalid
        } finally {
            pair.dispose()
        }
    }

    private fun publicLine(pair: KeyPair, comment: String): String =
        ByteArrayOutputStream().also { pair.writePublicKey(it, comment) }.toString("US-ASCII").trim()
}
