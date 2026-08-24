package com.airgrab.core

import java.security.MessageDigest

/**
 * Pairing-code derivation, ported from the Python reference implementation.
 *
 * This must agree with `desktop/airgrab/protocol.py` byte for byte. It is not
 * "similar to" it: a device that computes a different six-digit code cannot
 * pair with anything, and the failure looks like a mysterious mismatch rather
 * than a bug. `protocol/vectors/sas.json` exists so that drift is caught by a
 * unit test on this side rather than by two people staring at two screens.
 *
 * See `protocol/PROTOCOL.md` for the authoritative definition.
 */
object Sas {

    const val DOMAIN = "airgrab-auth-v1"

    /** The six-digit code shown on both devices during pairing. */
    fun compute(fingerprintA: String, fingerprintB: String): String {
        // Sorting makes the result independent of who initiated the pairing.
        val joined = listOf(fingerprintA, fingerprintB).sorted().joinToString("")
        val digest = sha256(joined.toByteArray(Charsets.UTF_8))

        // First four bytes, big-endian, modulo one million. Kotlin's Byte is
        // signed, so each byte is masked back to 0..255 before shifting;
        // without the mask any byte over 0x7F would poison the whole value.
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    /**
     * The payload each side signs to prove it holds its private key.
     *
     * Binding the nonce to BOTH fingerprints is what stops a captured
     * signature being replayed against a different peer.
     */
    fun signingPayload(nonce: String, serverFp: String, clientFp: String): ByteArray =
        sha256("$DOMAIN$nonce$serverFp$clientFp".toByteArray(Charsets.UTF_8))

    /** Lowercase hex SHA-256 of a DER-encoded certificate: a device's identity. */
    fun fingerprintOf(certDer: ByteArray): String =
        sha256(certDer).joinToString("") { "%02x".format(it) }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
