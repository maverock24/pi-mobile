package com.maverock24.pimobile.web

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Terminates TLS for the web server on the WLAN interface.
 *
 * The listening socket is an ordinary JSSE [SSLServerSocket]. Each accepted,
 * decrypted connection is piped to the loopback Ktor server, so Ktor keeps the
 * HTTP handling and this class only moves bytes.
 *
 * The listening socket is bound to every interface, not to one address. The
 * chosen transport, the phone's own hotspot, has no interface name that is the
 * same across manufacturers and its address can appear after the server starts,
 * so binding one guessed address is what left the hotspot unreachable in the
 * first place. The allowed `Host` set and the TLS handshake are the controls; a
 * socket bound to every interface is still only reachable on an address the
 * phone actually holds.
 *
 * Every close is recorded, because the front is outside the HTTP layer: a failed
 * handshake or a missing backend never becomes a response the browser can read,
 * so without this the phone cannot say why a connection ended. The reasons land
 * in [WebDiagnostics], which the relay serves on GET /api/diagnostics.
 *
 * Stop closes the listening socket first, so no new client can arrive, and then
 * the open pipes.
 */
class WebTlsFront(
    private val keyStore: KeyStore,
    private val keyAlias: String,
    private val certificate: X509Certificate,
    private val port: Int,
    private val backendPort: Int,
    private val log: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val open = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val pumps = Executors.newCachedThreadPool { work ->
        Thread(work, "web-tls-pump").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: SSLServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /**
     * Binds every interface and starts accepting. Throws if the port is taken or
     * if the keystore key cannot be read, so a listener that could never complete
     * a handshake is refused here rather than accepting and then hanging up.
     */
    fun start() {
        // The private key is resolved once, before the socket binds. AndroidKeyStore
        // ignores the password: the key is the entry's, not one unlocked by a
        // passphrase. A missing key is a listener that answers nothing, so it is an
        // error now, where the service can show it, not a per-connection surprise.
        val privateKey = keyStore.getKey(keyAlias, null) as? PrivateKey
            ?: error("The web access key is missing from the keystore ($keyAlias)")
        val context = SSLContext.getInstance("TLS")
        // The key manager is built by hand rather than from the keystore alone.
        // JSSE's own X509 key manager chooses a server alias by reading the
        // certificate chain out of the keystore, and an AndroidKeyStore entry made
        // for this server holds a key and no certificate, so the default manager
        // finds no alias and the handshake ends with no certificate sent. That is
        // exactly the empty response the browser reported.
        context.init(arrayOf(ServerKeyManager(keyAlias, privateKey, certificate)), null, null)
        // No address argument: the JSSE server socket then listens on the wildcard
        // address, which is what answers on the hotspot and on any other interface
        // the phone later brings up.
        val socket = context.serverSocketFactory
            .createServerSocket(port, BACKLOG) as SSLServerSocket
        serverSocket = socket
        WebDiagnostics.update {
            it.copy(
                certificateLoaded = true,
                fingerprint = fingerprintOf(certificate),
                listenPort = port,
                backendPort = backendPort,
                boundAddress = socket.inetAddress?.hostAddress ?: "",
            )
        }
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "web-tls-accept").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The address the listening socket bound, read back so a test can prove it is
     * the wildcard and not one interface. Null before [start] and after [stop].
     */
    internal fun boundAddress(): InetAddress? = serverSocket?.inetAddress

    private fun acceptLoop(server: SSLServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept() as SSLSocket
            } catch (_: IOException) {
                // The listening socket was closed under the loop, which is how
                // stop() ends it.
                break
            }
            val peer = client.inetAddress?.hostAddress ?: "unknown"
            val backend = try {
                Socket().apply {
                    connect(
                        InetSocketAddress(InetAddress.getByName(LOOPBACK), backendPort),
                        CONNECT_TIMEOUT_MS,
                    )
                }
            } catch (error: IOException) {
                // With no backend there is nothing to forward the request to, so
                // the client is closed with no response. This was silent before,
                // and a browser shows it only as an empty response with no reason.
                recordClose(
                    "$peer: no backend listening on 127.0.0.1:$backendPort ($error)",
                    backendFailure = true,
                )
                closeQuietly(client)
                continue
            }
            WebDiagnostics.update { it.copy(acceptedConnections = it.acceptedConnections + 1) }
            open.add(client)
            open.add(backend)
            // The HTTP layer only ever sees 127.0.0.1, so record the real peer
            // against the loopback port this connection dialled. The HTTP layer
            // reads the same port back as the request's remote port, which is
            // what lets the phone show who is asking to be approved.
            val dialedPort = backend.localPort
            WebPeers.register(dialedPort, peer)
            val remaining = AtomicInteger(2)
            val finished = {
                if (remaining.decrementAndGet() == 0) WebPeers.forget(dialedPort)
            }
            pumps.execute {
                try {
                    pump(client, backend, peer, handshake = true)
                } finally {
                    finished()
                }
            }
            pumps.execute {
                try {
                    pump(backend, client, peer, handshake = false)
                } finally {
                    finished()
                }
            }
        }
    }

    /**
     * Moves bytes one way. [handshake] marks the direction where a read from the
     * client runs the TLS handshake, so a failure before the first application
     * byte is the handshake failing and is recorded; a failure after some bytes
     * have flowed is an ordinary reset and is not.
     */
    private fun pump(from: Socket, to: Socket, peer: String, handshake: Boolean) {
        var forwarded = 0L
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                forwarded += read
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (error: IOException) {
            // The peer closed, or stop() closed the socket mid-read, or the
            // handshake failed. Only the last is worth recording, and a failure
            // before any byte crossed is how the two are told apart. stop() sets
            // running to false first, so its closes are never counted.
            if (handshake && forwarded == 0L && running.get()) {
                recordClose("$peer: TLS handshake failed ($error)", handshakeFailure = true)
            }
        } finally {
            closeQuietly(from)
            closeQuietly(to)
            open.remove(from)
            open.remove(to)
        }
    }

    private fun recordClose(reason: String, handshakeFailure: Boolean = false, backendFailure: Boolean = false) {
        WebDiagnostics.update {
            it.copy(
                handshakeFailures = it.handshakeFailures + if (handshakeFailure) 1L else 0L,
                backendFailures = it.backendFailures + if (backendFailure) 1L else 0L,
                lastCloseReason = reason,
            )
        }
        log(reason)
    }

    /** Idempotent. Closes the listening socket and every open connection. */
    fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly(serverSocket)
        serverSocket = null
        for (connection in open.toList()) closeQuietly(connection)
        open.clear()
        pumps.shutdownNow()
        acceptThread?.join(STOP_JOIN_MS)
        acceptThread = null
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
            // Closing an already-broken connection is not a failure.
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 24
        const val BUFFER_SIZE = 16 * 1024
        const val CONNECT_TIMEOUT_MS = 2_000
        const val STOP_JOIN_MS = 1_000L
    }
}

/**
 * Serves the one web-access certificate with the one Android keystore key.
 *
 * JSSE's own X509 key manager builds its list of server aliases from the
 * certificate chain the keystore holds. An AndroidKeyStore entry generated for
 * this server has no chain, because the certificate is assembled separately and
 * the key stays in the keystore, so the default manager offers no alias and the
 * handshake sends no certificate. This manager answers with the certificate the
 * app already holds and the private key read back from the keystore, so the key
 * still never leaves it.
 *
 * Both the socket and the engine selection methods are answered, because an
 * engine may use either and a plain [javax.net.ssl.X509KeyManager] would leave
 * the engine path returning null.
 */
private class ServerKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {

    /** Only the EC key this app generated is offered; a key type it cannot be is refused. */
    private fun serves(keyType: String?): Boolean =
        keyType == null || keyType.equals("EC", ignoreCase = true)

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (serves(keyType)) alias else null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = if (serves(keyType)) alias else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        if (serves(keyType)) arrayOf(alias) else emptyArray()

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)

    override fun getPrivateKey(alias: String?): PrivateKey = privateKey

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
