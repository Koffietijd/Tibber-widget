package nl.koffietijd.tibberwidget

import android.content.Context
import org.json.JSONObject

data class DashboardState(
    val home: Home?,
    val live: Live?,
    val market: List<MarketPoint>,
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
            mergeProduction(viewer, token)
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
        refreshMarket(prefs)
    }

    /** Marktprijzen hoogstens elk half uur ophalen; mislukken is niet erg (dan alleen Tibber-prijzen). */
    private fun refreshMarket(prefs: Prefs) {
        if (System.currentTimeMillis() - prefs.marketFetchedAt < 30 * 60 * 1000L) return
        try {
            prefs.cachedMarket = MarketApi.fetch().toString()
            prefs.marketFetchedAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    /** Voegt teruglevering per uur toe aan de woningen in [viewer]. */
    private fun mergeProduction(viewer: JSONObject, token: String) {
        val prod = try {
            TibberApi.fetchProduction(token)
        } catch (e: Exception) {
            return
        }
        val byId = HashMap<String, JSONObject>()
        prod.optJSONArray("homes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                h.optJSONObject("production")?.let { byId[h.optString("id")] = it }
            }
        }
        viewer.optJSONArray("homes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                byId[h.optString("id")]?.let { h.put("production", it) }
            }
        }
    }

    fun load(context: Context): DashboardState {
        val prefs = Prefs(context)
        val viewer = prefs.cachedViewer?.let { runCatching { JSONObject(it) }.getOrNull() }
        val home = viewer?.let { Parser.pickHome(it, prefs.homeId) }
        val live = prefs.cachedLive?.let { runCatching { Parser.parseLive(JSONObject(it)) }.getOrNull() }
        val market = prefs.cachedMarket?.let { runCatching { MarketApi.parse(JSONObject(it)) }.getOrNull() }.orEmpty()
        return DashboardState(
            home = home,
            live = live,
            market = market,
            fetchedAt = prefs.fetchedAt,
            error = prefs.lastError,
            hasToken = prefs.token != null,
        )
    }
}
