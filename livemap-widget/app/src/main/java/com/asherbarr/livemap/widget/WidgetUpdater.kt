package com.asherbarr.livemap.widget

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import com.asherbarr.livemap.ui.MainActivity
import com.asherbarr.livemap.R
import com.asherbarr.livemap.data.LiveStatus
import com.asherbarr.livemap.data.Prefs
import com.asherbarr.livemap.data.workerExecutor
import com.asherbarr.livemap.map.OpenInMapsActivity
import com.asherbarr.livemap.map.TileMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pushes the current state into every placed widget.
 *
 * Two update paths:
 *  - render = true  : redraw the map bitmap (new position, size, zoom, style or theme).
 *  - render = false : text-only partial update (timestamp / status), which is cheap and keeps the image.
 *
 * Must run off the main thread (tile loading can hit the network).
 */
object WidgetUpdater {

    fun refreshAsync(context: Context, render: Boolean) {
        val app = context.applicationContext
        workerExecutor.execute { updateAll(app, render) }
    }

    fun updateAll(context: Context, render: Boolean) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, LiveMapWidgetProvider::class.java))
        for (id in ids) updateOne(context, mgr, id, render)
    }

    fun updateOne(context: Context, mgr: AppWidgetManager, id: Int, render: Boolean) {
        val prefs = Prefs(context)
        val density = context.resources.displayMetrics.density
        val opts = mgr.getAppWidgetOptions(id)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
        val maxW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW)
        val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH)
        // Use the larger of min/max so the map fills the cell in both orientations.
        val wPx = (maxOf(minW, maxW) * density).toInt().coerceIn(120, 1200)
        val hPx = (maxOf(minH, maxH) * density).toInt().coerceIn(120, 1200)
        val radius = 24 * density
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val views = RemoteViews(context.packageName, R.layout.widget_livemap)

        if (render) {
            val lat = prefs.lastLat
            val lng = prefs.lastLng
            val bmp = if (lat != null && lng != null) {
                prefs.renderedLat = lat
                prefs.renderedLng = lng
                TileMap.render(
                    context, lat, lng, prefs.lastAccuracy, prefs.zoom, prefs.mapStyle,
                    dark, wPx, hPx, radius,
                )
            } else {
                TileMap.placeholder(wPx, hPx, "Waiting for GPS…", dark, radius)
            }
            views.setImageViewBitmap(R.id.map_image, bmp)
        }

        val status = prefs.status
        val text = statusText(context, status, prefs.lastFixTime, prefs.trackingEnabled)
        views.setTextViewText(R.id.status_text, text)
        val color = when (status) {
            LiveStatus.LIVE -> Color.WHITE
            LiveStatus.WAITING -> Color.WHITE
            LiveStatus.PAUSED -> Color.rgb(255, 214, 102)
            LiveStatus.UNAVAILABLE -> Color.rgb(255, 138, 128)
        }
        views.setTextColor(R.id.status_text, color)

        // Tap the map: open Google Maps at the latest location.
        views.setOnClickPendingIntent(
            R.id.map_image,
            PendingIntent.getActivity(
                context, 0,
                Intent(context, OpenInMapsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        // Tap the status pill: open the app.
        views.setOnClickPendingIntent(
            R.id.status_text,
            PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        if (render) mgr.updateAppWidget(id, views) else mgr.partiallyUpdateAppWidget(id, views)
    }

    private fun statusText(context: Context, status: LiveStatus, fixTime: Long, tracking: Boolean): String {
        val time = if (fixTime > 0) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(fixTime)) else null
        return when (status) {
            LiveStatus.LIVE -> "● Live · ${time ?: "--:--:--"}"
            LiveStatus.WAITING -> if (tracking) "Waiting for GPS…" else "Paused"
            LiveStatus.PAUSED -> if (time != null) "⏸ Paused · $time" else "⏸ Tracking paused"
            LiveStatus.UNAVAILABLE -> if (time != null) "⚠ Unavailable · $time" else "⚠ Location unavailable"
        }
    }
}
