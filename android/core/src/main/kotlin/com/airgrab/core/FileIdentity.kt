package com.airgrab.core

import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * A device identity backed by a keystore file, created once and kept forever.
 *
 * One implementation serves both the JVM and Android. That is deliberate: a
 * separate Android path would be a second place for the certificate format to
 * drift, and a fingerprint that differs between the two would silently break
 * pairing with the desktop.
 *
 * ## Why not the hardware-backed Android Keystore
 *
 * A non-exportable hardware key is the stronger choice for signing, and for
 * signing alone it would work. It cannot be used here because this same key
 * also terminates TLS: the server needs a [KeyStore] entry it can hand to the
 * TLS stack, and a key that cannot leave secure hardware cannot be placed in
 * one. Splitting into two keys would mean two identities and two fingerprints
 * for one device, which the protocol has no room for.
 *
 * The file therefore lives in app-private storage, which on Android is
 * readable only by this application, and on Windows is restricted to the
 * owner. That is the same protection the desktop's key already has.
 *
 * ## Why the low-level builder rather than Ktor's convenience helper
 *
 * `generateCertificate` defaults to RSA and offers no way to request a curve.
 * The protocol is fixed to P-256 with SHA256withECDSA, matching
 * `desktop/airgrab/identity.py`, so the certificate is built through
 * `buildKeyStore`, where the curve can be stated explicitly.
 */
class FileIdentity private constructor(
    override val displayName: String,
    private val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    val keyStore: KeyStore,
    val keyAlias: String,
) : DeviceIdentity {

    /**
     * A FRESH array on every read, never the shared one.
     *
     * Ktor's TLS setup zeroes the password array once it has used it, which is
     * ordinary hygiene for key material and completely correct on its own.
     * Handing out the same array twice therefore breaks the second caller:
     * the first server starts, wipes it, and every later use sees a string of
     * null characters. The JDK reports that as "Password is not ASCII", which
     * points nowhere near the real cause.
     */
    val keyStorePassword: CharArray get() = PASSWORD.copyOf()

    override val certDer: ByteArray = certificate.encoded

    override val fingerprint: String = Sas.fingerprintOf(certDer)

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(Certificates.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    companion object {
        private const val ALIAS = "airgrab"
        private const val KEYSTORE_FILE = "identity.p12"

        /**
         * PKCS12 rather than JKS: JKS is deprecated on the JVM and absent from
         * some Android builds, while PKCS12 is the default on both.
         */
        private const val KEYSTORE_TYPE = "PKCS12"

        /**
         * The keystore password protects a file that is already restricted to
         * this application, so it guards against nothing an attacker with file
         * access could not already defeat. It is fixed rather than random
         * because a lost random password would mean a lost identity, and with
         * it every device pairing the user has made.
         *
         * Never passed to anything directly: see [keyStorePassword]. Callers
         * that zero their copy are the reason it exists as a template.
         */
        private const val PASSWORD_TEXT = "airgrab"

        private val PASSWORD: CharArray get() = PASSWORD_TEXT.toCharArray()

        fun loadOrCreate(dataDir: File, displayName: String): FileIdentity {
            dataDir.mkdirs()
            val file = File(dataDir, KEYSTORE_FILE)

            val store = if (file.exists()) {
                runCatching { load(file) }.getOrElse {
                    // A corrupt keystore is unrecoverable, and refusing to
                    // start would leave the user with an app that never opens
                    // again. Starting over costs them their pairings, which is
                    // recoverable, so the file is replaced.
                    file.delete()
                    create(file)
                }
            } else {
                create(file)
            }

            val certificate = store.getCertificate(ALIAS) as X509Certificate
            val privateKey = store.getKey(ALIAS, PASSWORD) as PrivateKey
            return FileIdentity(displayName, certificate, privateKey, store, ALIAS)
        }

        private fun load(file: File): KeyStore =
            KeyStore.getInstance(KEYSTORE_TYPE).apply {
                file.inputStream().use { load(it, PASSWORD) }
                // A keystore that opens but holds no key is as useless as a
                // corrupt one, and would fail much later and less clearly.
                requireNotNull(getKey(ALIAS, PASSWORD)) { "keystore has no identity" }
            }

        private fun create(file: File): KeyStore {
            val generated = buildKeyStore {
                certificate(ALIAS) {
                    password = PASSWORD_TEXT
                    // P-256 with SHA-256, matching desktop/airgrab/identity.py.
                    // The default is RSA, which would handshake and sign
                    // perfectly well and fail only when the desktop tried to
                    // verify a signature it could not parse.
                    sign = SignatureAlgorithm.ECDSA
                    hash = HashAlgorithm.SHA256
                    keySizeInBits = 256
                    keyType = KeyType.Server
                    subject = X500Principal("CN=AirGrab")
                    daysValid = 365L * 10
                    // The certificate is never validated by hostname — peers
                    // are self-signed and identity comes from the fingerprint
                    // — but a TLS stack may still object to a certificate with
                    // no subject alternative name at all.
                    domains = listOf("localhost")
                    ipAddresses = listOf(InetAddress.getByName("127.0.0.1"))
                }
            }

            // Re-homed into PKCS12 because buildKeyStore produces a JKS.
            val store = KeyStore.getInstance(KEYSTORE_TYPE).apply {
                load(null, PASSWORD)
                setKeyEntry(
                    ALIAS,
                    generated.getKey(ALIAS, PASSWORD),
                    PASSWORD,
                    generated.getCertificateChain(ALIAS),
                )
            }

            // Written to a temporary file and renamed, so an interrupted first
            // launch cannot leave a half-written keystore that later loads as
            // corrupt and silently resets the device's identity.
            val temp = File(file.parentFile, "${file.name}.new")
            temp.outputStream().use { store.store(it, PASSWORD) }
            restrictToOwner(temp)
            java.nio.file.Files.move(
                temp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            return store
        }

        /**
         * Remove group and world access where the platform supports it.
         *
         * Android already confines app-private storage, and Windows has no
         * POSIX permissions, so this is a no-op on both. It matters when the
         * same code runs on a shared Linux or macOS machine during
         * development, where the default would otherwise be world-readable.
         */
        private fun restrictToOwner(file: File) {
            runCatching {
                val path = file.toPath()
                val view = java.nio.file.Files.getFileAttributeView(
                    path, java.nio.file.attribute.PosixFileAttributeView::class.java
                ) ?: return
                view.setPermissions(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                )
            }
        }
    }
}
