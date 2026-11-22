package nl.koffietijd.tibberwidget

import android.content.Context

/** Eenvoudige opslag van het API-token, de gekozen woning en de laatst opgehaalde data. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tibber_widget", Context.MODE_PRIVATE)

    var token: String?
        get() = sp.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = sp.edit().putString(KEY_TOKEN, value?.trim()).apply()

    var homeId: String?
        get() = sp.getString(KEY_HOME, null)
        set(value) = sp.edit().putString(KEY_HOME, value).apply()

    var cachedViewer: String?
        get() = sp.getString(KEY_VIEWER, null)
        set(value) = sp.edit().putString(KEY_VIEWER, value).apply()

    var cachedLive: String?
        get() = sp.getString(KEY_LIVE, null)
        set(value) = sp.edit().putString(KEY_LIVE, value).apply()

    var fetchedAt: Long
        get() = sp.getLong(KEY_FETCHED, 0L)
        set(value) = sp.edit().putLong(KEY_FETCHED, value).apply()

    var lastError: String?
        get() = sp.getString(KEY_ERROR, null)
        set(value) = sp.edit().putString(KEY_ERROR, value).apply()

    var lastAttempt: Long
        get() = sp.getLong(KEY_ATTEMPT, 0L)
        set(value) = sp.edit().putLong(KEY_ATTEMPT, value).apply()

    /** Verwijdert token én alle gecachte gegevens. */
    fun clearAll() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_HOME = "home_id"
        private const val KEY_VIEWER = "cached_viewer"
        private const val KEY_LIVE = "cached_live"
        private const val KEY_FETCHED = "fetched_at"
        private const val KEY_ERROR = "last_error"
        private const val KEY_ATTEMPT = "last_attempt"
    }
}
