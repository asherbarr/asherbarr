package com.asherbarr.livemap.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.asherbarr.livemap.data.Prefs
import com.asherbarr.livemap.data.workerExecutor

class LiveMapWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        val step = when (intent.action) {
            ACTION_ZOOM_IN -> 1
            ACTION_ZOOM_OUT -> -1
            else -> return super.onReceive(context, intent)
        }
        val prefs = Prefs(context)
        val newZoom = (prefs.zoom + step).coerceIn(Prefs.MIN_ZOOM, Prefs.MAX_ZOOM)
        if (newZoom == prefs.zoom) return
        prefs.zoom = newZoom
        prefs.invalidateRender()
        // Keep the receiver alive while the new map is drawn (tiles may need downloading).
        val pending = goAsync()
        val app = context.applicationContext
        workerExecutor.execute {
            try {
                WidgetUpdater.updateAll(app, render = true)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Runs on the main thread, so hand the (possibly networked) draw to the worker.
        workerExecutor.execute {
            appWidgetIds.forEach { WidgetUpdater.updateOne(context, manager, it, render = true) }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        workerExecutor.execute {
            WidgetUpdater.updateOne(context, manager, appWidgetId, render = true)
        }
    }

    companion object {
        const val ACTION_ZOOM_IN = "com.asherbarr.livemap.action.ZOOM_IN"
        const val ACTION_ZOOM_OUT = "com.asherbarr.livemap.action.ZOOM_OUT"
    }
}
