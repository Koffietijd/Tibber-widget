package nl.koffietijd.tibberwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class TibberWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Toon direct de gecachte gegevens en zorg dat het 15-minutenschema loopt.
        val state = DataRepository.load(context)
        ids.forEach { WidgetUpdater.update(context, mgr, it, state) }
        RefreshWorker.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        // Formaat gewijzigd: opnieuw tekenen op de nieuwe grootte.
        WidgetUpdater.update(context, mgr, id)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            RefreshWorker.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "nl.koffietijd.tibberwidget.REFRESH"
    }
}
