package nl.koffietijd.tibberwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WidgetUpdater {

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, TibberWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = DataRepository.load(context)
        ids.forEach { update(context, mgr, it, state) }
        cleanupOldImages(context)
    }

    fun update(context: Context, mgr: AppWidgetManager, widgetId: Int, state: DashboardState = DataRepository.load(context)) {
        val (w, h) = widgetSizePx(context, mgr.getAppWidgetOptions(widgetId))
        val bitmap = DashboardRenderer(context).render(w, h, state)
        val uri = saveImage(context, widgetId, bitmap)
        bitmap.recycle()

        val views = RemoteViews(context.packageName, R.layout.widget)
        views.setImageViewUri(R.id.widget_image, uri)
        views.setViewVisibility(R.id.widget_loading, View.GONE)

        val click = if (state.hasToken) {
            val intent = Intent(context, TibberWidgetProvider::class.java).setAction(TibberWidgetProvider.ACTION_REFRESH)
            PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            val intent = Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        views.setOnClickPendingIntent(R.id.widget_root, click)
        mgr.updateAppWidget(widgetId, views)
    }

    /** Bepaalt de werkelijke afmeting van de widget in pixels, zodat de afbeelding scherp is. */
    private fun widgetSizePx(context: Context, options: Bundle?): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val density = dm.density
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        var wDp = 0
        var hDp = 0
        if (options != null) {
            if (portrait) {
                wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            } else {
                wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            }
        }
        var w = (wDp * density).roundToInt()
        var h = (hDp * density).roundToInt()
        if (w <= 0 || h <= 0) {
            w = (dm.widthPixels * 0.95).roundToInt()
            h = (dm.heightPixels * 0.8).roundToInt()
        }
        // Begrens de grootte zodat oude telefoons niet zonder geheugen raken.
        val maxSide = 2400f
        val scale = min(1f, maxSide / max(w, h))
        return Pair(max(200, (w * scale).roundToInt()), max(200, (h * scale).roundToInt()))
    }

    /**
     * Grote bitmaps passen niet in een RemoteViews-transactie (limiet ~1 MB), daarom schrijven we
     * een PNG en geven de launcher een content:// URI. Elke update krijgt een nieuwe bestandsnaam
     * zodat de launcher niet een oude versie uit zijn cache toont.
     */
    private fun saveImage(context: Context, widgetId: Int, bitmap: Bitmap): Uri {
        val dir = File(context.filesDir, DashboardImageProvider.DIR).apply { mkdirs() }
        val name = "dash_${widgetId}_${System.currentTimeMillis()}.png"
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.parse("content://${context.packageName}.dashboard/$name")
    }

    /** Bewaar per widget alleen de twee nieuwste afbeeldingen. */
    private fun cleanupOldImages(context: Context) {
        val dir = File(context.filesDir, DashboardImageProvider.DIR)
        val files = dir.listFiles() ?: return
        files.groupBy { it.name.split("_").getOrNull(1) }
            .values
            .forEach { group -> group.sortedByDescending { it.lastModified() }.drop(2).forEach { it.delete() } }
    }

}
