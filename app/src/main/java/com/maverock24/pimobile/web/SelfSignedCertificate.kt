package com.maverock24.pimobile.web

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The certificate the phone serves. This file has no Android imports on purpose:
 * the assembly and the fingerprint formatting are plain JVM work, so they are
 * unit tested on the build machine instead of waiting for a phone.
 */

/**
 * ECDSA over NIST P-256 with SHA-256. RFC 8446 section 9.1 makes P-256 and
 * `ecdsa_secp256r1_sha256` mandatory for a TLS-compliant application, so both
 * Chrome and Firefox accept it. EC over RSA keeps the handshake cheap on a
 * low-end phone, which is the point of choosing it.
 */
internal const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
internal const val CURVE = "secp256r1"

/**
 * The signature algorithm the TLS stack asks the keystore key for. On Android the
 * handshake does not sign a hash it computed itself: it hands the transcript digest
 * to the key as a raw, NONE-digest ECDSA signature.
 */
private const val RAW_DIGEST_SIGNATURE = "NONEwithECDSA"

/**
 * The SHA-256 fingerprint as the colon-separated uppercase hex the browser
 * warning shows. Bytes are masked before formatting: `%02X` on a negative
 * [Byte] sign-extends and would print eight digits for half the input.
 */
internal fun formatFingerprint(digest: ByteArray): String =
    digest.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

/** The fingerprint of a certificate, i.e. the SHA-256 of its DER encoding. */
internal fun fingerprintOf(certificate: X509Certificate): String =
    formatFingerprint(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

/**
 * The addresses a certificate names as IP subject alternative names. A browser
 * matches the address in the URL against these, so a served certificate that
 * does not name an address the app tells the human to open makes the browser
 * warn about a name mismatch on top of the self-signed warning. The type tag is
 * GeneralName.iPAddress (7), which is how Java exposes an IP SAN; a DNS name in
 * the same list is not an address and is left out.
 */
internal fun subjectAltIpsOf(certificate: X509Certificate): Set<String> =
    runCatching { certificate.subjectAlternativeNames }.getOrNull()
        .orEmpty()
        .filter { it.size >= 2 && it[0] == GeneralName.iPAddress }
        .mapNotNull { it[1] as? String }
        .toSet()

/**
 * The addresses in [addresses] that [certificate] does not name as an IP SAN,
 * in the order they were asked for. The list is empty when the certificate
 * covers every address, which is the only case where reusing it is safe.
 */
internal fun addressesNotNamed(certificate: X509Certificate, addresses: List<String>): List<String> {
    val named = subjectAltIpsOf(certificate)
    return addresses.filterNot { it in named }
}

/**
 * What to do with the certificate material found in the keystore and in app
 * storage. The key and the certificate are regenerated apart, so which one is
 * stale decides whether the key survives.
 */
internal enum class CertificateDisposition { REUSE, REISSUE, REGENERATE }

/** The fixed bytes the key-match check signs; only that the same bytes are signed and checked matters. */
private val MATCH_CHALLENGE = "pi-remote-web-access key match".toByteArray(Charsets.UTF_8)

/**
 * Whether [privateKey] is the key [certificate] was issued for.
 *
 * The private key lives in the keystore and the certificate lives in app storage,
 * so the two can come apart: a keystore reset, or a key remade while the stored
 * certificate stayed behind, leaves a pair that presents one key and signs with
 * another. TLS only discovers that during the handshake, and then only as a close
 * with no certificate shown. The check signs a fixed challenge and verifies it
 * under the certificate's own public key, which is the property the handshake
 * needs. A key that cannot sign at all, such as one made with a digest it was not
 * given, fails here rather than at handshake time.
 *
 * The signer is provider-less on purpose, like the certificate assembly, because
 * only the platform's own [Signature] can use an Android keystore key.
 */
internal fun certificateMatchesKey(certificate: X509Certificate, privateKey: PrivateKey): Boolean =
    runCatching {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(MATCH_CHALLENGE)
        val signed = signer.sign()
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(certificate.publicKey)
        verifier.update(MATCH_CHALLENGE)
        verifier.verify(signed)
    }.getOrDefault(false)

/**
 * Whether [privateKey] can make the raw-digest signature the TLS handshake asks
 * for. An Android keystore key is only usable with the digests it was created
 * with, so a key made without `DIGEST_NONE` is refused during the handshake, after
 * the listener is already up, and the connection closes with no certificate shown.
 * The failure is invisible from the app otherwise, because the TLS engine swallows
 * the keystore's own exception, so the property is checked when the pair is chosen
 * and again before the socket binds.
 *
 * Every provider that offers the algorithm is tried, because the TLS stack does
 * the same: the first provider may be the TLS engine, which cannot use a
 * non-exportable keystore key, and a later provider can.
 */
internal fun keySignsRawDigest(privateKey: PrivateKey): Boolean {
    val digest = MessageDigest.getInstance("SHA-256").digest(MATCH_CHALLENGE)
    return Security.getProviders("Signature.$RAW_DIGEST_SIGNATURE").any { provider ->
        runCatching {
            val signer = Signature.getInstance(RAW_DIGEST_SIGNATURE, provider)
            signer.initSign(privateKey)
            signer.update(digest)
            signer.sign()
            true
        }.getOrDefault(false)
    }
}

/**
 * Chooses between reusing the stored certificate, re-signing it over the same
 * keystore key, and rebuilding both.
 *
 * A missing key, an expired certificate, or a certificate that no longer belongs
 * to the key is a pair that has to be rebuilt, because re-signing over the key
 * would still carry the other key's public key. Only a name that has moved is
 * re-signed over the same key. A key that cannot make the raw-digest signature TLS
 * needs is rebuilt too, which is how a key made before the digest set was corrected
 * is replaced on the next start instead of being reused into a listener that
 * accepts every connection and then hangs up.
 *
 * [keyCanServeTls] is a parameter so the decision is tested without a device: the
 * Android keystore is the only place a key with the wrong digests exists, and it
 * cannot be built on the build machine.
 */
internal fun certificateDisposition(
    stored: X509Certificate?,
    privateKey: PrivateKey?,
    addresses: List<String>,
    keyCanServeTls: (PrivateKey) -> Boolean = ::keySignsRawDigest,
): CertificateDisposition {
    if (stored == null || privateKey == null) return CertificateDisposition.REGENERATE
    if (runCatching { stored.checkValidity() }.isFailure) return CertificateDisposition.REGENERATE
    if (!certificateMatchesKey(stored, privateKey)) return CertificateDisposition.REGENERATE
    if (!keyCanServeTls(privateKey)) return CertificateDisposition.REGENERATE
    if (addressesNotNamed(stored, addresses).isNotEmpty()) return CertificateDisposition.REISSUE
    return CertificateDisposition.REUSE
}

/**
 * Builds a self-signed certificate for [keyPair], signed by its own private key.
 *
 * The X.509 structure is BouncyCastle's, because Android has no public API that
 * builds one. The signing is deliberately provider-less: on the phone the
 * private key lives in the Android keystore and only the platform's own
 * `Signature` implementation can use it, so naming the BouncyCastle provider
 * here would break the very case this exists for.
 */
internal fun assembleSelfSigned(
    keyPair: KeyPair,
    commonName: String,
    notBefore: Date,
    notAfter: Date,
    subjectAltIps: List<String>,
): X509Certificate {
    val subject = X500Principal("CN=$commonName")
    // A real issuer uses a random serial; here it only has to be unique, but
    // there is no reason to hard-code one.
    val serial = BigInteger(64, SecureRandom())
    val builder = JcaX509v3CertificateBuilder(
        subject,
        serial,
        notBefore,
        notAfter,
        subject,
        keyPair.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    // An ECDSA key signs and does not encipher, so digitalSignature is the whole
    // usable key usage.
    builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
    if (subjectAltIps.isNotEmpty()) {
        // A bare address is its own subject alternative name type. The browser
        // warns about a self-signed certificate anyway, but naming every address
        // the phone might be reached on keeps the warning from also being about a
        // name mismatch whichever one the human types.
        val names = GeneralNames(subjectAltIps.map { GeneralName(GeneralName.iPAddress, it) }.toTypedArray())
        builder.addExtension(Extension.subjectAlternativeName, false, names)
    }
    val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
}
