package nl.koffietijd.tibberwidget

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Rendert voorbeeldafbeeldingen van het dashboard met testdata (app/build/previews). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)

    /** Marktprijs (EUR/MWh) per kwartier voor vandaag en morgen. */
    private fun spot(d: Int, q: Int): Double {
        val h = q / 4.0
        return 90 + 60 * Math.sin((h - 12) / 24 * 2 * Math.PI + 1) + (if (h in 17.0..20.0) 60.0 else 0.0) -
            (if (h in 2.0..5.0) 40.0 else 0.0) - (if (d == 1 && h in 11.0..15.0) 70.0 else 0.0)
    }

    private val market = (0..1).flatMap { d ->
        (0 until 96).map { q ->
            MarketPoint(today.plusDays(d.toLong()).atStartOfDay(zone).plusMinutes(q * 15L).toInstant(), spot(d, q))
        }
    }

    private fun home(tomorrowAtTibber: Boolean, solar: Boolean): Home {
        val prices = market.filter { tomorrowAtTibber || it.start.atZone(zone).toLocalDate() == today }.map {
            val v = (it.eurMwh / 1000 + 0.0248) * 1.21 + 0.1108
            val lvl = when { v < 0.20 -> "VERY_CHEAP"; v < 0.24 -> "CHEAP"; v < 0.29 -> "NORMAL"; v < 0.33 -> "EXPENSIVE"; else -> "VERY_EXPENSIVE" }
            PricePoint(it.start.atZone(zone).toOffsetDateTime(), v, v * 0.45, v * 0.55, lvl)
        }
        val nowH = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS)
        val hourly = (1..30).map { i ->
            val k = 0.2 + 0.6 * Math.abs(Math.sin(i / 3.0))
            Usage(nowH.minusHours(i.toLong()).toOffsetDateTime(), k, k * 0.24)
        }.reversed()
        val daily = (1..3).map { i -> Usage(today.minusDays(i.toLong()).atStartOfDay(zone).toOffsetDateTime(), 9.4, 2.31) }
        val production = if (!solar) emptyList() else (1..40).map { i ->
            val t = nowH.minusHours(i.toLong())
            val k = if (t.hour in 9..16) 0.4 * Math.sin((t.hour - 8) / 9.0 * Math.PI) else 0.0
            Usage(t.toOffsetDateTime(), k, k * 0.09)
        }.reversed()
        return Home("1", "Thuis", "Dorpsstraat 1, Utrecht", true, "EUR", prices, hourly, daily, production)
    }

    private val live = Live(OffsetDateTime.now(), 0.0, 820.0, 6.42, 3.1, 1.58, 0.28, 0.0, 540.0, 3400.0, "EUR")
    private val liveNoSolar = live.copy(power = 1234.0, powerProduction = 0.0, accumulatedProduction = 0.0, accumulatedReward = 0.0)

    private fun save(name: String, w: Int, h: Int, s: DashboardState) {
        val bmp = DashboardRenderer(ApplicationProvider.getApplicationContext()).render(w, h, s)
        val dir = File("build/previews").apply { mkdirs() }
        dir.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Kleine versie voor snelle controle in de CI-log.
        val small = Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true)
        File("build/previews-small").apply { mkdirs() }.resolve("$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
    }

    @Test
    fun render() {
        val now = System.currentTimeMillis()
        save("portrait_solar", 1080, 2100, DashboardState(home(true, true), live, market, now, null, true))
        save("portrait_market", 720, 1280, DashboardState(home(false, true), null, market, now, null, true))
        save("portrait_nosolar", 1080, 2100, DashboardState(home(true, false), liveNoSolar, emptyList(), now, null, true))
        save("landscape", 2100, 1080, DashboardState(home(false, true), live, market, now, "HTTP 500", true))
        save("notoken", 1080, 2100, DashboardState(null, null, emptyList(), 0, null, false))
    }
}
