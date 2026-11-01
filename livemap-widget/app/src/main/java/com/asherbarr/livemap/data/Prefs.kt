package com.asherbarr.livemap.data

import android.content.Context
import android.location.Location
import java.util.concurrent.Executors

/** One background thread for tile fetching, rendering and widget updates. */
val workerExecutor = Executors.newSingleThreadExecutor()

enum class LiveStatus { WAITING, LIVE, PAUSED, UNAVAILABLE }

enum class MapStyle(val key: String, val label: String) {
    STANDARD("standard", "Standard"),
    SATELLITE("satellite", "Satellite");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

/**
 * All persisted state in one place. Everything stays on the device:
 * no location data is ever sent anywhere except to the tile server (which only sees tile coordinates).
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("livemap", Context.MODE_PRIVATE)

    var trackingEnabled: Boolean
        get() = sp.getBoolean(KEY_TRACKING, false)
        set(v) = sp.edit().putBoolean(KEY_TRACKING, v).apply()

    var intervalMs: Long
        get() = sp.getLong(KEY_INTERVAL, 5_000L)
        set(v) = sp.edit().putLong(KEY_INTERVAL, v).apply()

    var zoom: Int
        get() = sp.getInt(KEY_ZOOM, 16)
        set(v) = sp.edit().putInt(KEY_ZOOM, v.coerceIn(MIN_ZOOM, MAX_ZOOM)).apply()

    var mapStyle: MapStyle
        get() = MapStyle.from(sp.getString(KEY_STYLE, null))
        set(v) = sp.edit().putString(KEY_STYLE, v.key).apply()

    var status: LiveStatus
        get() = runCatching { LiveStatus.valueOf(sp.getString(KEY_STATUS, "WAITING")!!) }
            .getOrDefault(LiveStatus.WAITING)
        set(v) = sp.edit().putString(KEY_STATUS, v.name).apply()

    var lastLat: Double?
        get() = if (sp.contains(KEY_LAT)) java.lang.Double.longBitsToDouble(sp.getLong(KEY_LAT, 0)) else null
        set(v) = sp.edit().apply {
            if (v == null) remove(KEY_LAT) else putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(v))
        }.apply()

    var lastLng: Double?
        get() = if (sp.contains(KEY_LNG)) java.lang.Double.longBitsToDouble(sp.getLong(KEY_LNG, 0)) else null
        set(v) = sp.edit().apply {
            if (v == null) remove(KEY_LNG) else putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(v))
        }.apply()

    var lastAccuracy: Float
        get() = sp.getFloat(KEY_ACC, 0f)
        set(v) = sp.edit().putFloat(KEY_ACC, v).apply()

    /** Wall-clock time (ms) of the last accepted fix. 0 = never. */
    var lastFixTime: Long
        get() = sp.getLong(KEY_FIX_TIME, 0L)
        set(v) = sp.edit().putLong(KEY_FIX_TIME, v).apply()

    /** Center of the last map image drawn, so we only redraw when the position really moved. */
    var renderedLat: Double?
        get() = if (sp.contains(KEY_RLAT)) java.lang.Double.longBitsToDouble(sp.getLong(KEY_RLAT, 0)) else null
        set(v) = sp.edit().apply {
            if (v == null) remove(KEY_RLAT) else putLong(KEY_RLAT, java.lang.Double.doubleToRawLongBits(v))
        }.apply()

    var renderedLng: Double?
        get() = if (sp.contains(KEY_RLNG)) java.lang.Double.longBitsToDouble(sp.getLong(KEY_RLNG, 0)) else null
        set(v) = sp.edit().apply {
            if (v == null) remove(KEY_RLNG) else putLong(KEY_RLNG, java.lang.Double.doubleToRawLongBits(v))
        }.apply()

    /** Settings or style changed, so the next draw must not be skipped. */
    fun invalidateRender() = sp.edit().remove(KEY_RLAT).remove(KEY_RLNG).apply()

    fun saveFix(loc: Location) {
        sp.edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putFloat(KEY_ACC, loc.accuracy)
            .putLong(KEY_FIX_TIME, System.currentTimeMillis())
            .putString(KEY_STATUS, LiveStatus.LIVE.name)
            .apply()
    }

    /** True if we have never drawn, or moved more than [minMeters] since the last drawn center. */
    fun needsRedraw(lat: Double, lng: Double, minMeters: Float = 8f): Boolean {
        val rl = renderedLat ?: return true
        val rg = renderedLng ?: return true
        val out = FloatArray(1)
        Location.distanceBetween(rl, rg, lat, lng, out)
        return out[0] >= minMeters
    }

    companion object {
        const val MIN_ZOOM = 10
        const val MAX_ZOOM = 19
        private const val KEY_TRACKING = "tracking"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_STYLE = "style"
        private const val KEY_STATUS = "status"
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_ACC = "acc"
        private const val KEY_FIX_TIME = "fix_time"
        private const val KEY_RLAT = "r_lat"
        private const val KEY_RLNG = "r_lng"
    }
}
