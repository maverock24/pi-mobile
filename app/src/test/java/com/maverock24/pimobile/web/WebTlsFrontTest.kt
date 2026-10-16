package com.maverock24.pimobile.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
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

    @Test
    fun `the front binds every interface and answers on a non-loopback address`() {
        val keyStore = scratchKeyStore("127.0.0.1")
        val frontPort = ServerSocket(0).use { it.localPort }
        val localIp = nonLoopbackIpv4()
        val authority = "$localIp:$frontPort"
        val relay = WebRelayServer(
            loadAsset = { "<html>PAGE</html>" },
            allowedAuthorities = setOf(authority),
            allowedOrigins = setOf("https://$authority"),
            upstream = NoopUpstream,
            gate = WebApprovalGate(ttlMs = 5_000),
        )
        val backendPort = runBlocking { relay.start() }
        val front = WebTlsFront(keyStore, ALIAS, frontPort, backendPort)
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

    private fun scratchKeyStore(subjectAltIp: String): KeyStore {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val certificate = assembleSelfSigned(
            pair, "scratch", Date(now - 60_000), Date(now + 86_400_000), listOf(subjectAltIp),
        )
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        // The same shape AndroidKeyStore presents: a key entry reachable with a
        // null password, which is what WebTlsFront.init passes.
        store.setKeyEntry(ALIAS, pair.private, null, arrayOf(certificate))
        return store
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
