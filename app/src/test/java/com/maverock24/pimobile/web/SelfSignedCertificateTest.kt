package com.maverock24.pimobile.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Date

/**
 * The parts of the certificate work that run on a plain JVM. The keystore half
 * needs a device; this half is what a mistake in the fingerprint formatting or
 * the X.509 assembly would show up in.
 */
class SelfSignedCertificateTest {

    @Test
    fun `fingerprint is colon separated uppercase hex`() {
        val digest = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xAB.toByte(), 0xFF.toByte())
        assertEquals("00:01:7F:80:AB:FF", formatFingerprint(digest))
    }

    @Test
    fun `fingerprint of a certificate is the sha256 of its encoding`() {
        val certificate = assembleSelfSigned(
            keyPair = newP256KeyPair(),
            commonName = "Pi Remote web access",
            notBefore = Date(System.currentTimeMillis() - 60_000),
            notAfter = Date(System.currentTimeMillis() + 60_000),
            subjectAltIps = emptyList(),
        )
        val expected = formatFingerprint(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
        )
        assertEquals(expected, fingerprintOf(certificate))
    }

    @Test
    fun `assembled certificate is a self-signed p256 certificate`() {
        val keyPair = newP256KeyPair()
        val now = System.currentTimeMillis()
        val certificate = assembleSelfSigned(
            keyPair = keyPair,
            commonName = "Pi Remote web access",
            notBefore = Date(now - 60_000),
            notAfter = Date(now + 60_000),
            subjectAltIps = listOf("192.168.1.10", "192.168.43.1"),
        )

        certificate.checkValidity(Date(now))

        // Self-signed: the signature verifies under the certificate's own key.
        certificate.verify(keyPair.public)
        assertEquals("SHA256withECDSA", certificate.sigAlgName)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)

        val publicKey = certificate.publicKey as ECPublicKey
        val params = publicKey.params as ECParameterSpec
        assertEquals("P-256 has a 256 bit field", 256, params.curve.field.fieldSize)

        val alternativeNames = certificate.subjectAlternativeNames
        assertNotNull(alternativeNames)
        // Every candidate address is named, so the human can pick the one that
        // answers without the certificate warning also being about a name match.
        assertTrue(
            "the first candidate is an IP subject alternative name",
            alternativeNames!!.any { it.size >= 2 && it[0] == 7 && it[1] == "192.168.1.10" },
        )
        assertTrue(
            "the second candidate is an IP subject alternative name",
            alternativeNames.any { it.size >= 2 && it[0] == 7 && it[1] == "192.168.43.1" },
        )
    }

    private fun newP256KeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun `a certificate names every address it was built for`() {
        val certificate = assembleSelfSigned(
            keyPair = newP256KeyPair(),
            commonName = "Pi Remote web access",
            notBefore = Date(System.currentTimeMillis() - 60_000),
            notAfter = Date(System.currentTimeMillis() + 60_000),
            subjectAltIps = listOf("192.168.43.1", "10.0.0.2"),
        )
        assertEquals(setOf("192.168.43.1", "10.0.0.2"), subjectAltIpsOf(certificate))
        assertTrue(addressesNotNamed(certificate, listOf("192.168.43.1", "10.0.0.2")).isEmpty())
    }

    @Test
    fun `an address the certificate does not name is reported missing`() {
        val certificate = assembleSelfSigned(
            keyPair = newP256KeyPair(),
            commonName = "Pi Remote web access",
            notBefore = Date(System.currentTimeMillis() - 60_000),
            notAfter = Date(System.currentTimeMillis() + 60_000),
            subjectAltIps = listOf("192.168.1.10"),
        )
        // The hotspot address appeared after the certificate was built, which is
        // the case the store re-signs for rather than serving a stale name.
        assertEquals(listOf("192.168.43.1"), addressesNotNamed(certificate, listOf("192.168.43.1")))
        assertTrue(addressesNotNamed(certificate, listOf("192.168.1.10")).isEmpty())
    }

    @Test
    fun `the key match check accepts the key the certificate was made for`() {
        val keyPair = newP256KeyPair()
        val certificate = validCertificate(keyPair, listOf("192.168.43.1"))
        assertTrue(certificateMatchesKey(certificate, keyPair.private))
    }

    @Test
    fun `the key match check rejects a key the certificate was not made for`() {
        val certificate = validCertificate(newP256KeyPair(), listOf("192.168.43.1"))
        // A key remade under the alias while the stored certificate stayed behind
        // is the pair that presents one key and signs with another, and TLS only
        // fails it at handshake time.
        assertTrue(!certificateMatchesKey(certificate, newP256KeyPair().private))
    }

    @Test
    fun `a certificate that belongs to the key and names every address is reused`() {
        val keyPair = newP256KeyPair()
        val certificate = validCertificate(keyPair, listOf("192.168.43.1", "10.0.0.2"))
        assertEquals(
            CertificateDisposition.REUSE,
            certificateDisposition(certificate, keyPair.private, listOf("192.168.43.1", "10.0.0.2")),
        )
    }

    @Test
    fun `a certificate that does not name every address is re-signed over the same key`() {
        val keyPair = newP256KeyPair()
        val certificate = validCertificate(keyPair, listOf("192.168.1.10"))
        assertEquals(
            CertificateDisposition.REISSUE,
            certificateDisposition(certificate, keyPair.private, listOf("192.168.1.10", "192.168.43.1")),
        )
    }

    @Test
    fun `a certificate that does not belong to the keystore key is rebuilt`() {
        val certificate = validCertificate(newP256KeyPair(), listOf("192.168.43.1"))
        assertEquals(
            CertificateDisposition.REGENERATE,
            certificateDisposition(certificate, newP256KeyPair().private, listOf("192.168.43.1")),
        )
    }

    @Test
    fun `a missing key or certificate is rebuilt together`() {
        val keyPair = newP256KeyPair()
        val certificate = validCertificate(keyPair, listOf("192.168.43.1"))
        assertEquals(
            CertificateDisposition.REGENERATE,
            certificateDisposition(null, keyPair.private, listOf("192.168.43.1")),
        )
        assertEquals(
            CertificateDisposition.REGENERATE,
            certificateDisposition(certificate, null, listOf("192.168.43.1")),
        )
    }

    @Test
    fun `an expired certificate is rebuilt`() {
        val keyPair = newP256KeyPair()
        val past = System.currentTimeMillis() - 120_000
        val certificate = assembleSelfSigned(
            keyPair = keyPair,
            commonName = "Pi Remote web access",
            notBefore = Date(past - 60_000),
            notAfter = Date(past),
            subjectAltIps = listOf("192.168.43.1"),
        )
        assertEquals(
            CertificateDisposition.REGENERATE,
            certificateDisposition(certificate, keyPair.private, listOf("192.168.43.1")),
        )
    }

    private fun validCertificate(keyPair: java.security.KeyPair, addresses: List<String>) = assembleSelfSigned(
        keyPair = keyPair,
        commonName = "Pi Remote web access",
        notBefore = Date(System.currentTimeMillis() - 60_000),
        notAfter = Date(System.currentTimeMillis() + 60_000),
        subjectAltIps = addresses,
    )
}
