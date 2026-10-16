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
}
