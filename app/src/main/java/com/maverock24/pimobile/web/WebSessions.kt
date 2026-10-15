package com.maverock24.pimobile.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * One approved browser: the credential the cookie carries and the CSRF secret
 * the page keeps in memory.
 *
 * The id is what a session is looked up by. The CSRF secret is separate on
 * purpose: the cookie rides along on every same-site request, including a
 * cross-site one that a same-site edge case lets through, so a mutation needs a
 * second value that a cross-site request cannot set as a header.
 */
internal class WebSession(
    val id: String,
    val csrf: String,
    val sourceAddress: String,
    val createdAt: Long,
    var lastSeen: Long,
)

/**
 * The live sessions, in memory for the life of the server.
 *
 * Nothing is persisted: stopping the server must drop every session, so a stale
 * browser cannot reconnect to a listener the human believes is off. The idle and
 * absolute bounds are enforced on each lookup as finding F3 requires, and the
 * absolute cap is what makes "short-lived" true even while the server stays on.
 */
internal class WebSessions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = IDLE_MS,
    private val capMs: Long = CAP_MS,
) {
    private val random = SecureRandom()
    private val byId = ConcurrentHashMap<String, WebSession>()

    val idleSeconds: Long get() = idleMs / 1000

    /** Mints a session for an approved connection. */
    fun create(sourceAddress: String): WebSession {
        val now = clock()
        val session = WebSession(
            id = randomToken(),
            csrf = randomToken(),
            sourceAddress = sourceAddress,
            createdAt = now,
            lastSeen = now,
        )
        byId[session.id] = session
        return session
    }

    /**
     * Resolves a cookie value to a live session. An unknown id, an idle session
     * or a session past its absolute cap all return null, so an expired session
     * fails closed. A live one is touched, which is the sliding idle window.
     */
    fun authenticate(id: String?): WebSession? {
        if (id.isNullOrBlank()) return null
        val session = byId[id] ?: return null
        val now = clock()
        if (now >= session.createdAt + capMs || now >= session.lastSeen + idleMs) {
            byId.remove(id)
            return null
        }
        session.lastSeen = now
        return session
    }

    fun revoke(id: String) {
        byId.remove(id)
    }

    fun revokeAll() {
        byId.clear()
    }

    /** The cookie value, ready to place after `pi_session=`. */
    fun cookieHeader(session: WebSession): String =
        "pi_session=${session.id}; HttpOnly; SameSite=Strict; Path=/; Secure; " +
            "Max-Age=$idleSeconds"

    /** The cookie that clears the session in the browser. */
    fun clearedCookieHeader(): String =
        "pi_session=; HttpOnly; SameSite=Strict; Path=/; Secure; Max-Age=0"

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Thirty minutes with no request ends a session. */
        const val IDLE_MS = 30L * 60 * 1000

        /** Four hours from approval ends it regardless of activity. */
        const val CAP_MS = 4L * 60 * 60 * 1000
    }
}

/**
 * Compares two secrets without leaking where they first differ. A plain `==`
 * short-circuits on the first byte, which is enough of an oracle to matter when
 * the value is being guessed.
 */
internal fun secretsMatch(expected: String?, provided: String?): Boolean {
    if (expected.isNullOrEmpty() || provided.isNullOrEmpty()) return false
    return MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())
}
