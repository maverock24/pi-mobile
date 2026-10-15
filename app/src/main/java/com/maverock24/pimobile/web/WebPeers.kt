package com.maverock24.pimobile.web

import java.util.concurrent.ConcurrentHashMap

/**
 * The real WLAN address behind a connection, for the phone's approval screen.
 *
 * TLS is terminated by [WebTlsFront] in front of the loopback HTTP server, so
 * the HTTP layer sees every request as coming from 127.0.0.1. The front knows
 * the true peer and the loopback port its own dial used, and the HTTP layer can
 * read that same port back as the request's remote port, so the two are joined
 * here. The phone shows this address so the human can tell a request they made
 * from one they did not.
 */
internal object WebPeers {
    private val byBackendPort = ConcurrentHashMap<Int, String>()

    fun register(backendPort: Int, host: String) {
        byBackendPort[backendPort] = host
    }

    fun lookup(backendPort: Int): String? = byBackendPort[backendPort]

    fun forget(backendPort: Int) {
        byBackendPort.remove(backendPort)
    }
}
