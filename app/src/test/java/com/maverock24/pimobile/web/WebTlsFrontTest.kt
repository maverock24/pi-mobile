package com.maverock24.pimobile.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS front, driven end to end on a plain JVM. This is the join the design
 * says only a device can fully exercise (the Android keystore key), but the bind
 * and the byte path are not Android-specific and are the part that decides
 * whether the addresses the app displays can be reached. A front bound to one
 * interface would fail the non-loopback connection here.
 */
class WebTlsFrontTest {

    @Before
    fun resetDiagnostics() {
        // The listener state is per process, so a previous test's counts are
        // cleared before this one starts.
        WebDiagnostics.reset()
    }

    @Test
    fun `the front binds every interface and answers on a non-loopback address`() {
        val material = scratchCertificate("127.0.0.1")
        val frontPort = ServerSocket(0).use { it.localPort }
        val localIp = nonLoopbackIpv4()
        val authority = "$localIp:$frontPort"
        val relay = relayFor(authority)
        val backendPort = runBlocking { relay.start() }
        val front = WebTlsFront(material.keyStore, ALIAS, material.certificate, frontPort, backendPort)
        front.start()
        try {
            // The wildcard bind is what makes every displayed address answer, not
            // only the one the socket happened to name. isAnyLocalAddress is true
            // only for 0.0.0.0 (or ::), never for a specific interface address.
            assertTrue(
                "the listening socket is bound to the wildcard address, not $localIp",
                front.boundAddress()!!.isAnyLocalAddress,
            )
            val (code, body) = httpsGet("https://$authority/")
            assertEquals(200, code)
            assertEquals("<html>PAGE</html>", body)
        } finally {
            front.stop()
            relay.stop()
        }
    }

    /**
     * The regression for the empty response. AndroidKeyStore holds a key and no
     * certificate, because the certificate is assembled separately and the key
     * never leaves the keystore. JSSE's own key manager reads the chain to choose
     * a server alias, so with this keystore it offers nothing and the handshake
     * ends with no certificate sent. The front must serve the certificate it was
     * handed and complete the handshake anyway.
     */
    @Test
    fun `the front serves a certificate when the keystore holds a key with no chain`() {
        val material = scratchCertificate("127.0.0.1")
        // The same key the certificate names, which is what the keystore holds on
        // the phone, but exposed with no chain as AndroidKeyStore does.
        val keyOnly = keyOnlyKeyStore(material.keyPair)
        val frontPort = ServerSocket(0).use { it.localPort }
        val authority = "127.0.0.1:$frontPort"
        val relay = relayFor(authority)
        val backendPort = runBlocking { relay.start() }
        val front = WebTlsFront(keyOnly, ALIAS, material.certificate, frontPort, backendPort)
        front.start()
        try {
            val (code, body) = httpsGet("https://$authority/")
            assertEquals("a keystore with no certificate chain still completes the handshake", 200, code)
            assertEquals("<html>PAGE</html>", body)
        } finally {
            front.stop()
            relay.stop()
        }
    }

    /**
     * The store is meant to rebuild a mismatched pair, but the front must not
     * accept one if it is ever handed it: a listener with a certificate that is
     * not its key's accepts every connection and then closes it with no
     * certificate, which a browser shows only as an empty response.
     */
    @Test
    fun `the front refuses to start when the certificate and the key do not match`() {
        val material = scratchCertificate("127.0.0.1")
        // A key under the alias that is not the certificate's key, which is what a
        // keystore reset, or a key remade while the stored certificate stayed
        // behind, leaves behind.
        val wrongKey = keyOnlyKeyStore(newP256KeyPair())
        val frontPort = ServerSocket(0).use { it.localPort }
        val front = WebTlsFront(wrongKey, ALIAS, material.certificate, frontPort, 1)
        val failure = runCatching { front.start() }.exceptionOrNull()
        assertTrue("start refuses a mismatched pair: $failure", failure is IllegalStateException)
        assertNull("nothing is left listening", front.boundAddress())
    }

    @Test
    fun `the diagnostics route reports the backend port and the loaded certificate`() {
        val material = scratchCertificate("127.0.0.1")
        val frontPort = ServerSocket(0).use { it.localPort }
        val authority = "127.0.0.1:$frontPort"
        val relay = relayFor(authority)
        val backendPort = runBlocking { relay.start() }
        val front = WebTlsFront(material.keyStore, ALIAS, material.certificate, frontPort, backendPort)
        front.start()
        try {
            val (code, body) = httpsGet("https://$authority/api/diagnostics")
            assertEquals(200, code)
            assertTrue("the backend port is named: $body", body!!.contains("\"backendPort\":$backendPort"))
            assertTrue("the listen port is named", body.contains("\"listenPort\":$frontPort"))
            assertTrue("the certificate is reported loaded", body.contains("\"certificateLoaded\":true"))
            assertTrue("the fingerprint is reported", body.contains(fingerprintOf(material.certificate)))
            assertTrue("the served address is allowed", body.contains(authority))
        } finally {
            front.stop()
            relay.stop()
        }
    }

    /**
     * The app's own account of a failed handshake is the only place the reason
     * appears, because the browser sees a close with no response. The bare
     * exception name is not enough to act on, so the cause chain must survive.
     */
    @Test
    fun `a handshake failure chain names the cause and not only the symptom`() {
        val cause = java.security.InvalidKeyException("the keystore key cannot sign with this digest")
        val error = javax.net.ssl.SSLHandshakeException("TLS handshake failed").apply { initCause(cause) }
        val chain = failureChain(error)
        assertTrue("the symptom is named: $chain", chain.contains("SSLHandshakeException"))
        assertTrue("the cause is named: $chain", chain.contains("InvalidKeyException"))
        assertTrue("the cause's message is named: $chain", chain.contains("cannot sign"))
    }

    private fun relayFor(authority: String): WebRelayServer {
        val relay = WebRelayServer(
            loadAsset = { "<html>PAGE</html>" },
            allowedAuthorities = setOf(authority),
            allowedOrigins = setOf("https://$authority"),
            upstream = NoopUpstream,
            gate = WebApprovalGate(ttlMs = 5_000),
        )
        return relay
    }

    private data class Material(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val keyPair: KeyPair,
    )

    private fun scratchCertificate(subjectAltIp: String): Material {
        val pair = newP256KeyPair()
        val now = System.currentTimeMillis()
        val certificate = assembleSelfSigned(
            pair, "scratch", Date(now - 60_000), Date(now + 86_400_000), listOf(subjectAltIp),
        )
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        // The shape AndroidKeyStore presents to JSSE once the certificate is
        // installed: a key entry reachable with a null password.
        store.setKeyEntry(ALIAS, pair.private, null, arrayOf(certificate))
        return Material(store, certificate, pair)
    }

    private fun newP256KeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    /**
     * A keystore that holds a key but exposes no certificate chain, the shape
     * AndroidKeyStore has for a key made with KeyGenParameterSpec.
     */
    private fun keyOnlyKeyStore(pair: KeyPair): KeyStore {
        val wrapped = object : KeyStore(KeyOnlyKeyStore(pair.private), null, "keyonly") {}
        // Marks the keystore initialized so aliases() does not throw.
        wrapped.load(null, null)
        return wrapped
    }

    private class KeyOnlyKeyStore(private val key: PrivateKey) : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key = key
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String?) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(listOf(ALIAS))
        override fun engineContainsAlias(alias: String?): Boolean = alias == ALIAS
        override fun engineSize(): Int = 1
        override fun engineIsKeyEntry(alias: String?): Boolean = alias == ALIAS
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit
        override fun engineStore(param: KeyStore.LoadStoreParameter?) = Unit
    }

    private fun httpsGet(url: String): Pair<Int, String?> {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, null)
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        val code = connection.responseCode
        val body = (connection.inputStream ?: connection.errorStream)?.bufferedReader()?.readText()
        connection.disconnect()
        return code to body
    }

    /**
     * The first non-loopback, non-link-local IPv4 the build machine holds, which
     * stands in for the phone's hotspot address. It is an interface address, so a
     * front that bound one interface only (or loopback) would not answer it.
     */
    private fun nonLoopbackIpv4(): String {
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            for (address in iface.inetAddresses) {
                val ipv4 = address as? Inet4Address ?: continue
                if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) continue
                return ipv4.hostAddress.orEmpty()
            }
        }
        return "127.0.0.1"
    }

    private companion object {
        const val ALIAS = "scratch"

        val NoopUpstream = object : WebUpstream {
            private fun ok() = UpstreamResult(200, "{}")
            override suspend fun state(clientLabel: String) = ok()
            override suspend fun history(clientLabel: String, limit: Int) = ok()
            override suspend fun files(clientLabel: String) = ok()
            override suspend fun question(clientLabel: String) = ok()
            override suspend fun file(clientLabel: String, name: String, range: String?) =
                throw UnsupportedOperationException()
            override suspend fun prompt(clientLabel: String, body: String) = ok()
            override suspend fun answer(clientLabel: String, body: String) = ok()
            override suspend fun events(clientLabel: String): WebUpstreamStream =
                throw UnsupportedOperationException()
        }
    }
}
