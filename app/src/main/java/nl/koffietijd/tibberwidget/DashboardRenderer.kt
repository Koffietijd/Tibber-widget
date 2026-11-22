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

        val headerH = if (landscape) 9f * u else 8f * u
        val footerH = 3.6f * u
        header(RectF(full.left, full.top, full.right, full.top + headerH), home, state)
        footer(RectF(full.left, full.bottom - footerH, full.right, full.bottom), state)
        val body = RectF(full.left, full.top + headerH + gap, full.right, full.bottom - footerH - gap)

        if (landscape) {
            val leftW = body.width() * 0.42f
            val left = RectF(body.left, body.top, body.left + leftW, body.bottom)
            val right = RectF(left.right + gap, body.top, body.right, body.bottom)
            val leftParts = stack(left, gap, if (live != null) listOf(4.2f, 2.8f, 3f) else listOf(5f, 0f, 3.2f))
            priceCard(leftParts[0], home)
            if (live != null) liveCard(leftParts[1], live, home.currency)
            tiles(leftParts[2], home, live)
            val rightParts = stack(right, gap, listOf(6f, 4f))
            priceChart(rightParts[0], home)
            usageChart(rightParts[1], home)
        } else {
            val weights = if (live != null) listOf(2.3f, 1.5f, 3.5f, 2.2f, 1.7f) else listOf(2.6f, 0f, 4f, 2.4f, 1.8f)
            val parts = stack(body, gap, weights)
            priceCard(parts[0], home)
            if (live != null) liveCard(parts[1], live, home.currency)
            priceChart(parts[2], home)
            usageChart(parts[3], home)
            tiles(parts[4], home, live)
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
        cheapestWindow(prices)?.let { (start, avg) ->
            val z = start.atZoneSameInstant(zone)
            val dayWord = if (z.toLocalDate() == now.toLocalDate()) "" else "morgen "
            rows += "Goedkoopste uur" to "$dayWord${z.format(hhmm)}  ${money(avg, home.currency)}"
        }
        if (rows.isEmpty()) return
        val rowH = right.height() / rows.size
        rows.forEachIndexed { i, (label, value) ->
            val top = right.top + i * rowH
            text(label, RectF(right.left, top, right.right, top + rowH * 0.42f), textSub)
            text(value, RectF(right.left, top + rowH * 0.42f, right.right, top + rowH * 0.92f), textMain, bold = true)
        }
    }

    private fun liveCard(r: RectF, live: Live, fallbackCurrency: String) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val currency = live.currency ?: fallbackCurrency
        val split = inner.left + inner.width() * 0.5f
        val left = RectF(inner.left, inner.top, split - 2f * u, inner.bottom)
        val right = RectF(split + 2f * u, inner.top, inner.right, inner.bottom)

        val production = live.powerProduction ?: 0.0
        val label = "Live verbruik" + (live.timestamp?.let { " · ${it.atZoneSameInstant(zone).format(hhmm)}" } ?: "")
        text(label, rowOf(left, 0f, 0.25f), textSub)
        val big = if (production > 0.0 && (live.power ?: 0.0) <= 0.0) "−${watts(production)}" else watts(live.power)
        text(big, rowOf(left, 0.28f, 0.85f), accent, bold = true)
        if (production > 0.0) text("Teruglevering ${watts(production)}", rowOf(left, 0.86f, 1f), textSub)

        val rows = listOfNotNull(
            live.accumulatedConsumption?.let { "Vandaag verbruikt" to "${kwh(it)}" + (live.accumulatedCost?.let { c -> " · ${money(c, currency)}" } ?: "") },
            live.accumulatedProduction?.takeIf { it > 0.0 }?.let { "Vandaag teruggeleverd" to kwh(it) },
            if (live.minPower != null || live.maxPower != null)
                "Min / gem / max" to "${wattsShort(live.minPower)} / ${wattsShort(live.averagePower)} / ${watts(live.maxPower)}"
            else null,
        )
        if (rows.isEmpty()) return
        val rowH = right.height() / rows.size
        rows.forEachIndexed { i, (l, v) ->
            val top = right.top + i * rowH
            text(l, RectF(right.left, top, right.right, top + rowH * 0.42f), textSub)
            text(v, RectF(right.left, top + rowH * 0.42f, right.right, top + rowH * 0.92f), textMain, bold = true)
        }
    }

    private fun priceChart(r: RectF, home: Home) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val todayDate = now.toLocalDate()
        val tomorrowDate = todayDate.plusDays(1)
        val points = home.prices.filter {
            val d = it.start.atZoneSameInstant(zone).toLocalDate()
            d == todayDate || d == tomorrowDate
        }
        val hasTomorrow = points.any { it.start.atZoneSameInstant(zone).toLocalDate() == tomorrowDate }
        val titleRect = RectF(inner.left, inner.top, inner.right, inner.top + 7f * u)
        text(if (hasTomorrow) "Prijzen vandaag & morgen" else "Prijzen vandaag", RectF(titleRect.left, titleRect.top, titleRect.left + titleRect.width() * 0.55f, titleRect.bottom), textMain, bold = true)
        if (!hasTomorrow) {
            text("Morgen beschikbaar rond 13:00", RectF(titleRect.left + titleRect.width() * 0.55f, titleRect.top + titleRect.height() * 0.2f, titleRect.right, titleRect.bottom), textSub, align = Paint.Align.RIGHT)
        } else {
            legend(RectF(titleRect.left + titleRect.width() * 0.55f, titleRect.top + titleRect.height() * 0.25f, titleRect.right, titleRect.bottom - titleRect.height() * 0.1f))
        }
        if (points.isEmpty()) {
            text("Geen prijsgegevens", RectF(inner.left, titleRect.bottom, inner.right, inner.bottom), textSub, align = Paint.Align.CENTER)
            return
        }

        val plot = RectF(inner.left + 10f * u, titleRect.bottom + 2f * u, inner.right, inner.bottom - 4.5f * u)
        val minV = min(0.0, points.minOf { it.total })
        val maxV = niceCeil(points.maxOf { it.total })
        val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (plot.bottom - (v - minV) / range * plot.height()).toFloat()

        // Rasterlijnen + y-as labels
        val ticks = 4
        for (i in 0..ticks) {
            val v = minV + range * i / ticks
            val yy = y(v)
            line(plot.left, yy, plot.right, yy, grid, 0.15f * u)
            text(money(v, home.currency, 2), RectF(inner.left, yy - 2f * u, plot.left - 1.2f * u, yy + 2f * u), textSub, align = Paint.Align.RIGHT, maxSize = 2.6f * u)
        }

        // Tijd-as: van begin vandaag tot eind van laatste slot
        val dayStart = todayDate.atStartOfDay(zone)
        val end = (if (hasTomorrow) tomorrowDate.plusDays(1) else tomorrowDate).atStartOfDay(zone)
        val totalSec = Duration.between(dayStart, end).seconds.toFloat()
        fun x(t: ZonedDateTime) = plot.left + Duration.between(dayStart, t).seconds / totalSec * plot.width()

        val slotSec = slotSeconds(points)
        val barW = slotSec / totalSec * plot.width()
        val gapW = if (barW > 4f) barW * 0.18f else 0f
        val current = currentPrice(points)
        points.forEach { p ->
            val start = p.start.atZoneSameInstant(zone)
            val x0 = x(start) + gapW / 2
            val x1 = x0 + barW - gapW
            val isPast = start.plusSeconds(slotSec.toLong()).isBefore(now) || start.plusSeconds(slotSec.toLong()).isEqual(now)
            var color = levelColor(p.level, accent)
            if (isPast) color = withAlpha(color, 80)
            paint.style = Paint.Style.FILL
            paint.color = color
            val top = y(max(p.total, 0.0))
            val bottom = y(min(p.total, 0.0))
            canvas.drawRect(x0, top, max(x1, x0 + 1f), max(bottom, top + 1f), paint)
            if (p == current) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.4f * u
                paint.color = Color.WHITE
                canvas.drawRect(x0, top, max(x1, x0 + 1f), bottom, paint)
            }
        }

        // "Nu"-lijn
        val nx = x(now)
        if (nx in plot.left..plot.right) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = 0.25f * u
            paint.pathEffect = DashPathEffect(floatArrayOf(u, u), 0f)
            canvas.drawLine(nx, plot.top, nx, plot.bottom, paint)
            paint.pathEffect = null
            text("nu", RectF(nx - 5f * u, plot.top - 2.4f * u, nx + 5f * u, plot.top), Color.WHITE, align = Paint.Align.CENTER, bold = true)
        }

        // Middernacht-scheiding
        if (hasTomorrow) {
            val mx = x(tomorrowDate.atStartOfDay(zone))
            line(mx, plot.top, mx, plot.bottom + 1f * u, textSub, 0.2f * u)
            text("morgen", RectF(mx + 0.8f * u, plot.top, mx + 14f * u, plot.top + 2.8f * u), textSub)
        }

        // X-as labels
        val stepH = if (hasTomorrow) (if (plot.width() / u > 120) 3 else 6) else 3
        var t = dayStart
        while (!t.isAfter(end.minusHours(1))) {
            if (t.hour % stepH == 0) {
                val xx = x(t)
                text(t.format(DateTimeFormatter.ofPattern("HH", locale)), RectF(xx - 4f * u, plot.bottom + 0.8f * u, xx + 4f * u, inner.bottom), textSub, align = Paint.Align.CENTER, maxSize = 2.8f * u)
            }
            t = t.plusHours(1)
        }
    }

    private fun usageChart(r: RectF, home: Home) {
        cardBg(r)
        val inner = inset(r, 3f * u)
        val data = home.hourly.filter { it.kwh != null }.takeLast(24)
        val titleRect = RectF(inner.left, inner.top, inner.right, inner.top + 6.5f * u)
        val totalKwh = data.sumOf { it.kwh ?: 0.0 }
        val totalCost = data.sumOf { it.cost ?: 0.0 }
        text("Verbruik per uur · laatste 24 uur", RectF(titleRect.left, titleRect.top, titleRect.left + titleRect.width() * 0.6f, titleRect.bottom), textMain, bold = true)
        if (data.isNotEmpty()) {
            text("${kwh(totalKwh)} · ${money(totalCost, home.currency)}", RectF(titleRect.left + titleRect.width() * 0.6f, titleRect.top, titleRect.right, titleRect.bottom), accent, bold = true, align = Paint.Align.RIGHT)
        }
        if (data.isEmpty()) {
            text("Nog geen verbruiksgegevens", RectF(inner.left, titleRect.bottom, inner.right, inner.bottom), textSub, align = Paint.Align.CENTER)
            return
        }
        val plot = RectF(inner.left + 10f * u, titleRect.bottom + 2f * u, inner.right, inner.bottom - 4.5f * u)
        val maxV = niceCeil(data.maxOf { it.kwh ?: 0.0 }.coerceAtLeast(0.1))
        for (i in 0..2) {
            val v = maxV * i / 2
            val yy = plot.bottom - (v / maxV * plot.height()).toFloat()
            line(plot.left, yy, plot.right, yy, grid, 0.15f * u)
            text(fmt(v, if (maxV < 1) 2 else 1) + " kWh", RectF(inner.left, yy - 2f * u, plot.left - 1.2f * u, yy + 2f * u), textSub, align = Paint.Align.RIGHT, maxSize = 2.4f * u)
        }
        val n = data.size
        val slotW = plot.width() / n
        val gapW = slotW * 0.2f
        data.forEachIndexed { i, d ->
            val x0 = plot.left + i * slotW + gapW / 2
            val hh = ((d.kwh ?: 0.0) / maxV * plot.height()).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawRoundRect(RectF(x0, plot.bottom - hh, x0 + slotW - gapW, plot.bottom), 0.4f * u, 0.4f * u, paint)
            val z = d.from.atZoneSameInstant(zone)
            if (z.hour % 3 == 0) {
                val cx = x0 + (slotW - gapW) / 2
                text(z.format(DateTimeFormatter.ofPattern("HH", locale)), RectF(cx - 4f * u, plot.bottom + 0.8f * u, cx + 4f * u, inner.bottom), textSub, align = Paint.Align.CENTER, maxSize = 2.8f * u)
            }
        }
    }

    private fun tiles(r: RectF, home: Home, live: Live?) {
        val gap = 2f * u
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val thisMonth = today.withDayOfMonth(1)
        val lastMonth = thisMonth.minusMonths(1)

        fun daySum(filter: (LocalDate) -> Boolean): Pair<Double, Double>? {
            val list = home.daily.filter { it.kwh != null && filter(it.from.atZoneSameInstant(zone).toLocalDate()) }
            if (list.isEmpty()) return null
            return list.sumOf { it.kwh ?: 0.0 } to list.sumOf { it.cost ?: 0.0 }
        }

        val yest = daySum { it == yesterday }
            ?: home.hourly.filter { it.kwh != null && it.from.atZoneSameInstant(zone).toLocalDate() == yesterday }
                .takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.kwh ?: 0.0 } to l.sumOf { it.cost ?: 0.0 } }

        // Vandaag: live (Pulse) of uit de uurdata
        val todayUse = live?.accumulatedConsumption?.let { it to (live.accumulatedCost ?: 0.0) }
            ?: home.hourly.filter { it.kwh != null && it.from.atZoneSameInstant(zone).toLocalDate() == today }
                .takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { it.kwh ?: 0.0 } to l.sumOf { it.cost ?: 0.0 } }

        val monthBase = daySum { !it.isBefore(thisMonth) && it.isBefore(today) }
        val month = when {
            monthBase == null && todayUse == null -> null
            else -> ((monthBase?.first ?: 0.0) + (todayUse?.first ?: 0.0)) to ((monthBase?.second ?: 0.0) + (todayUse?.second ?: 0.0))
        }
        val prevMonth = daySum { !it.isBefore(lastMonth) && it.isBefore(thisMonth) }

        val monthName = DateTimeFormatter.ofPattern("MMMM", locale)
        val items = listOf(
            "Gisteren" to yest,
            now.format(monthName).replaceFirstChar { it.titlecase(locale) } to month,
            lastMonth.format(monthName).replaceFirstChar { it.titlecase(locale) } to prevMonth,
        )
        val tileW = (r.width() - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (label, value) ->
            val tr = RectF(r.left + i * (tileW + gap), r.top, r.left + i * (tileW + gap) + tileW, r.bottom)
            cardBg(tr)
            val inner = inset(tr, 2.5f * u)
            text(label, rowOf(inner, 0f, 0.26f), textSub)
            text(value?.let { kwh(it.first) } ?: "–", rowOf(inner, 0.3f, 0.68f), textMain, bold = true)
            text(value?.let { money(it.second, home.currency) } ?: "", rowOf(inner, 0.72f, 1f), accent, bold = true)
        }
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

    /** Goedkoopste aaneengesloten uur vanaf nu (gemiddelde prijs). */
    private fun cheapestWindow(prices: List<PricePoint>): Pair<java.time.OffsetDateTime, Double>? {
        val slot = slotSeconds(prices).toInt()
        val perHour = max(1, 3600 / slot)
        val cur = currentPrice(prices)
        val upcoming = prices.filter { !it.start.toInstant().isBefore(now.toInstant()) || it == cur }
        if (upcoming.size < perHour) return null
        var best: Pair<java.time.OffsetDateTime, Double>? = null
        for (i in 0..upcoming.size - perHour) {
            val window = upcoming.subList(i, i + perHour)
            val avg = window.map { it.total }.average()
            if (best == null || avg < best.second) best = window.first().start to avg
        }
        return best
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

    private fun legend(r: RectF) {
        val levels = listOf("VERY_CHEAP", "CHEAP", "NORMAL", "EXPENSIVE", "VERY_EXPENSIVE")
        val labels = listOf("zeer goedkoop", "goedkoop", "normaal", "duur", "zeer duur")
        val n = levels.size
        val w = r.width() / n
        levels.forEachIndexed { i, lvl ->
            val x = r.left + i * w
            val dot = r.height() * 0.5f
            paint.style = Paint.Style.FILL
            paint.color = levelColor(lvl, accent)
            canvas.drawCircle(x + dot / 2, r.centerY(), dot / 2, paint)
            text(labels[i], RectF(x + dot + 0.6f * u, r.top, x + w - 0.6f * u, r.bottom), textSub)
        }
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
}
