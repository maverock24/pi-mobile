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
import java.security.SecureRandom
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
