package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.result.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeysTest {
    private fun res(name: String) = javaClass.getResource("/keys/$name")!!.readText()

    private fun expectedFp(name: String) = res("$name.fp").split(" ")[1]

    private fun import(
        name: String,
        pass: String? = null,
    ) = SshKeys.import(res(name).toCharArray(), pass?.toCharArray())

    @Test
    fun `generated key serializes parses and keeps fingerprint`() {
        val k = SshKeys.generateEd25519("me@phone")
        val pem = String(k.privateKeyPem)
        assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(k.publicKeyOpenSsh.startsWith("ssh-ed25519 AAAA") && k.publicKeyOpenSsh.endsWith(" me@phone"))
        val back = (import(k.privateKeyPem, null) as AppResult.Success).value
        assertEquals(k.fingerprintSha256, back.fingerprintSha256)
        assertEquals(k.publicKeyOpenSsh.split(" ").take(2), back.publicKeyOpenSsh.split(" ").take(2))
    }

    private fun import(
        pem: CharArray,
        pass: CharArray?,
    ) = SshKeys.import(pem, pass)

    @Test
    fun `fingerprints match ssh-keygen for ed25519 ecdsa and rsa`() {
        for (name in listOf("ed25519", "ecdsa", "rsa")) {
            val k = (import(name) as AppResult.Success).value
            assertEquals(name, expectedFp(name), k.fingerprintSha256)
            assertEquals(res("$name.pub").split(" ").take(2), k.publicKeyOpenSsh.split(" ").take(2))
        }
    }

    @Test
    fun `normalized imports re-parse to the same fingerprint`() {
        for (name in listOf("ecdsa", "rsa", "ed25519")) {
            val k = (import(name) as AppResult.Success).value
            val r = SshKeys.import(k.privateKeyPem, null)
            val again = (r as? AppResult.Success)?.value ?: error("$name: $r")
            assertEquals(name, k.fingerprintSha256, again.fingerprintSha256)
        }
    }

    @Test
    fun `encrypted key needs the right passphrase`() {
        val ok = (import("ed25519_enc", "secret") as AppResult.Success).value
        assertEquals(expectedFp("ed25519_enc"), ok.fingerprintSha256)
        assertTrue(import("ed25519_enc", "wrong") is AppResult.Failure)
        assertTrue(import("ed25519_enc") is AppResult.Failure)
        assertTrue(SshKeys.import("garbage".toCharArray(), null) is AppResult.Failure)
    }
}
