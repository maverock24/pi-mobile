package com.maverock24.pimobile.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Settings screen says about the web server.
 *
 * The service writes this and the UI reads it, both inside the app's own
 * process, so a plain StateFlow in an object is enough. There is no cross-process
 * state to keep, and nothing here outlives the process.
 */
sealed interface WebAccessStatus {
    /** Nothing is listening. */
    data object Off : WebAccessStatus

    /** The human asked for the server and it is coming up. */
    data object Starting : WebAccessStatus

    /** Listening, with the address to open and the fingerprint to compare. */
    data class On(val url: String, val fingerprint: String) : WebAccessStatus

    /** It could not start, or stopped on its own for a reason worth naming. */
    data class Failed(val message: String) : WebAccessStatus
}

object WebAccess {
    /**
     * The port the human types at the other computer. It is fixed so the URL is
     * predictable, and the server fails loudly when it is taken rather than
     * silently moving, because a moving port means re-reading the phone. 8788
     * sits next to the bridge's 8787 so it is easy to remember, and it is above
     * 1024 so no root is needed.
     */
    const val PORT = 8788

    /**
     * How long the server waits with nothing in flight before it stops itself.
     * A page refresh, a tab switch or a short network blip all land inside this
     * window, so ordinary use does not end the session; only the client actually
     * going away for this long does.
     */
    const val IDLE_GRACE_MS = 90_000L

    private val _status = MutableStateFlow<WebAccessStatus>(WebAccessStatus.Off)
    val status: StateFlow<WebAccessStatus> = _status.asStateFlow()

    internal fun update(value: WebAccessStatus) {
        _status.value = value
    }
}
