package com.mremotedroid.app.data.importer

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypt/decrypt in the mRemoteNG "BlockCipher" (AEAD) format used by confCons.xml
 * (mRemoteNG >= 1.76). Shared by the importer (decrypt) and exporter (encrypt) so a
 * round-trip through this app produces a file mRemoteNG can read back.
 *
 * Blob layout, Base64-encoded: [salt(16)][nonce(16)][ciphertext + GCM tag(16)].
 * Key = PBKDF2-HMAC-SHA1(password, salt, iterations, 256 bits). Default file
 * password is "mR3m" when the file is not itself password-protected.
 */
object MRemoteNgCrypto {

    const val DEFAULT_FILE_PASSWORD = "mR3m"
    const val DEFAULT_ITERATIONS = 1000

    /** Sanity string mRemoteNG stores (encrypted) in the root "Protected" attribute. */
    const val PROTECTED_SANITY = "ThisIsNotProtected"

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 16
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    /**
     * Tries the modern AEAD format first, then the pre-1.76 legacy format
     * (AES-CBC with an MD5-derived key). Returns null only if neither works.
     */
    fun decryptAny(
        encrypted: String?,
        password: String = DEFAULT_FILE_PASSWORD,
        iterations: Int = DEFAULT_ITERATIONS
    ): String? = decrypt(encrypted, password, iterations) ?: decryptLegacy(encrypted, password)

    /** Returns the decrypted text, or null on empty/invalid input or wrong password. */
    fun decrypt(
        encrypted: String?,
        password: String = DEFAULT_FILE_PASSWORD,
        iterations: Int = DEFAULT_ITERATIONS
    ): String? {
        if (encrypted.isNullOrEmpty()) return null
        return try {
            val data = Base64.decode(encrypted, Base64.DEFAULT)
            if (data.size <= SALT_LEN + NONCE_LEN) return null
            val salt = data.copyOfRange(0, SALT_LEN)
            val nonce = data.copyOfRange(SALT_LEN, SALT_LEN + NONCE_LEN)
            val cipherText = data.copyOfRange(SALT_LEN + NONCE_LEN, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            // mRemoteNG feeds the salt to GCM as associated data (nonSecretPayload).
            cipher.updateAAD(salt)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Legacy mRemoteNG (< 1.76) password format: AES-CBC where the 128-bit key is
     * MD5(UTF-8(password)), the 16-byte IV is prepended to the ciphertext, PKCS#5/7
     * padding. No salt, no KDF iterations.
     */
    fun decryptLegacy(encrypted: String?, password: String = DEFAULT_FILE_PASSWORD): String? {
        if (encrypted.isNullOrEmpty()) return null
        return try {
            val data = Base64.decode(encrypted, Base64.DEFAULT)
            if (data.size <= 16) return null
            val key = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
            val iv = data.copyOfRange(0, 16)
            val cipherText = data.copyOfRange(16, data.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Encrypts [plainText] into the mRemoteNG blob format (empty input -> ""). */
    fun encrypt(
        plainText: String,
        password: String = DEFAULT_FILE_PASSWORD,
        iterations: Int = DEFAULT_ITERATIONS
    ): String {
        if (plainText.isEmpty()) return ""
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        // Match mRemoteNG: the salt is the GCM associated data (nonSecretPayload).
        cipher.updateAAD(salt)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = salt + nonce + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }
}
