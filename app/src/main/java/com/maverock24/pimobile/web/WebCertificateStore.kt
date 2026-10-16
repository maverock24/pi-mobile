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
     * certificate that names them all is reused as-is, so the fingerprint the
     * human already accepted does not move. A stored certificate that does not
     * name every current address is re-signed: the app is about to tell the human
     * to open those addresses, and a browser matches the URL against the
     * certificate's names, so a certificate that has gone stale would add a name
     * mismatch on top of the self-signed warning. Re-issuing keeps the keystore
     * key (hardware-backed where the device has it) and changes only the
     * fingerprint, which the Settings screen reads fresh each start.
     */
    fun load(context: Context, subjectAltIps: List<String>): WebCertificate {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val certFile = File(File(context.filesDir, CERT_DIR), CERT_FILE)
        if (keyStore.containsAlias(ALIAS)) {
            val stored = if (certFile.isFile) readCertificate(certFile) else null
            if (stored != null && isUsable(stored) && addressesNotNamed(stored, subjectAltIps).isEmpty()) {
                return WebCertificate(keyStore, ALIAS, stored, fingerprintOf(stored))
            }
            // The key is kept and a fresh certificate is signed over it with every
            // current address named. The alternative, reusing the stale names, is
            // what made the browser warn about an address it could not match.
            val certificate = reissue(keyStore, certFile, stored, subjectAltIps)
            return WebCertificate(keyStore, ALIAS, certificate, fingerprintOf(certificate))
        }
        // No key yet, so build both together; the pair always belongs together.
        val certificate = generate(keyStore, certFile, subjectAltIps)
        return WebCertificate(keyStore, ALIAS, certificate, fingerprintOf(certificate))
    }

    /**
     * Signs a new certificate over the existing keystore key, naming
     * [subjectAltIps]. The public key comes from the stored certificate because
     * an Android keystore entry holds a key and not a certificate. Falls back to
     * [generate] when there is no key, no stored certificate, or the key does not
     * actually match it, which is the only case where the pair has to be rebuilt.
     */
    private fun reissue(
        keyStore: KeyStore,
        certFile: File,
        stored: X509Certificate?,
        subjectAltIps: List<String>,
    ): X509Certificate {
        val publicKey = stored?.publicKey ?: return generate(keyStore, certFile, subjectAltIps)
        val privateKey = runCatching { keyStore.getKey(ALIAS, null) as? PrivateKey }.getOrNull()
            ?: return generate(keyStore, certFile, subjectAltIps)
        val keyPair = KeyPair(publicKey, privateKey)
        val now = System.currentTimeMillis()
        val certificate = assembleSelfSigned(
            keyPair = keyPair,
            commonName = COMMON_NAME,
            notBefore = Date(now - ONE_DAY_MS),
            notAfter = Date(now + TEN_YEARS_MS),
            subjectAltIps = subjectAltIps,
        )
        // If the keystore key is not the one the stored certificate's public key
        // belongs to, a browser cannot complete the handshake with this
        // certificate, so rebuild the pair instead of serving it.
        return runCatching {
            certificate.verify(publicKey)
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
                .setDigests(KeyProperties.DIGEST_SHA256)
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

    private fun isUsable(certificate: X509Certificate): Boolean =
        runCatching { certificate.checkValidity() }.isSuccess
}
