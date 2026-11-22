package nl.koffietijd.tibberwidget

import android.content.Context
import org.json.JSONObject

data class DashboardState(
    val home: Home?,
    val live: Live?,
    val fetchedAt: Long,
    val error: String?,
    val hasToken: Boolean,
)

object DataRepository {

    /** Haalt verse gegevens op en slaat ze op. Gooit geen exceptions; fouten worden opgeslagen. */
    suspend fun refresh(context: Context) {
        val prefs = Prefs(context)
        val token = prefs.token ?: return
        prefs.lastAttempt = System.currentTimeMillis()
        try {
            val viewer = TibberApi.fetchViewer(token)
            val home = Parser.pickHome(viewer, prefs.homeId)
            var liveJson: JSONObject? = null
            val wsUrl = viewer.optStringOrNull("websocketSubscriptionUrl")
            if (home != null && home.realtimeEnabled && wsUrl != null) {
                liveJson = try {
                    TibberApi.fetchLiveMeasurement(token, wsUrl, home.id)
                } catch (e: Exception) {
                    null
                }
            }
            prefs.cachedViewer = viewer.toString()
            prefs.cachedLive = liveJson?.toString()
            prefs.fetchedAt = System.currentTimeMillis()
            prefs.lastError = null
        } catch (e: Exception) {
            prefs.lastError = e.message ?: e.javaClass.simpleName
        }
    }

    fun load(context: Context): DashboardState {
        val prefs = Prefs(context)
        val viewer = prefs.cachedViewer?.let { runCatching { JSONObject(it) }.getOrNull() }
        val home = viewer?.let { Parser.pickHome(it, prefs.homeId) }
        val live = prefs.cachedLive?.let { runCatching { Parser.parseLive(JSONObject(it)) }.getOrNull() }
        return DashboardState(
            home = home,
            live = live,
            fetchedAt = prefs.fetchedAt,
            error = prefs.lastError,
            hasToken = prefs.token != null,
        )
    }
}
