package com.airgrab.core

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Authentication, including the machine-in-the-middle cases.
 *
 * These mirror `desktop/tests/test_auth.py`. Asserting them twice is
 * deliberate: this is the code that decides whether a stranger on the same
 * Wi-Fi can impersonate the user's laptop, and a port that quietly disagrees
 * with the reference implementation would still pass a test suite written
 * only against itself.
 *
 * BouncyCastle appears only here. Generating a self-signed certificate is the
 * one operation with no portable JCA API, and on Android it is done through
 * the hardware-backed Keystore instead — so BouncyCastle stays a test
 * dependency and never enters the APK.
 */
class AuthTest {

    private class TestIdentity(override val displayName: String) : DeviceIdentity {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        override val certDer: ByteArray = run {
            val name = X500Name("CN=$displayName")
            val now = Date()
            val until = Date(now.time + 365L * 24 * 3600 * 1000)
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(64, SecureRandom()),
                now,
                until,
                name,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder(Certificates.SIGNATURE_ALGORITHM)
                .setProvider(BouncyCastleProvider())
                .build(keyPair.private)
            JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider())
                .getCertificate(builder.build(signer))
                .encoded
        }

        override val fingerprint: String = Sas.fingerprintOf(certDer)

        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance(Certificates.SIGNATURE_ALGORITHM).run {
                initSign(keyPair.private)
                update(data)
                sign()
            }
    }

    private val server = TestIdentity("SERVER")
    private val client = TestIdentity("CLIENT")

    // ------------------------------------------------------------ basic shape

    @Test
    fun `nonce is sixty-four hex characters and unique`() {
        val a = Auth.newNonce()
        val b = Auth.newNonce()
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a != b)
    }

    @Test
    fun `a fingerprint is derived from the certificate not declared`() {
        assertEquals(Sas.fingerprintOf(client.certDer), client.fingerprint)
        assertEquals(64, client.fingerprint.length)
    }

    @Test
    fun `a signature round trips`() {
        val payload = Auth.signingPayload(Auth.newNonce(), server.fingerprint, client.fingerprint)
        assertTrue(Certificates.verifySignature(client.certDer, payload, client.sign(payload)))
    }

    @Test
    fun `a garbage certificate verifies nothing`() {
        assertFalse(
            Certificates.verifySignature("not a certificate".toByteArray(), byteArrayOf(1), byteArrayOf(2))
        )
    }

    // ---------------------------------------------------- authenticating a client

    @Test
    fun `a valid response is accepted`() {
        val nonce = Auth.newNonce()
        val signature = client.sign(
            Auth.signingPayload(nonce, server.fingerprint, client.fingerprint)
        )
        val result = Auth.verifyAuthResponse(
            client.certDer, signature, nonce, server.fingerprint, client.fingerprint
        )
        assertTrue(result.ok, result.reason)
    }

    @Test
    fun `a wrong nonce is rejected`() {
        val signature = client.sign(
            Auth.signingPayload(Auth.newNonce(), server.fingerprint, client.fingerprint)
        )
        val result = Auth.verifyAuthResponse(
            client.certDer, signature, Auth.newNonce(), server.fingerprint, client.fingerprint
        )
        assertFalse(result.ok)
        assertEquals("bad_signature", result.reason)
    }

    @Test
    fun `a signature bound to another server does not replay`() {
        val nonce = Auth.newNonce()
        val elsewhere = "9".repeat(64)
        val signature = client.sign(Auth.signingPayload(nonce, elsewhere, client.fingerprint))
        val result = Auth.verifyAuthResponse(
            client.certDer, signature, nonce, server.fingerprint, client.fingerprint
        )
        assertFalse(result.ok)
        assertEquals("bad_signature", result.reason)
    }

    @Test
    fun `claiming someone else's identity is rejected`() {
        val nonce = Auth.newNonce()
        val lie = "7".repeat(64)
        val signature = client.sign(Auth.signingPayload(nonce, server.fingerprint, lie))
        val result = Auth.verifyAuthResponse(
            client.certDer, signature, nonce, server.fingerprint, lie
        )
        assertFalse(result.ok)
        assertEquals("identity_mismatch", result.reason)
    }

    // ---------------------------------------------------- authenticating a server

    @Test
    fun `server identity comes from its certificate`() {
        val nonce = Auth.newNonce()
        val signature = server.sign(
            Auth.signingPayload(nonce, server.fingerprint, client.fingerprint)
        )
        val (result, derived) = Auth.verifyServerIdentity(
            server.certDer, signature, nonce, client.fingerprint, pinnedFp = null
        )
        assertTrue(result.ok, result.reason)
        assertEquals(server.fingerprint, derived)
    }

    @Test
    fun `a pinned fingerprint must match`() {
        val nonce = Auth.newNonce()
        val signature = server.sign(
            Auth.signingPayload(nonce, server.fingerprint, client.fingerprint)
        )
        val (result, _) = Auth.verifyServerIdentity(
            server.certDer, signature, nonce, client.fingerprint, pinnedFp = "9".repeat(64)
        )
        assertFalse(result.ok)
        assertEquals("pin_mismatch", result.reason)
    }

    @Test
    fun `an impostor cannot impersonate a pinned server`() {
        // The core case: the attacker holds its own key and controls the
        // address, but cannot produce the real server's certificate.
        val attacker = TestIdentity("MITM")
        val nonce = Auth.newNonce()
        val signature = attacker.sign(
            Auth.signingPayload(nonce, attacker.fingerprint, client.fingerprint)
        )
        val (result, derived) = Auth.verifyServerIdentity(
            attacker.certDer, signature, nonce, client.fingerprint, pinnedFp = server.fingerprint
        )
        assertFalse(result.ok)
        assertEquals("pin_mismatch", result.reason)
        assertEquals(attacker.fingerprint, derived)
    }

    @Test
    fun `an impostor cannot forge a signature for a stolen certificate`() {
        // Certificates are public, so an attacker can present the real one.
        // Without the private key the signature cannot be produced.
        val attacker = TestIdentity("MITM")
        val nonce = Auth.newNonce()
        val signature = attacker.sign(
            Auth.signingPayload(nonce, server.fingerprint, client.fingerprint)
        )
        val (result, _) = Auth.verifyServerIdentity(
            server.certDer, signature, nonce, client.fingerprint, pinnedFp = server.fingerprint
        )
        assertFalse(result.ok)
        assertEquals("bad_signature", result.reason)
    }

    @Test
    fun `a machine in the middle shows a different pairing code on each side`() {
        // Before trust exists there is no pin, so the six-digit comparison is
        // the only defence. An attacker must present its own certificate to
        // each side, which yields two different codes.
        val attacker = TestIdentity("MITM")
        val clientSide = Sas.compute(client.fingerprint, attacker.fingerprint)
        val serverSide = Sas.compute(server.fingerprint, attacker.fingerprint)
        val honest = Sas.compute(client.fingerprint, server.fingerprint)

        assertTrue(clientSide != serverSide)
        assertTrue(clientSide != honest)
        assertTrue(serverSide != honest)
    }
}
