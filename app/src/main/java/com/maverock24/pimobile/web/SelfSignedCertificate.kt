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
    subjectAltIp: String?,
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
    if (subjectAltIp != null) {
        // A bare address is its own subject alternative name type. The browser
        // warns about a self-signed certificate anyway, but naming the address
        // in the URL here keeps the warning from also being about a name
        // mismatch.
        val names = GeneralNames(GeneralName(GeneralName.iPAddress, subjectAltIp))
        builder.addExtension(Extension.subjectAlternativeName, false, names)
    }
    val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
}
