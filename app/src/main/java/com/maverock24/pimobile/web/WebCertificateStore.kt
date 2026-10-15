package com.maverock24.pimobile.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
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
     * [subjectAltIp] is the address the phone is on right now. It is only used
     * when a certificate is built: an existing one is kept even if the address
     * has since changed, because a stable fingerprint is worth more than a
     * name that matches, and the browser warns about the self-signed certificate
     * regardless.
     */
    fun load(context: Context, subjectAltIp: String?): WebCertificate {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val certFile = File(File(context.filesDir, CERT_DIR), CERT_FILE)
        if (keyStore.containsAlias(ALIAS) && certFile.isFile) {
            val stored = readCertificate(certFile)
            if (stored != null && isUsable(stored)) {
                return WebCertificate(keyStore, ALIAS, stored, fingerprintOf(stored))
            }
        }
        // The key was reset, the certificate is gone or unreadable, or it is no
        // longer valid. Build both again so the pair always belongs together.
        val certificate = generate(keyStore, certFile, subjectAltIp)
        return WebCertificate(keyStore, ALIAS, certificate, fingerprintOf(certificate))
    }

    private fun generate(keyStore: KeyStore, certFile: File, subjectAltIp: String?): X509Certificate {
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
            subjectAltIp = subjectAltIp,
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
