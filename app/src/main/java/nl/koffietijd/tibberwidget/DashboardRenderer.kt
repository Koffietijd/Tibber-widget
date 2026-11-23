package nl.koffietijd.tibberwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Tekent het complete dashboard als één bitmap, schaalbaar naar elk formaat (staand en liggend). */
class DashboardRenderer(@Suppress("unused") private val context: Context) {

    private val locale = Locale("nl", "NL")
    private val zone: ZoneId = ZoneId.systemDefault()
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)

    // Kleuren
    private val bg = Color.parseColor("#0B0F14")
    private val card = Color.parseColor("#151B23")
    private val textMain = Color.parseColor("#E6EDF3")
    private val textSub = Color.parseColor("#8B949E")
    private val grid = Color.parseColor("#2A323D")
    private val accent = Color.parseColor("#3FB6FF")
    private val errorColor = Color.parseColor("#F0524F")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val boldTf = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val normalTf = Typeface.create("sans-serif", Typeface.NORMAL)

    private var u = 1f // basiseenheid (≈1% van de kleinste zijde)
    private lateinit var canvas: Canvas
    private lateinit var now: ZonedDateTime

    fun render(width: Int, height: Int, state: DashboardState): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bmp)
        canvas.drawColor(bg)
        now = ZonedDateTime.now(zone)
        u = min(width, height) / 100f

        val home = state.home
        when {
            !state.hasToken -> message(width, height, "Tibber dashboard", "Tik hier om je Tibber API-token in te stellen")
            home == null && state.error != null -> message(width, height, "Kan Tibber niet bereiken", state.error)
            home == null -> message(width, height, "Gegevens ophalen…", "Een moment geduld, de eerste update komt eraan")
            else -> dashboard(width, height, home, state)
        }
        return bmp
    }

    // ---------------------------------------------------------------- layout

    private fun dashboard(w: Int, h: Int, home: Home, state: DashboardState) {
        val pad = 3f * u
        val gap = 2.2f * u
        val full = RectF(pad, pad, w - pad, h - pad)
        val live = state.live?.takeIf { isLiveFresh(it) }
        val landscape = w > h * 1.15f
        val timeline = PriceAdvisor.build(home.prices, state.market, now)
        val rec = PriceAdvisor.recommend(timeline, now.toInstant(), durationHours = 2)
        val solar = hasSolar(home, live)
        val showLiveRow = live != null || solar

        val headerH = if (landscape) 9f * u else 8f * u
        val footerH = 3.6f * u
        header(RectF(full.left, full.top, full.right, full.top + headerH), home, state)
        footer(RectF(full.left, full.bottom - footerH, full.right, full.bottom), state)
        val body = RectF(full.left, full.top + headerH + gap, full.right, full.bottom - footerH - gap)

        if (landscape) {
            val leftW = body.width() * 0.45f
            val left = RectF(body.left, body.top, body.left + leftW, body.bottom)
            val right = RectF(left.right + gap, body.top, body.right, body.bottom)
            val parts = stack(left, gap, listOf(3.4f, 2.6f, if (showLiveRow) 2.6f else 0f, 2f))
            priceCard(parts[0], home)
            adviceCard(parts[1], rec, timeline, home.currency)
            if (showLiveRow) liveRow(parts[2], gap, live, home, solar)
            tiles(parts[3], gap, home, live, solar)
            priceChart(right, home, timeline, rec)
        } else {
            val parts = stack(body, gap, listOf(2.2f, 1.8f, if (showLiveRow) 1.7f else 0f, 3.8f, 1.4f))
            priceCard(parts[0], home)
            adviceCard(parts[1], rec, timeline, home.currency)
            if (showLiveRow) liveRow(parts[2], gap, live, home, solar)
            priceChart(parts[3], home, timeline, rec)
            tiles(parts[4], gap, home, live, solar)
        }
    }

    /** Verdeelt een rechthoek verticaal volgens gewichten; gewicht 0 = overslaan. */
    private fun stack(r: RectF, gap: Float, weights: List<Float>): List<RectF> {
        val active = weights.count { it > 0f }
        val avail = r.height() - gap * (active - 1)
        val total = weights.sum()
        var y = r.top
        return weights.map { wgt ->
            if (wgt <= 0f) RectF(r.left, y, r.right, y) else {
                val hh = avail * wgt / total
                RectF(r.left, y, r.right, y + hh).also { y += hh + gap }
            }
        }
    }

    // ---------------------------------------------------------------- secties

    private fun header(r: RectF, home: Home, state: DashboardState) {
        val leftW = r.width() * 0.62f
        text(home.name, RectF(r.left, r.top, r.left + leftW, r.top + r.height() * 0.58f), textMain, bold = true)
        text(
            now.format(dayFmt).replaceFirstChar { it.titlecase(locale) },
            RectF(r.left, r.top + r.height() * 0.62f, r.left + leftW, r.bottom), textSub
        )
        val updated = if (state.fetchedAt > 0) time(state.fetchedAt) else "–"
        text("Bijgewerkt", RectF(r.right - r.width() * 0.35f, r.top + r.height() * 0.05f, r.right, r.top + r.height() * 0.38f), textSub, align = Paint.Align.RIGHT)
        text(updated, RectF(r.right - r.width() * 0.35f, r.top + r.height() * 0.4f, r.right, r.bottom), textMain, bold = true, align = Paint.Align.RIGHT)
    }

    private fun footer(r: RectF, state: DashboardState) {
        val err = state.error
        if (err != null) {
            text("⚠ Laatste update mislukt: $err", r, errorColor, align = Paint.Align.CENTER)
        } else {
            text("Ververst automatisch elke 15 minuten · tik om nu te verversen", r, textSub, align = Paint.Align.CENTER)
        }
    }

    private fun priceCard(r: RectF, home: Home) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val prices = home.prices
        val current = currentPrice(prices)
        val split = inner.left + inner.width() * 0.56f
        val left = RectF(inner.left, inner.top, split - 2f * u, inner.bottom)
        val right = RectF(split + 2f * u, inner.top, inner.right, inner.bottom)

        // Links: huidige prijs
        val slot = current?.let { p ->
            val end = nextStart(prices, p)
            "${p.start.atZoneSameInstant(zone).format(hhmm)}–${end.format(hhmm)}"
        }
        text("Stroomprijs nu" + (slot?.let { " · $it" } ?: ""), rowOf(left, 0f, 0.17f), textSub)
        if (current == null) {
            text("Geen prijs", rowOf(left, 0.2f, 0.62f), textMain, bold = true)
        } else {
            val priceRect = rowOf(left, 0.19f, 0.66f)
            val main = money(current.total, home.currency, 3)
            val used = text(main, RectF(priceRect.left, priceRect.top, priceRect.right - priceRect.width() * 0.18f, priceRect.bottom), levelColor(current.level, textMain), bold = true)
            text("/kWh", RectF(priceRect.left + used + u, priceRect.top + priceRect.height() * 0.45f, priceRect.right, priceRect.bottom - priceRect.height() * 0.08f), textSub)
            pill(levelLabel(current.level), rowOf(left, 0.7f, 0.86f), levelColor(current.level, accent))
            val parts = listOfNotNull(
                current.energy?.let { "energie ${money(it, home.currency, 3)}" },
                current.tax?.let { "belasting ${money(it, home.currency, 3)}" },
            )
            if (parts.isNotEmpty()) text(parts.joinToString(" · "), rowOf(left, 0.88f, 1f), textSub)
        }

        // Rechts: statistieken van vandaag
        val today = prices.filter { it.start.atZoneSameInstant(zone).toLocalDate() == now.toLocalDate() }
        val rows = ArrayList<Pair<String, String>>()
        if (today.isNotEmpty()) {
            val minP = today.minBy { it.total }
            val maxP = today.maxBy { it.total }
            rows += "Laagste vandaag" to "${money(minP.total, home.currency)}  ${minP.start.atZoneSameInstant(zone).format(hhmm)}"
            rows += "Gemiddeld vandaag" to money(today.map { it.total }.average(), home.currency)
            rows += "Hoogste vandaag" to "${money(maxP.total, home.currency)}  ${maxP.start.atZoneSameInstant(zone).format(hhmm)}"
        }
        val tomorrow = prices.filter { it.start.atZoneSameInstant(zone).toLocalDate() == now.toLocalDate().plusDays(1) }
        if (tomorrow.isNotEmpty()) rows += "Gemiddeld morgen" to money(tomorrow.map { it.total }.average(), home.currency)
        if (rows.isEmpty()) return
        val rowH = right.height() / rows.size
        rows.forEachIndexed { i, (label, value) ->
            val top = right.top + i * rowH
            text(label, RectF(right.left, top, right.right, top + rowH * 0.42f), textSub)
            text(value, RectF(right.left, top + rowH * 0.42f, right.right, top + rowH * 0.92f), textMain, bold = true)
        }
    }

    /** Stoplicht: is het nu een goed moment voor wasmachine of droger? */
    private fun adviceCard(r: RectF, rec: Recommendation?, timeline: Timeline, currency: String) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val green = Color.parseColor("#00C26E")
        val orange = Color.parseColor("#F2A33A")
        val red = Color.parseColor("#F0524F")

        // Stoplicht-behuizing
        val lightH = inner.height()
        val lightW = min(lightH * 0.4f, inner.width() * 0.2f)
        val housing = RectF(inner.left, inner.top, inner.left + lightW, inner.top + lightH)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#05080B")
        canvas.drawRoundRect(housing, lightW * 0.3f, lightW * 0.3f, paint)
        val lampR = min(lightW * 0.34f, lightH / 6.6f)
        val lamps = listOf(Advice.RED to red, Advice.ORANGE to orange, Advice.GREEN to green)
        lamps.forEachIndexed { i, (adv, color) ->
            val cy = housing.top + lightH * (i + 0.5f) / 3f
            val cx = housing.centerX()
            val on = rec?.advice == adv
            if (on) {
                paint.color = withAlpha(color, 70)
                canvas.drawCircle(cx, cy, lampR * 1.35f, paint)
            }
            paint.color = if (on) color else withAlpha(color, 45)
            canvas.drawCircle(cx, cy, lampR, paint)
        }

        val tx = RectF(housing.right + 3f * u, inner.top, inner.right, inner.bottom)
        if (rec == null) {
            text("Wasmachine & droger", rowOf(tx, 0f, 0.22f), textSub)
            text("Geen advies", rowOf(tx, 0.26f, 0.56f), textMain, bold = true)
            text("Er zijn nog geen prijzen voor de komende uren", rowOf(tx, 0.62f, 0.8f), textSub)
            return
        }
        val color = when (rec.advice) {
            Advice.GREEN -> green
            Advice.ORANGE -> orange
            Advice.RED -> red
        }
        val title = when (rec.advice) {
            Advice.GREEN -> "Nu aanzetten"
            Advice.ORANGE -> "Kan, maar wachten loont"
            Advice.RED -> "Beter wachten"
        }
        val horizon = timeline.slots.maxOfOrNull { it.end }
        val bestZ = rec.bestStart.atZone(zone)
        val bestIsNow = Duration.between(now.toInstant(), rec.bestStart).toMinutes() < 15

        text("Wasmachine & droger · programma van ${rec.durationHours} uur", rowOf(tx, 0f, 0.17f), textSub)
        text(title, rowOf(tx, 0.19f, 0.46f), color, bold = true)
        text("Nu starten: gemiddeld ${money(rec.nowAvg, currency)}/kWh", rowOf(tx, 0.5f, 0.64f), textMain)
        if (bestIsNow || rec.savingPerKwh < 0.005) {
            text("Dit is het goedkoopste moment" + (horizon?.let { " tot ${untilLabel(it)}" } ?: ""), rowOf(tx, 0.67f, 0.81f), textMain)
        } else {
            val marker = if (rec.bestKind == SlotKind.MARKET) " (marktprijs)" else ""
            text("Goedkoopst: ${dayWord(bestZ)} ${bestZ.format(hhmm)} · ${money(rec.bestAvg, currency)}/kWh$marker", rowOf(tx, 0.67f, 0.81f), textMain, bold = true)
            val wash = rec.savingPerKwh * WASH_KWH
            val dry = rec.savingPerKwh * DRYER_KWH
            text("Wachten bespaart ± ${money(wash, currency)} per wasbeurt · ${money(dry, currency)} per droogbeurt", rowOf(tx, 0.85f, 1f), textSub)
            return
        }
        text("Gebaseerd op bekende prijzen" + (horizon?.let { " tot ${untilLabel(it)}" } ?: ""), rowOf(tx, 0.85f, 1f), textSub)
    }

    /** Live verbruik en zonnepanelen naast elkaar (of één van beide over de volle breedte). */
    private fun liveRow(r: RectF, gap: Float, live: Live?, home: Home, solar: Boolean) {
        if (live != null && solar) {
            val half = (r.width() - gap) / 2
            liveCard(RectF(r.left, r.top, r.left + half, r.bottom), live, home.currency)
            solarCard(RectF(r.right - half, r.top, r.right, r.bottom), live, home)
        } else if (live != null) {
            liveCard(r, live, home.currency)
        } else {
            solarCard(r, null, home)
        }
    }

    private fun liveCard(r: RectF, live: Live, fallbackCurrency: String) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val currency = live.currency ?: fallbackCurrency
        val label = "Verbruik nu" + (live.timestamp?.let { " · ${it.atZoneSameInstant(zone).format(hhmm)}" } ?: "")
        text(label, rowOf(inner, 0f, 0.2f), textSub)
        text(watts(live.power), rowOf(inner, 0.22f, 0.58f), accent, bold = true)
        live.accumulatedConsumption?.let {
            text("Vandaag ${kwh(it)}" + (live.accumulatedCost?.let { c -> " · ${money(c, currency)}" } ?: ""), rowOf(inner, 0.62f, 0.8f), textMain, bold = true)
        }
        if (live.minPower != null || live.maxPower != null) {
            text("min ${wattsShort(live.minPower)} · gem ${wattsShort(live.averagePower)} · max ${watts(live.maxPower)}", rowOf(inner, 0.83f, 1f), textSub)
        }
    }

    private fun solarCard(r: RectF, live: Live?, home: Home) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val sun = Color.parseColor("#FFC93C")
        val label = "Zonnepanelen · teruglevering"
        text(label, rowOf(inner, 0f, 0.2f), textSub)
        val nowW = live?.powerProduction
        text(if (nowW != null) watts(nowW) else "–", rowOf(inner, 0.22f, 0.58f), sun, bold = true)

        val today = now.toLocalDate()
        val hourlyToday = home.production.filter { it.from.atZoneSameInstant(zone).toLocalDate() == today && it.kwh != null }
        val kwhToday = live?.accumulatedProduction ?: hourlyToday.takeIf { it.isNotEmpty() }?.sumOf { it.kwh ?: 0.0 }
        val rewardToday = live?.accumulatedReward ?: hourlyToday.takeIf { it.isNotEmpty() }?.sumOf { it.cost ?: 0.0 }
        if (kwhToday != null) {
            text("Vandaag ${kwh(kwhToday)}" + (rewardToday?.let { " · ${money(it, home.currency)}" } ?: ""), rowOf(inner, 0.62f, 0.8f), textMain, bold = true)
        }
        text("teruggeleverd aan het net", rowOf(inner, 0.83f, 1f), textSub)
    }

    private fun priceChart(r: RectF, home: Home, timeline: Timeline, rec: Recommendation?) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val start = now.truncatedTo(ChronoUnit.HOURS).minusHours(3)
        val limit = now.plusHours(48)
        val slots = timeline.slots.filter { it.end.isAfter(start.toInstant()) && it.start.isBefore(limit.toInstant()) }
        val endInstant = slots.maxOfOrNull { it.end } ?: now.plusHours(1).toInstant()
        val end = ZonedDateTime.ofInstant(endInstant, zone)
        val hasMarket = slots.any { it.kind == SlotKind.MARKET }

        val titleRect = RectF(inner.left, inner.top, inner.right, inner.top + 7f * u)
        val hours = Duration.between(now, end).toHours()
        text("Prijzen komende ${max(1L, hours)} uur", RectF(titleRect.left, titleRect.top, titleRect.left + titleRect.width() * 0.6f, titleRect.bottom), textMain, bold = true)
        val tomorrowKnown = slots.any { it.start.atZone(zone).toLocalDate() == now.toLocalDate().plusDays(1) }
        if (!tomorrowKnown) {
            text("Morgen bekend rond 13:00", RectF(titleRect.left + titleRect.width() * 0.62f, titleRect.top + titleRect.height() * 0.2f, titleRect.right, titleRect.bottom), textSub, align = Paint.Align.RIGHT)
        }
        val legendRect = RectF(inner.left, titleRect.bottom + 1f * u, inner.right, titleRect.bottom + 4.2f * u)
        legend(legendRect, hasMarket)
        if (slots.isEmpty()) {
            text("Geen prijsgegevens", RectF(inner.left, legendRect.bottom, inner.right, inner.bottom), textSub, align = Paint.Align.CENTER)
            return
        }

        val plot = RectF(inner.left + 10f * u, legendRect.bottom + 3.5f * u, inner.right, inner.bottom - 4.5f * u)
        val minV = min(0.0, slots.minOf { it.price })
        val maxV = niceCeil(slots.maxOf { it.price })
        val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (plot.bottom - (v - minV) / range * plot.height()).toFloat()

        for (i in 0..4) {
            val v = minV + range * i / 4
            val yy = y(v)
            line(plot.left, yy, plot.right, yy, grid, 0.15f * u)
            text(money(v, home.currency, 2), RectF(inner.left, yy - 2f * u, plot.left - 1.2f * u, yy + 2f * u), textSub, align = Paint.Align.RIGHT, maxSize = 2.6f * u)
        }

        val totalSec = Duration.between(start, end).seconds.toFloat().coerceAtLeast(1f)
        fun x(t: java.time.Instant) = plot.left + Duration.between(start.toInstant(), t).seconds / totalSec * plot.width()

        // Advies-venster als groene band achter de staven.
        if (rec != null) {
            val bx0 = x(rec.bestStart).coerceAtLeast(plot.left)
            val bx1 = x(rec.bestStart.plusSeconds(rec.durationHours * 3600L)).coerceAtMost(plot.right)
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Color.parseColor("#00C26E"), 40)
            canvas.drawRect(bx0, plot.top, bx1, plot.bottom, paint)
            text("goedkoopst", RectF(bx0, plot.bottom - 2.6f * u, max(bx1, bx0 + 14f * u), plot.bottom - 0.2f * u), Color.parseColor("#00C26E"), bold = true, maxSize = 2.4f * u)
        }

        val avg = slots.map { it.price }.average()
        val currentStart = slots.lastOrNull { !it.start.isAfter(now.toInstant()) }?.start
        slots.forEach { s ->
            val barW = s.minutes * 60f / totalSec * plot.width()
            val gapW = if (barW > 4f) barW * 0.18f else 0f
            val x0 = max(plot.left, x(s.start) + gapW / 2)
            val x1 = min(plot.right, x(s.start) + barW - gapW / 2)
            if (x1 <= x0) return@forEach
            val level = s.level ?: relativeLevel(s.price, avg)
            var color = levelColor(level, accent)
            val isPast = !s.end.isAfter(now.toInstant())
            if (isPast) color = withAlpha(color, 80)
            if (s.kind == SlotKind.MARKET) color = withAlpha(color, 150)
            val top = y(max(s.price, 0.0))
            val bottom = y(min(s.price, 0.0))
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawRect(x0, top, max(x1, x0 + 1f), max(bottom, top + 1f), paint)
            if (s.start == currentStart) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.4f * u
                paint.color = Color.WHITE
                canvas.drawRect(x0, top, max(x1, x0 + 1f), bottom, paint)
            }
        }

        // "Nu"-lijn
        val nx = x(now.toInstant())
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 0.25f * u
        paint.pathEffect = DashPathEffect(floatArrayOf(u, u), 0f)
        canvas.drawLine(nx, plot.top, nx, plot.bottom, paint)
        paint.pathEffect = null
        text("nu", RectF(nx - 5f * u, plot.top - 2.4f * u, nx + 5f * u, plot.top), Color.WHITE, align = Paint.Align.CENTER, bold = true)

        // Daggrenzen
        var midnight = start.toLocalDate().plusDays(1).atStartOfDay(zone)
        while (midnight.isBefore(end)) {
            val mx = x(midnight.toInstant())
            line(mx, plot.top, mx, plot.bottom + 1f * u, textSub, 0.2f * u)
            text(dayWord(midnight), RectF(mx + 0.8f * u, plot.top, mx + 16f * u, plot.top + 2.8f * u), textSub)
            midnight = midnight.plusDays(1)
        }

        // X-as labels
        val stepH = when {
            hours > 30 -> 6
            hours > 14 -> 3
            else -> 2
        }
        var t = start
        while (t.isBefore(end)) {
            if (t.hour % stepH == 0) {
                val xx = x(t.toInstant())
                text(t.format(DateTimeFormatter.ofPattern("HH", locale)), RectF(xx - 4f * u, plot.bottom + 0.8f * u, xx + 4f * u, inner.bottom), textSub, align = Paint.Align.CENTER, maxSize = 2.8f * u)
            }
            t = t.plusHours(1)
        }
    }

    private fun tiles(r: RectF, gap: Float, home: Home, live: Live?, solar: Boolean) {
        val yesterday = now.toLocalDate().minusDays(1)
        fun sumFor(list: List<Usage>, day: java.time.LocalDate): Pair<Double, Double>? =
            list.filter { it.kwh != null && it.from.atZoneSameInstant(zone).toLocalDate() == day }
                .takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.kwh ?: 0.0 } to l.sumOf { it.cost ?: 0.0 } }

        val usedYesterday = sumFor(home.daily, yesterday) ?: sumFor(home.hourly, yesterday)
        data class Tile(val label: String, val main: String, val sub: String, val subColor: Int)
        val items = ArrayList<Tile>()
        items += Tile("Gisteren verbruikt", usedYesterday?.let { kwh(it.first) } ?: "–", usedYesterday?.let { money(it.second, home.currency) } ?: "", accent)
        if (solar) {
            val prodYesterday = sumFor(home.production, yesterday)
            val sun = Color.parseColor("#FFC93C")
            items += Tile("Gisteren teruggeleverd", prodYesterday?.let { kwh(it.first) } ?: "–", prodYesterday?.let { money(it.second, home.currency) } ?: "", sun)
            if (usedYesterday != null && prodYesterday != null) {
                val net = usedYesterday.second - prodYesterday.second
                items += Tile("Netto kosten gisteren", money(net, home.currency), "verbruik − opbrengst", textSub)
            }
        } else if (live?.accumulatedConsumption != null) {
            items += Tile("Vandaag verbruikt", kwh(live.accumulatedConsumption), live.accumulatedCost?.let { money(it, home.currency) } ?: "", accent)
        }
        val tileW = (r.width() - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, tile ->
            val tr = RectF(r.left + i * (tileW + gap), r.top, r.left + i * (tileW + gap) + tileW, r.bottom)
            cardBg(tr)
            val inner = inset(tr, 2.5f * u)
            text(tile.label, rowOf(inner, 0f, 0.26f), textSub)
            text(tile.main, rowOf(inner, 0.3f, 0.68f), textMain, bold = true)
            text(tile.sub, rowOf(inner, 0.72f, 1f), tile.subColor, bold = tile.subColor != textSub)
        }
    }

    private fun hasSolar(home: Home, live: Live?): Boolean =
        home.production.any { (it.kwh ?: 0.0) > 0.0 } ||
            (live?.accumulatedProduction ?: 0.0) > 0.0 ||
            (live?.powerProduction ?: 0.0) > 0.0

    /** Prijsniveau voor omgerekende marktprijzen (Tibber geeft daar zelf geen niveau voor). */
    private fun relativeLevel(price: Double, avg: Double): String {
        val ratio = if (avg > 0) price / avg else 1.0
        return when {
            ratio < 0.7 -> "VERY_CHEAP"
            ratio < 0.9 -> "CHEAP"
            ratio < 1.15 -> "NORMAL"
            ratio < 1.4 -> "EXPENSIVE"
            else -> "VERY_EXPENSIVE"
        }
    }

    private fun dayWord(t: ZonedDateTime): String = when (ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())) {
        0L -> "vandaag"
        1L -> "morgen"
        2L -> "overmorgen"
        else -> t.format(DateTimeFormatter.ofPattern("EEEE", locale))
    }

    /** "morgen 24:00" in plaats van "overmorgen 00:00". */
    private fun untilLabel(end: java.time.Instant): String {
        val z = end.atZone(zone)
        return if (z.hour == 0 && z.minute == 0) "${dayWord(z.minusDays(1))} 24:00" else "${dayWord(z)} ${z.format(hhmm)}"
    }

    private fun message(w: Int, h: Int, title: String, sub: String) {
        val cx = w / 2f
        val cy = h / 2f
        text(title, RectF(6f * u, cy - 12f * u, w - 6f * u, cy - 2f * u), textMain, bold = true, align = Paint.Align.CENTER)
        text(sub, RectF(6f * u, cy + 1f * u, w - 6f * u, cy + 7f * u), textSub, align = Paint.Align.CENTER)
        // bliksem-icoon boven de titel
        val s = 9f * u
        val top = cy - 16f * u - 2f * s
        val pts = listOf(58f to 26f, 36f to 58f, 52f to 58f, 46f to 82f, 72f to 46f, 56f to 46f)
        val path = Path()
        pts.forEachIndexed { i, (px, py) ->
            val x = cx + (px - 54f) / 54f * s * 2f
            val y = top + (py - 26f) / 56f * 2f * s
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = accent
        canvas.drawPath(path, paint)
    }

    // ---------------------------------------------------------------- data helpers

    private fun currentPrice(prices: List<PricePoint>): PricePoint? {
        val instant = now.toInstant()
        return prices.lastOrNull { !it.start.toInstant().isAfter(instant) }
            ?.takeIf { p -> nextStart(prices, p).toInstant().isAfter(instant) }
    }

    private fun nextStart(prices: List<PricePoint>, p: PricePoint): ZonedDateTime {
        val idx = prices.indexOf(p)
        val next = prices.getOrNull(idx + 1)?.start
        val slot = slotSeconds(prices).toLong()
        return (next?.takeIf { Duration.between(p.start, it).seconds <= slot } ?: p.start.plusSeconds(slot))
            .atZoneSameInstant(zone)
    }

    private fun slotSeconds(prices: List<PricePoint>): Float {
        if (prices.size < 2) return 3600f
        val diffs = prices.zipWithNext { a, b -> Duration.between(a.start, b.start).seconds }.filter { it > 0 }
        return (diffs.minOrNull() ?: 3600L).toFloat()
    }

    private fun isLiveFresh(live: Live): Boolean {
        val ts = live.timestamp ?: return true
        return Duration.between(ts.toInstant(), Instant.now()).toMinutes() < 60
    }

    // ---------------------------------------------------------------- tekenhulpen

    /**
     * Tekent tekst zo groot mogelijk binnen [r] (verticaal gecentreerd).
     * @return de gebruikte breedte in pixels.
     */
    private fun text(
        s: String,
        r: RectF,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
        maxSize: Float? = null,
    ): Float {
        if (s.isEmpty() || r.width() <= 0f || r.height() <= 0f) return 0f
        textPaint.typeface = if (bold) boldTf else normalTf
        textPaint.color = color
        textPaint.textAlign = align
        var size = r.height() / 1.15f
        if (maxSize != null) size = min(size, maxSize)
        textPaint.textSize = size
        val width = textPaint.measureText(s)
        if (width > r.width()) {
            size *= r.width() / width
            textPaint.textSize = size
        }
        val fm = textPaint.fontMetrics
        val baseline = r.centerY() - (fm.ascent + fm.descent) / 2f
        val x = when (align) {
            Paint.Align.LEFT -> r.left
            Paint.Align.RIGHT -> r.right
            else -> r.centerX()
        }
        canvas.drawText(s, x, baseline, textPaint)
        return textPaint.measureText(s)
    }

    private fun pill(label: String, r: RectF, color: Int) {
        textPaint.typeface = boldTf
        textPaint.textSize = r.height() * 0.62f
        val tw = textPaint.measureText(label)
        val w = min(r.width(), tw + r.height() * 1.2f)
        val pr = RectF(r.left, r.top, r.left + w, r.bottom)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, 60)
        canvas.drawRoundRect(pr, r.height() / 2, r.height() / 2, paint)
        text(label, inset(pr, r.height() * 0.18f), color, bold = true, align = Paint.Align.CENTER)
    }

    private fun legend(r: RectF, withMarket: Boolean = false) {
        val levels = listOf("VERY_CHEAP", "CHEAP", "NORMAL", "EXPENSIVE", "VERY_EXPENSIVE")
        val labels = listOf("zeer goedkoop", "goedkoop", "normaal", "duur", "zeer duur")
        val extra = if (withMarket) "· licht = marktprijs omgerekend" else null
        val dot = r.height() * 0.55f
        val spacing = 2.5f * u
        // Eén lettergrootte voor alle labels, zodat alles in één regel past.
        textPaint.typeface = normalTf
        var size = r.height() / 1.15f
        textPaint.textSize = size
        val needed = labels.sumOf { textPaint.measureText(it).toDouble() }.toFloat() + levels.size * (dot + 0.8f * u) + (levels.size - 1) * spacing +
            (extra?.let { textPaint.measureText(it) + spacing } ?: 0f)
        if (needed > r.width()) size *= r.width() / needed
        textPaint.textSize = size
        var x = r.left
        levels.forEachIndexed { i, lvl ->
            paint.style = Paint.Style.FILL
            paint.color = levelColor(lvl, accent)
            canvas.drawCircle(x + dot / 2, r.centerY(), dot / 2 * min(1f, size / (r.height() / 1.15f)) , paint)
            x += dot + 0.8f * u
            textPaint.textSize = size
            val tw = textPaint.measureText(labels[i])
            text(labels[i], RectF(x, r.top, x + tw + 1f, r.bottom), textSub, maxSize = size)
            x += tw + spacing
        }
        if (extra != null) text(extra, RectF(x, r.top, r.right, r.bottom), textSub, maxSize = size)
    }

    private fun cardBg(r: RectF) {
        paint.style = Paint.Style.FILL
        paint.color = card
        canvas.drawRoundRect(r, 2.5f * u, 2.5f * u, paint)
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = width
        canvas.drawLine(x0, y0, x1, y1, paint)
    }

    private fun inset(r: RectF, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)
    private fun rowOf(r: RectF, from: Float, to: Float) = RectF(r.left, r.top + r.height() * from, r.right, r.top + r.height() * to)
    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun levelColor(level: String?, fallback: Int): Int = when (level) {
        "VERY_CHEAP" -> Color.parseColor("#00C26E")
        "CHEAP" -> Color.parseColor("#7BD88F")
        "NORMAL" -> Color.parseColor("#F2C14E")
        "EXPENSIVE" -> Color.parseColor("#F78C40")
        "VERY_EXPENSIVE" -> Color.parseColor("#F0524F")
        else -> fallback
    }

    private fun levelLabel(level: String?): String = when (level) {
        "VERY_CHEAP" -> "ZEER GOEDKOOP"
        "CHEAP" -> "GOEDKOOP"
        "NORMAL" -> "NORMAAL"
        "EXPENSIVE" -> "DUUR"
        "VERY_EXPENSIVE" -> "ZEER DUUR"
        else -> "PRIJS"
    }

    // ---------------------------------------------------------------- opmaak

    private fun fmt(v: Double, decimals: Int): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        return nf.format(v)
    }

    private fun money(v: Double, currency: String, decimals: Int = 2): String {
        val symbol = when (currency) {
            "EUR" -> "€"
            "NOK", "SEK" -> "kr"
            else -> currency
        }
        val sign = if (v < 0) "−" else ""
        return "$sign$symbol ${fmt(abs(v), decimals)}"
    }

    private fun kwh(v: Double) = "${fmt(v, if (v < 10) 2 else 1)} kWh"

    private fun watts(v: Double?): String {
        if (v == null) return "– W"
        return if (abs(v) >= 10_000) "${fmt(v / 1000, 1)} kW" else "${fmt(v, 0)} W"
    }

    private fun wattsShort(v: Double?): String = v?.let { fmt(it, 0) } ?: "–"

    private fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(hhmm)

    private fun niceCeil(v: Double): Double {
        if (v <= 0) return 1.0
        val exp = floor(log10(v))
        val base = 10.0.pow(exp)
        val f = v / base
        val nice = when {
            f <= 1 -> 1.0
            f <= 1.2 -> 1.2
            f <= 1.5 -> 1.5
            f <= 2 -> 2.0
            f <= 2.5 -> 2.5
            f <= 3 -> 3.0
            f <= 4 -> 4.0
            f <= 5 -> 5.0
            f <= 6 -> 6.0
            f <= 8 -> 8.0
            else -> 10.0
        }
        return ceil(nice * base * 1000) / 1000
    }

    companion object {
        /** Gemiddeld verbruik per wasbeurt en droogbeurt (kWh), voor de besparingsindicatie. */
        const val WASH_KWH = 1.0
        const val DRYER_KWH = 2.5
    }
}
