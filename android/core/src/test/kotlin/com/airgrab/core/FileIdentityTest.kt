package com.airgrab.core

import java.io.File
import java.nio.file.Files
import java.security.interfaces.ECPublicKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The identity must survive restarts and match the desktop's cryptography.
 *
 * A device whose fingerprint changes between launches would appear as a
 * brand-new stranger every time the app opened, and every pairing the user
 * had made would silently stop working. That failure looks like a networking
 * bug from the outside, so it is pinned here.
 */
class FileIdentityTest {

    private val temp: File = Files.createTempDirectory("airgrab-identity").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the key is P-256, as the protocol requires`() {
        val identity = FileIdentity.loadOrCreate(temp, "TEST")
        val certificate = identity.keyStore.getCertificate(identity.keyAlias)
        val key = certificate.publicKey

        assertEquals("EC", key.algorithm)
        // Ktor's own helper defaults to RSA, which would still handshake and
        // still sign, and would fail only when the desktop tried to verify.
        assertEquals(256, (key as ECPublicKey).params.order.bitLength())
    }

    @Test
    fun `the fingerprint is derived from the certificate`() {
        val identity = FileIdentity.loadOrCreate(temp, "TEST")
        assertEquals(Sas.fingerprintOf(identity.certDer), identity.fingerprint)
        assertEquals(64, identity.fingerprint.length)
    }

    @Test
    fun `the identity survives a restart`() {
        val first = FileIdentity.loadOrCreate(temp, "TEST")
        val second = FileIdentity.loadOrCreate(temp, "TEST")

        assertEquals(first.fingerprint, second.fingerprint)
        assertContentEquals(first.certDer, second.certDer)
    }

    @Test
    fun `a different data directory is a different device`() {
        val other = Files.createTempDirectory("airgrab-identity-b").toFile()
        try {
            assertNotEquals(
                FileIdentity.loadOrCreate(temp, "TEST").fingerprint,
                FileIdentity.loadOrCreate(other, "TEST").fingerprint,
            )
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `signatures verify against the certificate`() {
        val identity = FileIdentity.loadOrCreate(temp, "TEST")
        val payload = Auth.signingPayload(Auth.newNonce(), "a".repeat(64), identity.fingerprint)

        assertTrue(
            Certificates.verifySignature(identity.certDer, payload, identity.sign(payload))
        )
    }

    @Test
    fun `a signature over different data does not verify`() {
        val identity = FileIdentity.loadOrCreate(temp, "TEST")
        val signature = identity.sign("one".toByteArray())

        assertTrue(
            !Certificates.verifySignature(identity.certDer, "two".toByteArray(), signature)
        )
    }

    @Test
    fun `a corrupt keystore is replaced rather than fatal`() {
        val original = FileIdentity.loadOrCreate(temp, "TEST").fingerprint
        File(temp, "identity.p12").writeText("this is not a keystore")

        // Refusing to start would leave the user with an app that never opens
        // again. Losing the pairings is recoverable; a dead app is not.
        val recovered = FileIdentity.loadOrCreate(temp, "TEST")

        assertEquals(64, recovered.fingerprint.length)
        assertNotEquals(original, recovered.fingerprint)
    }

    @Test
    fun `no half-written keystore is left behind`() {
        FileIdentity.loadOrCreate(temp, "TEST")
        val leftovers = temp.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".new") }
        assertTrue(leftovers.isEmpty(), "temporary keystore files remain: $leftovers")
    }

    @Test
    fun `the display name is cosmetic and does not change identity`() {
        val first = FileIdentity.loadOrCreate(temp, "PHONE").fingerprint
        val second = FileIdentity.loadOrCreate(temp, "RENAMED")

        // Identity is the certificate. A user renaming their device must not
        // have to pair everything again.
        assertEquals(first, second.fingerprint)
        assertEquals("RENAMED", second.displayName)
    }
}
