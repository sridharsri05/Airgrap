package com.airgrab.core

import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A device's permanent identity, and the verification of other devices'.
 *
 * Certificate *verification* lives here because it uses only plain JCA, which
 * behaves identically on a JVM and on Android. Certificate *generation* does
 * not: Android should mint its keypair inside the hardware-backed Keystore,
 * where the private key is non-exportable, while a plain JVM has no such
 * facility. Generation is therefore left to the platform and expressed here
 * only as an interface.
 *
 * That split is also what keeps this file unit-testable without a device.
 */

/** The identity of THIS device. Implemented per platform. */
interface DeviceIdentity {
    /** Lowercase hex SHA-256 of the DER certificate. The device's real name. */
    val fingerprint: String

    /** The DER-encoded self-signed certificate, sent to peers during auth. */
    val certDer: ByteArray

    /** Human-readable, cosmetic, and never trusted for identification. */
    val displayName: String

    /** Sign with the device private key: ECDSA over SHA-256. */
    fun sign(data: ByteArray): ByteArray
}

object Certificates {

    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Parse a DER certificate, or null if it is not one. */
    fun parse(certDer: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as? X509Certificate
    } catch (_: Exception) {
        null
    }

    /**
     * Verify an ECDSA-SHA256 signature against a certificate's public key.
     *
     * Returns false rather than throwing for every failure mode — malformed
     * certificate, malformed signature, wrong key. A caller deciding whether
     * to trust a peer should not have to distinguish between "the signature
     * was forged" and "the bytes were garbage": both mean no.
     */
    fun verifySignature(certDer: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val certificate = parse(certDer) ?: return false
        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(certificate.publicKey)
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** The fingerprint a certificate actually has, whatever its owner claims. */
    fun fingerprintOf(certDer: ByteArray): String = Sas.fingerprintOf(certDer)
}
