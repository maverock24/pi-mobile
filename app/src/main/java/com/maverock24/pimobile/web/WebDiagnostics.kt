package com.maverock24.pimobile.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the network listener and its TLS front are actually doing, so a
 * connection that opens and then closes with no response leaves a reason behind.
 *
 * The TLS front runs outside the HTTP server, so a handshake that fails, or a
 * backend that will not answer, never becomes an HTTP response the browser can
 * show. That is the "accepts the connection and then hangs up" the human hit, and
 * it took several rounds because nothing recorded it. The front writes what it
 * saw here, the relay serves it on GET /api/diagnostics, the Settings screen
 * shows the same numbers, and the front also logs each close.
 *
 * This is per-process state, like [WebAccess] and [WebPeers]: a server that is
 * off has nothing to report, so nothing here is persisted.
 */
internal data class ListenerDiagnostics(
    /** True once the certificate and its key were usable to start the front. */
    val certificateLoaded: Boolean = false,
    /** The SHA-256 of the certificate being served, the string the phone shows. */
    val fingerprint: String = "",
    /** The fixed port the human opens, [WebAccess.PORT]. */
    val listenPort: Int = 0,
    /** The loopback port Ktor bound, where every connection is forwarded. */
    val backendPort: Int = 0,
    /** The address the listening socket bound; 0.0.0.0 means every interface. */
    val boundAddress: String = "",
    /** The authorities the Host check accepts right now. */
    val allowedAddresses: List<String> = emptyList(),
    /** Connections accepted by the TLS front. */
    val acceptedConnections: Long = 0,
    /** Connections whose TLS handshake failed, the empty-response case. */
    val handshakeFailures: Long = 0,
    /** Connections the front could not forward because the backend was gone. */
    val backendFailures: Long = 0,
    /** The most recent close, in one plain sentence, or "" when none has failed. */
    val lastCloseReason: String = "",
)

/**
 * The single place the listener state lives. It is a singleton for the same
 * reason [WebAccess] is: one server runs per process, and both the front that
 * writes it and the relay route that serves it are created by the same service.
 */
internal object WebDiagnostics {
    private val _state = MutableStateFlow(ListenerDiagnostics())
    val state: StateFlow<ListenerDiagnostics> = _state.asStateFlow()

    val value: ListenerDiagnostics get() = _state.value

    /** Compare-and-set, because a live pump can write from several threads. */
    internal fun update(change: (ListenerDiagnostics) -> ListenerDiagnostics) {
        _state.update(change)
    }

    internal fun reset() {
        _state.value = ListenerDiagnostics()
    }
}

/**
 * One plain sentence describing the listener, for the Settings screen. It names
 * the backend it forwards to and whether the certificate loaded, because those
 * are the two facts that decide whether a shown address can answer at all. When
 * a handshake has failed it says so, since a browser only shows that as an empty
 * response and the phone is the only place the reason can appear.
 *
 * This is the detail behind [diagnosticsHeadline], not a line to read on a phone:
 * it carries the counters and the whole close reason, so it is drawn unfolded
 * only when the human asks for it.
 */
internal fun diagnosticsSummary(state: ListenerDiagnostics): String {
    if (!state.certificateLoaded) {
        return "The listener did not load its certificate, so it cannot answer."
    }
    val lines = mutableListOf(
        "Listening on ${state.boundAddress}:${state.listenPort}, forwarding to " +
            "127.0.0.1:${state.backendPort}.",
    )
    if (state.acceptedConnections > 0) {
        lines += "${state.acceptedConnections} accepted, ${state.handshakeFailures} TLS " +
            "handshakes failed, ${state.backendFailures} with no backend."
    }
    if (state.lastCloseReason.isNotBlank()) lines += state.lastCloseReason
    return lines.joinToString("\n")
}

/**
 * The one line the Settings screen shows before anyone unfolds anything. It says
 * whether the listener is up and whether the last connection failed, which is all
 * a human needs to decide whether to open a browser or read the detail.
 */
internal fun diagnosticsHeadline(state: ListenerDiagnostics): String {
    if (!state.certificateLoaded) return "The listener did not start: no certificate."
    if (state.lastCloseReason.isNotBlank()) return "The listener is up and the last connection failed."
    return "The listener is up on port ${state.listenPort}."
}
