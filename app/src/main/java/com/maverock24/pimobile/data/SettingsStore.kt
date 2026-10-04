package com.maverock24.pimobile.data

import android.content.Context

/** Endpoint and token for the pi-remote bridge on the laptop. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)

    /**
     * Empty until a pairing (or a hand-typed URL) fills it in. The app deliberately
     * ships no default: the laptop's tailnet address is handed over by the QR at
     * pairing time, so it does not sit in the source, in every APK and in the
     * published releases for anyone to read.
     */
    var baseUrl: String
        get() = prefs.getString("baseUrl", "")?.trim().orEmpty()
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
}
