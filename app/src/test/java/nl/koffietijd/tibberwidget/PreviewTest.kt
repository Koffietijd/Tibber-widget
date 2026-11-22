package nl.koffietijd.tibberwidget

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewTest {
    private fun home(withTomorrow: Boolean): Home {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val prices = ArrayList<PricePoint>()
        val days = if (withTomorrow) 2 else 1
        for (d in 0 until days) for (q in 0 until 96) {
            val t = today.plusDays(d.toLong()).atStartOfDay(zone).plusMinutes(q * 15L).toOffsetDateTime()
            val h = q / 4.0
            val v = 0.22 + 0.08 * Math.sin((h - 12) / 24 * 2 * Math.PI * 1.0 + 1) + (if (h in 17.0..20.0) 0.07 else 0.0) - (if (h in 2.0..5.0) 0.05 else 0.0) + d * 0.01
            val lvl = when { v < 0.17 -> "VERY_CHEAP"; v < 0.21 -> "CHEAP"; v < 0.26 -> "NORMAL"; v < 0.31 -> "EXPENSIVE"; else -> "VERY_EXPENSIVE" }
            prices += PricePoint(t, v, v * 0.45, v * 0.55, lvl)
        }
        val nowH = ZonedDateTime.now(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val hourly = (1..30).map { i -> val f = nowH.minusHours(i.toLong()); val k = 0.2 + 0.6 * Math.abs(Math.sin(i / 3.0)); Usage(f.toOffsetDateTime(), k, k * 0.24) }.reversed()
        val daily = (1..50).map { i -> val f = today.minusDays(i.toLong()).atStartOfDay(zone); Usage(f.toOffsetDateTime(), 8.0 + (i % 5), (8.0 + (i % 5)) * 0.25) }.reversed()
        return Home("1", "Thuis", "Dorpsstraat 1, Utrecht", true, "EUR", prices, hourly, daily)
    }
    private val live = Live(OffsetDateTime.now(), 1234.0, 0.0, 6.42, 0.0, 1.58, 120.0, 540.0, 3400.0, "EUR")

    private fun save(name: String, w: Int, h: Int, s: DashboardState) {
        val bmp = DashboardRenderer(ApplicationProvider.getApplicationContext()).render(w, h, s)
        File("build/previews").apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun render() {
        val now = System.currentTimeMillis()
        save("portrait_live", 1080, 2100, DashboardState(home(true), live, now, null, true))
        save("portrait_nolive", 720, 1280, DashboardState(home(false), null, now, null, true))
        save("landscape", 2100, 1080, DashboardState(home(true), live, now, "HTTP 500", true))
        save("notoken", 1080, 2100, DashboardState(null, null, 0, null, false))
    }
}
