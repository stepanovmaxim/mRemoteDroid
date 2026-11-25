package com.mremotedroid.app

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.mremotedroid.app.remote.ssh.SshKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SshKeysTest {

    @Test
    fun generatesUsableKeys() {
        for (type in SshKeys.Type.entries) {
            val key = SshKeys.generate(type, "phone")
            assertTrue(type.name, key.privateKey.contains("BEGIN OPENSSH PRIVATE KEY"))
            assertTrue(type.name, key.publicKey.endsWith(" phone"))
            // the stored private key gives back the same public key
            val again = SshKeys.publicKey(key.privateKey, null, "phone") as SshKeys.PublicKey.Ok
            assertEquals(type.name, key.publicKey, again.line)
            assertEquals(type.name, key.fingerprint, again.fingerprint)
        }
        assertTrue(SshKeys.generate(SshKeys.Type.ED25519, "x").publicKey.startsWith("ssh-ed25519 "))
        assertTrue(SshKeys.generate(SshKeys.Type.RSA, "x").publicKey.startsWith("ssh-rsa "))
        assertTrue(SshKeys.generate(SshKeys.Type.ECDSA, "x").publicKey.startsWith("ecdsa-sha2-nistp256 "))
    }

    @Test
    fun protectedPemKeyNeedsPassphrase() {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val pem = ByteArrayOutputStream().also { pair.writePrivateKey(it, "secret".toByteArray()) }.toString("US-ASCII")
        val expected = ByteArrayOutputStream().also { pair.writePublicKey(it, "c") }.toString("US-ASCII").trim()
        assertEquals(SshKeys.PublicKey.NeedsPassphrase, SshKeys.publicKey(pem, null, "c"))
        assertEquals(SshKeys.PublicKey.NeedsPassphrase, SshKeys.publicKey(pem, "wrong", "c"))
        assertEquals(expected, (SshKeys.publicKey(pem, "secret", "c") as SshKeys.PublicKey.Ok).line)
    }

    @Test
    fun protectedOpenSshKeyShowsPublicPartWithoutPassphrase() {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val key = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, "secret".toByteArray()) }.toString("US-ASCII")
        val expected = ByteArrayOutputStream().also { pair.writePublicKey(it, "c") }.toString("US-ASCII").trim()
        assertEquals(expected, (SshKeys.publicKey(key, null, "c") as SshKeys.PublicKey.Ok).line)
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(SshKeys.PublicKey.Invalid, SshKeys.publicKey("not a key", null, "c"))
    }
}
