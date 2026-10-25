package com.maverock24.pimobile.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * One failure in the two parts the screens need. [summary] is what a human reads
 * on a phone: one or two plain sentences saying what failed and what to try, short
 * enough for car mode. [detail] is what a debugging round needs: the exception,
 * its message and the address it was talking to, kept behind an unfold so it never
 * crowds the summary.
 */
data class Problem(
    val summary: String,
    val detail: String = "",
    /**
     * True when the failure looks like a missing tailnet link. The screens turn
     * this into an "Open Tailscale" action and a line about the VPN state. It is
     * never a claim that the app can bring the tunnel up: it cannot, see
     * [Tailscale].
     */
    val tailnet: Boolean = false,
)

/**
 * The single place a [Throwable] becomes words for the screen. Every error the app
 * draws goes through here, so there is one style of message and one place to
 * change it.
 *
 * The summary leads, because the phone cuts a long line and the half that says
 * what to do used to be the half that got cut.
 */
object Problems {

    /** A short message with nothing technical behind it, for a plain notice. */
    fun note(summary: String): Problem = Problem(summary)

    /** The whole presentation for [error], with the address it was using kept in the detail. */
    fun of(error: Throwable, baseUrl: String = "", subject: String? = null): Problem = Problem(
        summary = summary(error),
        detail = detail(error, baseUrl, subject),
        tailnet = tailnetLikely(error),
    )

    fun summary(error: Throwable): String {
        // The cleartext policy is a rule on this phone rather than on the network,
        // so its fix is in the app's own settings and it stays first.
        if (isCleartext(error)) {
            return "Android blocked this address. Pair again to get the laptop's name."
        }
        if (error is BridgeException) {
            return when (error.code) {
                401, 403 -> "The bridge rejected the token. Pair again in Settings."
                404 -> "The bridge has no such route. Reload it on the laptop."
                in 500..599 -> "The bridge hit an error. The details say what."
                else -> "The bridge refused the request (HTTP ${error.code})."
            }
        }
        return when (error) {
            is UnknownHostException -> "Cannot find the laptop. Tailscale may be off."
            is NoRouteToHostException -> "No route to the laptop. Turn Tailscale on."
            is ConnectException -> "The bridge is not answering. Check it runs on the laptop."
            is SocketTimeoutException -> "The laptop is slow to answer. Check Tailscale, then retry."
            is SSLException -> "The secure connection failed. Check the address and the certificate."
            is SocketException -> "The connection dropped. Tailscale may have gone."
            is IOException -> "Cannot reach the bridge. The details say why."
            else -> "Something failed. The details say what."
        }
    }

    /**
     * The technical half: exception class, its message, the address it used and a
     * plain sentence on what that usually means. Newline separated, because the
     * fold draws it in a monospace block and a phone reads short lines better than
     * one long one.
     */
    fun detail(error: Throwable, baseUrl: String = "", subject: String? = null): String {
        val raw = error.message?.takeIf { it.isNotBlank() }
        val cause = error.javaClass.simpleName + if (raw == null) "" else ": $raw"
        val lines = mutableListOf(if (subject.isNullOrBlank()) cause else "$subject: $cause")
        if (baseUrl.isNotBlank()) lines += "address: $baseUrl"
        guidance(error)?.let { lines += it }
        return lines.joinToString("\n")
    }

    /**
     * Whether the failure is one a missing tunnel explains. A refused connection is
     * left out on purpose: the host answered, so the link works and the bridge is
     * the thing to look at.
     */
    fun tailnetLikely(error: Throwable): Boolean {
        if (isCleartext(error)) return false
        return when (error) {
            is UnknownHostException, is NoRouteToHostException, is SocketTimeoutException -> true
            is ConnectException -> false
            is SocketException -> true
            else -> false
        }
    }

    private fun isCleartext(error: Throwable): Boolean =
        error.message?.contains("CLEARTEXT", ignoreCase = true) == true

    /** What the exception class usually means here, in one sentence a human can act on. */
    private fun guidance(error: Throwable): String? = when {
        isCleartext(error) ->
            "Android blocked cleartext HTTP for this host. Pair again to get the laptop's " +
                "MagicDNS name; a bare tailnet IP is not allowed through."
        error is android.os.NetworkOnMainThreadException ->
            "Internal bug: a blocking network call ran on the UI thread."
        error is UnknownHostException ->
            "The name did not resolve. Tailscale is probably not running on the phone; if " +
                "it is, MagicDNS may be off or the URL has a typo."
        error is SocketTimeoutException ->
            "The address resolved but nothing answered in time. Tailscale may be connected " +
                "and blocked, or the laptop may be asleep."
        error is SSLException ->
            "TLS failed. Drop https:// unless tailscale serve is set up."
        error is NoRouteToHostException ->
            "There is no TCP route to the laptop, which means Tailscale is not connected on " +
                "the phone."
        error is ConnectException ->
            "The host answered and refused the port, so the network path works and the " +
                "bridge is probably not running on it."
        error is SocketException ->
            "The connection failed: check the bridge on the laptop and the Tailscale link " +
                "on the phone."
        else -> null
    }
}
