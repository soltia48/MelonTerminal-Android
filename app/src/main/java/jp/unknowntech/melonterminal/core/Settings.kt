package jp.unknowntech.melonterminal.core

import android.content.Context

/**
 * Persisted terminal configuration: the server URL and the merchant API key. Stored
 * in app-private `SharedPreferences` — readable only by this app under the Android
 * sandbox (the equivalent of the desktop terminal's 0600 credentials file). The key
 * never leaves the device; it is sent only as a Bearer token to the server.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER)!!.ifBlank { DEFAULT_SERVER }
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    /** The merchant API key, or null if not configured yet. */
    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.ifBlank { null }
        set(value) = prefs.edit().putString(KEY_API_KEY, value?.trim()).apply()

    val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank()

    companion object {
        const val DEFAULT_SERVER = "https://melon.unknowntech.jp"
        private const val PREFS = "melon_terminal"
        private const val KEY_SERVER = "server_url"
        private const val KEY_API_KEY = "api_key"
    }
}
