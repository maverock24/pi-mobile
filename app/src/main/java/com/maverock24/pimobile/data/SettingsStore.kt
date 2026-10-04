package com.maverock24.pimobile.data

import android.content.Context

/** Endpoint and token for the pi-remote bridge on the laptop. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", DEFAULT_BASE_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString("baseUrl", value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    val isConfigured: Boolean
        get() = token.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "http://192.0.2.1:8787"
    }
}
