package com.maverock24.pimobile.data

import android.content.Context

/** Endpoint and token for the pi-remote bridge on the laptop. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", DEFAULT_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString("baseUrl", value.trim().trimEnd('/')).apply()

    /**
     * The bridge token, encrypted with a keystore key before it touches disk.
     * A value written before encryption existed is plaintext; [TokenCrypto.decrypt]
     * fails on it, so we adopt it and rewrite it encrypted on the first read.
     * Nothing is ever written back as plaintext.
     */
    var token: String
        get() {
            val stored = prefs.getString("token", "").orEmpty()
            if (stored.isBlank()) return ""
            TokenCrypto.decrypt(stored)?.let { return it }
            token = stored
            return stored
        }
        set(value) {
            val trimmed = value.trim()
            val encrypted = if (trimmed.isEmpty()) null else TokenCrypto.encrypt(trimmed)
            val editor = prefs.edit()
            if (encrypted == null) editor.remove("token") else editor.putString("token", encrypted)
            editor.apply()
        }

    /** "dark", "light" or "system". The design lab choice was dark. */
    var appearance: String
        get() = prefs.getString("appearance", "dark") ?: "dark"
        set(value) = prefs.edit().putString("appearance", value).apply()

    val isConfigured: Boolean
        get() = token.isNotBlank()

    companion object {
        // MagicDNS name: Android's cleartext policy is evaluated per hostname, so
        // the name is more reliable than the raw tailnet IP.
        const val DEFAULT_BASE_URL = "http://your-laptop.your-tailnet.ts.net:8787"
    }
}
