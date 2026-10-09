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

    /**
     * "transcript", "deck" or "pins": which view is shown. The first two are
     * views of the same transcript and the third is the saved pins. It is kept
     * next to the appearance, so the view the user last chose is the one a
     * restart comes back to. The transcript is the default.
     */
    var viewMode: String
        get() = prefs.getString("viewMode", "transcript") ?: "transcript"
        set(value) = prefs.edit().putString("viewMode", value).apply()

    /**
     * "midnight", "indigo", "amber" or "forest": which dark palette the app
     * paints with. Light mode ignores it, so this only matters on the dark side.
     * Midnight is the original look and the default.
     */
    var theme: String
        get() = prefs.getString("theme", "midnight") ?: "midnight"
        set(value) = prefs.edit().putString("theme", value).apply()

    /**
     * The pi session this phone attached to. It is written down so a restart
     * does not silently adopt whatever session happens to be serving: the app
     * moves only when the bridge reports a different id, and then it says so.
     * Empty until the first state frame names a session.
     */
    var sessionId: String
        get() = prefs.getString("sessionId", "").orEmpty()
        set(value) = prefs.edit().putString("sessionId", value).apply()

    /** The name of the pinned session, so its label survives a restart. */
    var sessionName: String
        get() = prefs.getString("sessionName", "").orEmpty()
        set(value) = prefs.edit().putString("sessionName", value).apply()

    val isConfigured: Boolean
        get() = token.isNotBlank()
}
