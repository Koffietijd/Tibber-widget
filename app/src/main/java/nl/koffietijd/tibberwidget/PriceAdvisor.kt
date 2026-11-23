package nl.koffietijd.tibberwidget

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** Day-ahead marktprijs (EPEX, bidding zone NL) in EUR/MWh. */
data class MarketPoint(val start: Instant, val eurMwh: Double)

enum class SlotKind {
    /** Prijs zoals Tibber hem rekent. */
    TIBBER,
    /** Gepubliceerde day-ahead marktprijs, omgerekend naar Tibber-prijs (nog niet bij Tibber bekend). */
    MARKET,
}

data class Slot(val start: Instant, val minutes: Int, val price: Double, val level: String?, val kind: SlotKind) {
    val end: Instant get() = start.plusSeconds(minutes * 60L)
}

/** Lineaire omrekening: Tibber-prijs (€/kWh) = a × marktprijs (€/kWh) + b. */
data class PriceFit(val a: Double, val b: Double, val samples: Int)

data class Timeline(val slots: List<Slot>, val fit: PriceFit?)

enum class Advice { GREEN, ORANGE, RED }

data class Recommendation(
    val advice: Advice,
    val nowAvg: Double,
    val bestStart: Instant,
    val bestAvg: Double,
    val bestKind: SlotKind,
    /** Besparing per kWh als je wacht tot het goedkoopste moment. */
    val savingPerKwh: Double,
    val durationHours: Int,
)

object PriceAdvisor {

    /**
     * Bouwt een tijdlijn van maximaal [hours] uur vooruit met alleen gepubliceerde prijzen:
     * eerst Tibber-prijzen, daarna day-ahead marktprijzen die Tibber nog niet toont (omgerekend).
     * Er wordt bewust geen verwachting op basis van het verleden gemaakt.
     */
    fun build(prices: List<PricePoint>, market: List<MarketPoint>, now: ZonedDateTime, hours: Int = 48): Timeline {
        val horizonEnd = now.truncatedTo(ChronoUnit.HOURS).plusHours(hours.toLong() + 1).toInstant()
        val slots = ArrayList<Slot>()

        val tibberLen = slotMinutes(prices.map { it.start.toInstant() })
        prices.forEach { slots += Slot(it.start.toInstant(), tibberLen, it.total, it.level, SlotKind.TIBBER) }
        val coveredUntil = slots.maxOfOrNull { it.end } ?: now.toInstant()

        val fit = fit(prices, market)
        if (fit == null) return Timeline(slots.sortedBy { it.start }, fit)

        // Marktprijzen die al gepubliceerd zijn maar nog niet bij Tibber staan.
        val marketSorted = market.sortedBy { it.start }
        val marketLen = slotMinutes(marketSorted.map { it.start })
        marketSorted.filter { !it.start.isBefore(coveredUntil) && it.start.isBefore(horizonEnd) }.forEach {
            slots += Slot(it.start, marketLen, convert(fit, it.eurMwh), null, SlotKind.MARKET)
        }

        return Timeline(slots.sortedBy { it.start }, fit)
    }

    /** Kleinste-kwadratenfit van Tibber-prijs op marktprijs voor dezelfde tijdvakken. */
    fun fit(prices: List<PricePoint>, market: List<MarketPoint>): PriceFit? {
        if (prices.isEmpty() || market.isEmpty()) return null
        val byStart = market.associateBy { it.start }
        // Marktdata per uur als fallback wanneer Tibber per kwartier en de markt per uur rekent (of andersom).
        val hourMean = market.groupBy { it.start.truncatedTo(ChronoUnit.HOURS) }.mapValues { e -> e.value.map { it.eurMwh }.average() }
        val pairs = prices.mapNotNull { p ->
            val s = p.start.toInstant()
            val m = byStart[s]?.eurMwh ?: hourMean[s.truncatedTo(ChronoUnit.HOURS)] ?: return@mapNotNull null
            (m / 1000.0) to p.total
        }
        if (pairs.size < 4) return null
        val n = pairs.size.toDouble()
        val mx = pairs.sumOf { it.first } / n
        val my = pairs.sumOf { it.second } / n
        val sxx = pairs.sumOf { (it.first - mx) * (it.first - mx) }
        val sxy = pairs.sumOf { (it.first - mx) * (it.second - my) }
        var a = if (sxx > 1e-9) sxy / sxx else Double.NaN
        // Valt de fit buiten redelijke grenzen (bijv. vlakke prijzen), gebruik dan alleen btw als factor.
        if (a.isNaN() || a < 0.9 || a > 1.6) a = 1.21
        val b = pairs.map { it.second - a * it.first }.sorted().let { it[it.size / 2] }
        return PriceFit(a, b, pairs.size)
    }

    fun convert(fit: PriceFit, eurMwh: Double) = fit.a * eurMwh / 1000.0 + fit.b

    private fun slotMinutes(starts: List<Instant>): Int {
        val diffs = starts.sorted().zipWithNext { a, b -> Duration.between(a, b).toMinutes() }.filter { it > 0 }
        return (diffs.minOrNull() ?: 60L).toInt().coerceIn(15, 60)
    }

    /**
     * Stoplicht: is het nu een goed moment om een apparaat van [durationHours] uur te starten?
     * Vergelijkt de gemiddelde prijs als je nu start met het goedkoopste startmoment in de horizon.
     */
    fun recommend(timeline: Timeline, now: Instant, durationHours: Int = 2): Recommendation? {
        // Zet alles om naar een kwartierraster zodat vensters op elk kwartier kunnen starten.
        val grid = ArrayList<Pair<Instant, Slot>>()
        timeline.slots.forEach { s ->
            var t = s.start
            while (t.isBefore(s.end)) {
                grid += t to s
                t = t.plusSeconds(15 * 60)
            }
        }
        val sorted = grid.distinctBy { it.first }.sortedBy { it.first }
        val startIdx = sorted.indexOfLast { !it.first.isAfter(now) }
        if (startIdx < 0) return null
        val future = sorted.subList(startIdx, sorted.size)
        val n = durationHours * 4
        if (future.size < n) return null

        data class Window(val start: Instant, val avg: Double, val kind: SlotKind)
        val windows = ArrayList<Window>()
        for (i in 0..future.size - n) {
            val w = future.subList(i, i + n)
            // Alleen aaneengesloten vensters.
            if (Duration.between(w.first().first, w.last().first).toMinutes() != (n - 1) * 15L) continue
            val kind = w.maxOf { it.second.kind } // slechtste zekerheid in het venster
            windows += Window(w.first().first, w.map { it.second.price }.average(), kind)
        }
        if (windows.isEmpty()) return null
        val nowW = windows.first()
        // Omgerekende marktprijzen kunnen iets afwijken van de latere Tibber-prijs: kleine marge.
        fun effective(w: Window) = w.avg + if (w.kind == SlotKind.MARKET) 0.005 else 0.0
        val best = windows.minBy { effective(it) }
        val saving = max(0.0, nowW.avg - best.avg)
        val effSaving = max(0.0, effective(nowW) - effective(best))
        val rank = windows.count { it.avg < nowW.avg }.toDouble() / windows.size

        val advice = when {
            effSaving <= 0.02 || rank <= 0.2 -> Advice.GREEN
            effSaving >= 0.06 && rank >= 0.5 -> Advice.RED
            else -> Advice.ORANGE
        }
        return Recommendation(advice, nowW.avg, best.start, best.avg, best.kind, saving, durationHours)
    }
}
