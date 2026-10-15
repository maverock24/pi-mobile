package com.maverock24.pimobile.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One browser asking to be let in, as the phone shows it. [shortId] is what is
 * displayed so the human has something to read; [id] is the full value the
 * Allow and Deny buttons act on.
 */
internal data class WebApproval(
    val id: String,
    val shortId: String,
    val sourceAddress: String,
    val requestedAt: Long,
)

/** What happened to a held approval request. */
internal enum class ApprovalOutcome { APPROVED, DENIED, EXPIRED, BUSY, RATE_LIMITED }

/**
 * The approval step from design section 5.3, and the fix for security finding
 * F4.
 *
 * A browser's request is held open here, on the exact HTTP connection that asked
 * for it. Approval never mints a code the browser types back, and it never
 * answers a poll: the session is written to the held connection and nowhere
 * else. That is the binding. An attacker on the same WLAN can create its own
 * pending request, but it can never receive the response to someone else's,
 * because there is no way to reach a held socket except by being the socket.
 *
 * Only one request exists at a time and creation is rate-limited per address, so
 * the single pending slot is not easy to wear the human down with (the residual
 * F4 accepts it can still be squatted briefly).
 */
internal class WebApprovalGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = REQUEST_TTL_MS,
    private val rateWindowMs: Long = RATE_WINDOW_MS,
    private val rateMax: Int = RATE_MAX,
) {
    private class Pending(val approval: WebApproval, val deferred: CompletableDeferred<Boolean>)

    private val lock = Any()
    private val random = java.security.SecureRandom()
    private val arrivals = mutableMapOf<String, MutableList<Long>>()

    @Volatile
    private var current: Pending? = null

    private val _pending = MutableStateFlow<WebApproval?>(null)
    val pending: StateFlow<WebApproval?> = _pending.asStateFlow()

    /**
     * Registers a pending request bound to the calling connection and suspends
     * until the phone answers, the request expires, or the server stops. The
     * caller is the route handler, so its coroutine is the held connection.
     */
    suspend fun request(sourceAddress: String): ApprovalOutcome {
        if (rateLimited(sourceAddress)) return ApprovalOutcome.RATE_LIMITED
        val deferred = CompletableDeferred<Boolean>()
        val id = randomToken()
        val approval = WebApproval(id, id.take(SHORT_ID_CHARS), sourceAddress, clock())
        synchronized(lock) {
            val existing = current
            if (existing != null && !existing.deferred.isCompleted) return ApprovalOutcome.BUSY
            current = Pending(approval, deferred)
        }
        _pending.value = approval
        return try {
            when (withTimeoutOrNull(ttlMs) { deferred.await() }) {
                true -> ApprovalOutcome.APPROVED
                false -> ApprovalOutcome.DENIED
                null -> ApprovalOutcome.EXPIRED
            }
        } finally {
            synchronized(lock) {
                if (current?.approval?.id == id) {
                    current = null
                    _pending.value = null
                }
            }
        }
    }

    /** Allows the request with [id]. Returns false if it is no longer pending. */
    fun approve(id: String): Boolean = answer(id, allowed = true)

    /** Denies the request with [id]. Returns false if it is no longer pending. */
    fun deny(id: String): Boolean = answer(id, allowed = false)

    /** Drops any pending request, used when the server stops. Idempotent. */
    fun clear() {
        val stopping = synchronized(lock) {
            current.also { current = null }
        }
        _pending.value = null
        stopping?.deferred?.complete(false)
    }

    private fun answer(id: String, allowed: Boolean): Boolean {
        val target = synchronized(lock) {
            current?.takeIf { it.approval.id == id && !it.deferred.isCompleted }
        } ?: return false
        return target.deferred.complete(allowed)
    }

    private fun rateLimited(sourceAddress: String): Boolean = synchronized(lock) {
        val now = clock()
        val window = arrivals.getOrPut(sourceAddress) { mutableListOf() }
        window.removeAll { now - it >= rateWindowMs }
        if (window.size >= rateMax) return true
        window.add(now)
        false
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The one gate the running server and the phone UI share. */
        val shared = WebApprovalGate()

        /** About two minutes, as design section 5.3 says. */
        const val REQUEST_TTL_MS = 120_000L
        const val RATE_WINDOW_MS = 60_000L
        const val RATE_MAX = 5
        private const val SHORT_ID_CHARS = 8
    }
}
