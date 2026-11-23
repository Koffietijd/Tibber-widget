package nl.koffietijd.tibberwidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

data class PricePoint(
    val start: OffsetDateTime,
    val total: Double,
    val energy: Double?,
    val tax: Double?,
    val level: String?,
)

data class Usage(
    val from: OffsetDateTime,
    val kwh: Double?,
    val cost: Double?,
)

data class Live(
    val timestamp: OffsetDateTime?,
    val power: Double?,
    val powerProduction: Double?,
    val accumulatedConsumption: Double?,
    val accumulatedProduction: Double?,
    val accumulatedCost: Double?,
    val accumulatedReward: Double?,
    val minPower: Double?,
    val averagePower: Double?,
    val maxPower: Double?,
    val currency: String?,
)

data class Home(
    val id: String,
    val name: String,
    val address: String?,
    val realtimeEnabled: Boolean,
    val currency: String,
    /** Alle bekende prijzen (vandaag + morgen), gesorteerd. */
    val prices: List<PricePoint>,
    val hourly: List<Usage>,
    val daily: List<Usage>,
    /** Teruglevering per uur (kWh) met opbrengst (€). */
    val production: List<Usage>,
)

data class HomeRef(val id: String, val label: String)

object Parser {

    fun homeRefs(viewer: JSONObject): List<HomeRef> {
        val homes = viewer.optJSONArray("homes") ?: return emptyList()
        return (0 until homes.length()).mapNotNull { i ->
            val h = homes.optJSONObject(i) ?: return@mapNotNull null
            HomeRef(h.getString("id"), homeName(h))
        }
    }

    /** Kiest de ingestelde woning, of anders de eerste met een actief prijsabonnement. */
    fun pickHome(viewer: JSONObject, preferredId: String?): Home? {
        val homes = viewer.optJSONArray("homes") ?: return null
        val list = (0 until homes.length()).mapNotNull { homes.optJSONObject(it) }
        val chosen = list.firstOrNull { it.optString("id") == preferredId }
            ?: list.firstOrNull { priceInfo(it) != null }
            ?: list.firstOrNull()
            ?: return null
        return parseHome(chosen)
    }

    private fun priceInfo(h: JSONObject): JSONObject? =
        h.optJSONObject("currentSubscription")?.optJSONObject("priceInfo")

    private fun homeName(h: JSONObject): String {
        val nick = h.optStringOrNull("appNickname")
        val addr = h.optJSONObject("address")?.optStringOrNull("address1")
        return nick ?: addr ?: "Thuis"
    }

    private fun parseHome(h: JSONObject): Home {
        val pi = priceInfo(h)
        val prices = ArrayList<PricePoint>()
        var currency = "EUR"
        if (pi != null) {
            pi.optJSONObject("current")?.optStringOrNull("currency")?.let { currency = it }
            prices += parsePrices(pi.optJSONArray("today"))
            prices += parsePrices(pi.optJSONArray("tomorrow"))
        }
        val addressObj = h.optJSONObject("address")
        val address = listOfNotNull(
            addressObj?.optStringOrNull("address1"),
            addressObj?.optStringOrNull("city"),
        ).joinToString(", ").ifBlank { null }
        return Home(
            id = h.getString("id"),
            name = homeName(h),
            address = address,
            realtimeEnabled = h.optJSONObject("features")?.optBoolean("realTimeConsumptionEnabled") == true,
            currency = currency,
            prices = prices.distinctBy { it.start }.sortedBy { it.start },
            hourly = parseUsage(h.optJSONObject("hourly")?.optJSONArray("nodes")),
            daily = parseUsage(h.optJSONObject("daily")?.optJSONArray("nodes")),
            production = parseUsage(h.optJSONObject("production")?.optJSONArray("nodes"), "production", "profit"),
        )
    }

    private fun parsePrices(arr: JSONArray?): List<PricePoint> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = o.optStringOrNull("startsAt")?.let(::parseTime) ?: return@mapNotNull null
            val total = o.optDoubleOrNull("total") ?: return@mapNotNull null
            PricePoint(start, total, o.optDoubleOrNull("energy"), o.optDoubleOrNull("tax"), o.optStringOrNull("level"))
        }
    }

    private fun parseUsage(arr: JSONArray?, amountKey: String = "consumption", moneyKey: String = "cost"): List<Usage> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val from = o.optStringOrNull("from")?.let(::parseTime) ?: return@mapNotNull null
            Usage(from, o.optDoubleOrNull(amountKey), o.optDoubleOrNull(moneyKey))
        }.sortedBy { it.from }
    }

    fun parseLive(o: JSONObject?): Live? {
        if (o == null) return null
        return Live(
            timestamp = o.optStringOrNull("timestamp")?.let(::parseTime),
            power = o.optDoubleOrNull("power"),
            powerProduction = o.optDoubleOrNull("powerProduction"),
            accumulatedConsumption = o.optDoubleOrNull("accumulatedConsumption"),
            accumulatedProduction = o.optDoubleOrNull("accumulatedProduction"),
            accumulatedCost = o.optDoubleOrNull("accumulatedCost"),
            accumulatedReward = o.optDoubleOrNull("accumulatedReward"),
            minPower = o.optDoubleOrNull("minPower"),
            averagePower = o.optDoubleOrNull("averagePower"),
            maxPower = o.optDoubleOrNull("maxPower"),
            currency = o.optStringOrNull("currency"),
        )
    }

    private fun parseTime(s: String): OffsetDateTime? = try {
        OffsetDateTime.parse(s)
    } catch (e: Exception) {
        null
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
