package com.maverock24.pimobile.web

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Terminates TLS for the web server on the WLAN interface.
 *
 * The listening socket is an ordinary JSSE [SSLServerSocket], which is the one
 * Android path that can sign a handshake with an Android keystore key: the
 * platform reads the key from [keyStore] during the handshake and never exposes
 * it. Each accepted, decrypted connection is piped to the loopback Ktor server,
 * so Ktor keeps the HTTP handling and this class only moves bytes.
 *
 * The listening socket is bound to every interface, not to one address. The
 * chosen transport, the phone's own hotspot, has no interface name that is the
 * same across manufacturers and its address can appear after the server starts,
 * so binding one guessed address is what left the hotspot unreachable in the
 * first place. The allowed `Host` set and the TLS handshake are the controls; a
 * socket bound to every interface is still only reachable on an address the
 * phone actually holds.
 *
 * Stop closes the listening socket first, so no new client can arrive, and then
 * the open pipes.
 */
class WebTlsFront(
    private val keyStore: KeyStore,
    private val keyAlias: String,
    private val port: Int,
    private val backendPort: Int,
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

    /** Binds every interface and starts accepting. Throws if the port is taken. */
    fun start() {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        // The Android keystore ignores the password: the key is the entry's, not
        // one unlocked by a passphrase.
        managers.init(keyStore, null)
        val context = SSLContext.getInstance("TLS")
        context.init(managers.keyManagers, null, null)
        // No address argument: the JSSE server socket then listens on the wildcard
        // address, which is what answers on the hotspot and on any other interface
        // the phone later brings up.
        val socket = context.serverSocketFactory
            .createServerSocket(port, BACKLOG) as SSLServerSocket
        serverSocket = socket
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
            val backend = try {
                Socket().apply {
                    connect(
                        InetSocketAddress(InetAddress.getByName(LOOPBACK), backendPort),
                        CONNECT_TIMEOUT_MS,
                    )
                }
            } catch (_: IOException) {
                closeQuietly(client)
                continue
            }
            open.add(client)
            open.add(backend)
            // The HTTP layer only ever sees 127.0.0.1, so record the real peer
            // against the loopback port this connection dialled. The HTTP layer
            // reads the same port back as the request's remote port, which is
            // what lets the phone show who is asking to be approved.
            val backendPort = backend.localPort
            WebPeers.register(backendPort, client.inetAddress?.hostAddress ?: "unknown")
            val remaining = AtomicInteger(2)
            val finished = {
                if (remaining.decrementAndGet() == 0) WebPeers.forget(backendPort)
            }
            pumps.execute {
                try {
                    pump(client, backend)
                } finally {
                    finished()
                }
            }
            pumps.execute {
                try {
                    pump(backend, client)
                } finally {
                    finished()
                }
            }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // The peer closed, or stop() closed the socket mid-read; both end the
            // pump. Either way the other direction is closed with it below.
        } finally {
            closeQuietly(from)
            closeQuietly(to)
            open.remove(from)
            open.remove(to)
        }
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
