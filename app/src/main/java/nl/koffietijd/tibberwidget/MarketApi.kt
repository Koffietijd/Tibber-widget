package nl.koffietijd.tibberwidget

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Europese day-ahead marktprijzen (EPEX, bidding zone NL) via energy-charts.info (Fraunhofer ISE),
 * licentie CC BY 4.0. Geen account of sleutel nodig.
 */
object MarketApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Haalt gisteren t/m (indien gepubliceerd) morgen op; gebruikt voor omrekening en prijzen van morgen. */
    fun fetch(days: Long = 1): JSONObject {
        val today = LocalDate.now(ZoneOffset.UTC)
        val url = "https://api.energy-charts.info/price?bzn=NL&start=${today.minusDays(days)}&end=${today.plusDays(2)}"
        val request = Request.Builder().url(url).header("User-Agent", "TibberWallWidget/1.0 (Android)").build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw TibberException("Marktprijzen: HTTP ${resp.code}")
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    fun parse(json: JSONObject?): List<MarketPoint> {
        if (json == null) return emptyList()
        val times: JSONArray = json.optJSONArray("unix_seconds") ?: return emptyList()
        val prices: JSONArray = json.optJSONArray("price") ?: return emptyList()
        val n = minOf(times.length(), prices.length())
        return (0 until n).mapNotNull { i ->
            if (prices.isNull(i)) return@mapNotNull null
            val p = prices.optDouble(i)
            if (p.isNaN()) null else MarketPoint(Instant.ofEpochSecond(times.optLong(i)), p)
        }
    }
}
