package com.maverock24.pimobile.web

/**
 * The request checks that run on every call, before any route.
 *
 * These are the F1 and DNS-rebinding controls restated for a listener any device
 * on the WLAN can reach, and they are plain functions so the refusals can be
 * tested without a phone. Each returns true when the request must be refused.
 */

/**
 * The `Host` header must be present exactly once and equal one of the addresses
 * the server is served on. A page that rebinds its own domain to the phone's
 * address arrives with its own name in `Host`, and a duplicated header is a
 * smuggling attempt, so both fail closed. The allowed set is the phone's own
 * literal addresses, which changes as its interfaces come and go but is never a
 * name, so a rebound name still fails. The comparison ignores case because host
 * names are case-insensitive; the port is part of the string.
 */
internal fun hostRefused(hosts: List<String>, allowedAuthorities: Set<String>): Boolean {
    if (hosts.size != 1) return true
    val host = hosts[0].trim()
    return allowedAuthorities.none { it.trim().equals(host, ignoreCase = true) }
}

/**
 * `Origin` is only meaningful when a browser sends it, which it does for a
 * fetch. A missing value is allowed (a top-level navigation carries none), but
 * any value that is not one of this server's own origins is refused.
 */
internal fun originRefused(origin: String?, allowedOrigins: Set<String>): Boolean {
    if (origin.isNullOrBlank()) return false
    val value = origin.trim().trimEnd('/')
    return allowedOrigins.none { it.trim().trimEnd('/').equals(value, ignoreCase = true) }
}

/**
 * `Sec-Fetch-Site` is a browser-set fetch signal. `same-origin` is this page
 * calling itself and `none` is a direct navigation; anything else means another
 * site started the request.
 */
internal fun secFetchSiteRefused(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return value != "same-origin" && value != "none"
}

/**
 * The request target must be origin-form (a path starting with `/`). Absolute
 * form (`http://host/...`) and authority form (`host:port`) are rejected, as is
 * any encoding of a separator: security finding F5 showed that `%2e%2e` and
 * `..\..` resolve to a different route under the parser the bridge uses, so the
 * raw target is refused rather than forwarded.
 */
internal fun targetRefused(uri: String): Boolean {
    if (uri.isBlank() || !uri.startsWith("/")) return true
    val lower = uri.lowercase()
    if (lower.contains("%2f") || lower.contains("%2e") || lower.contains("%5c")) return true
    if (uri.contains('\\')) return true
    val path = uri.substringBefore('?')
    return path.split('/').any { it == "." || it == ".." }
}
