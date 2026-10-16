package com.maverock24.pimobile.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * The key and the certificate the web server speaks TLS with.
 *
 * The private key is the secret, so it is generated once into the Android
 * keystore under [WebCertificateStore.ALIAS] and never leaves it: there is no key
 * file to leak, back up or copy, and hardware backing is used automatically where
 * the device has it. The certificate is public, so it is written to the app's own
 * storage and reloaded on the next run. The fingerprint the human compares is the
 * SHA-256 of exactly those bytes.
 */
class WebCertificate internal constructor(
    val keyStore: KeyStore,
    val alias: String,
    val certificate: X509Certificate,
    val fingerprint: String,
)

object WebCertificateStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "pi-remote-web-access"
    private const val CERT_DIR = "web-access"
    private const val CERT_FILE = "cert.der"
    private const val COMMON_NAME = "Pi Remote web access"
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

    /**
     * Returns the certificate to serve, generating both the keystore key and the
     * certificate when either is missing.
     *
     * [subjectAltIps] are the addresses the phone is on right now. A stored
     * certificate that names them all and belongs to the keystore key is reused
     * as-is, so the fingerprint the human already accepted does not move. A stored
     * certificate that does not name every current address is re-signed over the
     * same key: the app is about to tell the human to open those addresses, and a
     * browser matches the URL against the certificate's names. A stored
     * certificate that no longer belongs to the keystore key, or a key that cannot
     * sign, is discarded along with the key and rebuilt, because such a pair
     * completes no handshake and only fails per-connection.
     */
    fun load(context: Context, subjectAltIps: List<String>): WebCertificate {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val certFile = File(File(context.filesDir, CERT_DIR), CERT_FILE)
        val stored = if (certFile.isFile) readCertificate(certFile) else null
        val key = readKey(keyStore)
        // The disposition is the one place the three cases are decided, so the
        // choice is testable without a phone: the match check is what stops a
        // keystore that was reset or a key remade apart from the certificate from
        // being served.
        val certificate = when (certificateDisposition(stored, key, subjectAltIps)) {
            CertificateDisposition.REUSE -> requireNotNull(stored)
            CertificateDisposition.REISSUE ->
                reissue(keyStore, requireNotNull(stored), requireNotNull(key), certFile, subjectAltIps)
            CertificateDisposition.REGENERATE -> generate(keyStore, certFile, subjectAltIps)
        }
        return WebCertificate(keyStore, ALIAS, certificate, fingerprintOf(certificate))
    }

    /** The keystore key, or null when the alias is missing or the entry cannot be read. */
    private fun readKey(keyStore: KeyStore): PrivateKey? =
        runCatching { keyStore.getKey(ALIAS, null) as? PrivateKey }.getOrNull()

    /**
     * Signs a new certificate over [key] with every address named. The public key
     * comes from [stored] because an Android keystore entry holds a key and not a
     * certificate, and the caller has already proved the two belong together. If
     * signing or writing fails anyway, the pair is rebuilt rather than left half
     * written.
     */
    private fun reissue(
        keyStore: KeyStore,
        stored: X509Certificate,
        key: PrivateKey,
        certFile: File,
        subjectAltIps: List<String>,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        return runCatching {
            val certificate = assembleSelfSigned(
                keyPair = KeyPair(stored.publicKey, key),
                commonName = COMMON_NAME,
                notBefore = Date(now - ONE_DAY_MS),
                notAfter = Date(now + TEN_YEARS_MS),
                subjectAltIps = subjectAltIps,
            )
            certFile.parentFile?.mkdirs()
            certFile.writeBytes(certificate.encoded)
            certificate
        }.getOrElse { generate(keyStore, certFile, subjectAltIps) }
    }

    private fun generate(keyStore: KeyStore, certFile: File, subjectAltIps: List<String>): X509Certificate {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                // SHA-256 is what this app signs the certificate and the key-match
                // challenge with. NONE is what the TLS stack asks for: on Android the
                // handshake hands its own digest to the key as a NONEwithECDSA
                // signature, and a keystore key is only usable with the digests it was
                // created with. Leaving NONE out fails every handshake after the
                // listener is up, with no certificate sent, which a browser shows only
                // as an empty response.
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .build(),
        )
        val keyPair: KeyPair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val certificate = assembleSelfSigned(
            keyPair = keyPair,
            commonName = COMMON_NAME,
            notBefore = Date(now - ONE_DAY_MS),
            notAfter = Date(now + TEN_YEARS_MS),
            subjectAltIps = subjectAltIps,
        )
        certFile.parentFile?.mkdirs()
        certFile.writeBytes(certificate.encoded)
        return certificate
    }

    private fun readCertificate(file: File): X509Certificate? = runCatching {
        file.inputStream().use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream) as X509Certificate
        }
    }.getOrNull()
}
