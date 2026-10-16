package com.maverock24.pimobile.web

/** One pinned prompt and the answer it produced, as the browser's Pins screen draws it. */
internal data class WebPin(
    val id: String,
    val title: String,
    val prompt: String,
    val answer: String,
)

/**
 * The phone's pins, read and written by the relay for the browser session.
 *
 * Pins are the phone's own data and not a bridge route, so this is a relay-local
 * surface like the session routes rather than part of the proxy allowlist. It is
 * an interface so the relay stays plain-JVM testable: the Android adapter wraps
 * `PinsStore`, and a test hands in an in-memory fake. Renaming and deleting here
 * change the phone's own pins, which is what "the same interface" means.
 */
internal interface WebPins {
    suspend fun list(): List<WebPin>

    /** Returns false when the title is blank or no pin carries [id]. */
    suspend fun rename(id: String, title: String): Boolean

    /** Returns false when no pin carries [id]. */
    suspend fun delete(id: String): Boolean

    /** A phone with no pins store, so the relay has a safe default. */
    object Empty : WebPins {
        override suspend fun list(): List<WebPin> = emptyList()
        override suspend fun rename(id: String, title: String): Boolean = false
        override suspend fun delete(id: String): Boolean = false
    }
}
