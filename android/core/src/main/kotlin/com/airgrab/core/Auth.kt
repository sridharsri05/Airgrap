package com.airgrab.core

import java.security.SecureRandom

/**
 * Proof of key possession over an unauthenticated TLS channel.
 *
 * Ported from `desktop/airgrab/auth.py`, and the port matters: if the two
 * implementations disagree about what gets signed, every pairing between a
 * phone and a PC fails with nothing to indicate why.
 *
 * TLS here provides confidentiality but not identity — certificates are
 * self-signed and no CA vouches for them. Each side instead proves it holds
 * the private key behind the fingerprint it claims, by signing a nonce chosen
 * by the other and bound to BOTH fingerprints. That binding is what stops a
 * captured signature being replayed against a different peer.
 *
 * The rule an earlier revision of the design got wrong, and which is the whole
 * reason this file exists in this shape: **a peer's fingerprint is derived
 * from the certificate it presented, never read from a message field.** A
 * message field is a claim; a signature over a certificate is proof. The
 * original scheme looked up the pin using the fingerprint the peer declared,
 * so an attacker could skip the check entirely by declaring one that was not
 * in the trust store.
 */

private const val NONCE_BYTES = 32

data class AuthResult(val ok: Boolean, val reason: String = "")

object Auth {

    private val random = SecureRandom()

    /** 32 random bytes, hex-encoded: 64 characters. */
    fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** What both sides sign. Identical to Python's `signing_payload`. */
    fun signingPayload(nonce: String, serverFp: String, clientFp: String): ByteArray =
        Sas.signingPayload(nonce, serverFp, clientFp)

    /**
     * Server side: authenticate the connecting client.
     *
     * Checks the signature first and the identity second, so a garbage
     * certificate fails as `bad_signature` rather than revealing which test
     * ran first. Both are rejections; the distinction only matters in logs.
     */
    fun verifyAuthResponse(
        certDer: ByteArray,
        signature: ByteArray,
        nonce: String,
        serverFp: String,
        claimedClientFp: String,
    ): AuthResult {
        val actualFp = try {
            Certificates.fingerprintOf(certDer)
        } catch (_: Exception) {
            return AuthResult(false, "bad_certificate")
        }

        val payload = signingPayload(nonce, serverFp, claimedClientFp)
        if (!Certificates.verifySignature(certDer, payload, signature)) {
            return AuthResult(false, "bad_signature")
        }

        if (actualFp != claimedClientFp) {
            return AuthResult(false, "identity_mismatch")
        }

        return AuthResult(true)
    }

    /**
     * Client side: authenticate the server, returning its DERIVED fingerprint.
     *
     * The returned fingerprint comes from the certificate the server actually
     * presented, never from a message field, because a message field can
     * simply be set to the victim's fingerprint by an attacker in the middle.
     *
     * [pinnedFp] is the fingerprint already recorded in the trust store, or
     * null on a first-time pairing. With a pin, a mismatch is fatal. Without
     * one, the caller must feed the returned fingerprint into the pairing code
     * so that a machine-in-the-middle shows different digits on the two
     * screens — that comparison is the only defence available before trust
     * exists.
     */
    fun verifyServerIdentity(
        certDer: ByteArray,
        signature: ByteArray,
        nonce: String,
        clientFp: String,
        pinnedFp: String?,
    ): Pair<AuthResult, String> {
        val derivedFp = try {
            Certificates.fingerprintOf(certDer)
        } catch (_: Exception) {
            return AuthResult(false, "bad_certificate") to ""
        }

        val payload = signingPayload(nonce, derivedFp, clientFp)
        if (!Certificates.verifySignature(certDer, payload, signature)) {
            return AuthResult(false, "bad_signature") to derivedFp
        }

        if (pinnedFp != null && derivedFp != pinnedFp) {
            return AuthResult(false, "pin_mismatch") to derivedFp
        }

        return AuthResult(true) to derivedFp
    }
}
