package com.asherbarr.livemap.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.LruCache
import com.asherbarr.livemap.data.MapStyle
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Builds the widget map image from raster map tiles (Web Mercator, 256 px tiles).
 *
 * Tile sources (both free, no API key):
 *  - Standard: OpenStreetMap  https://tile.openstreetmap.org  (ODbL, attribution required)
 *  - Satellite: Esri World Imagery (free for non-commercial use, attribution required)
 *
 * Tiles are cached in memory and on disk (7 days), so a moving widget only downloads
 * the tiles it has not seen before.
 */
object TileMap {

    private const val TILE = 256
    private const val MAX_BITMAP_SIDE = 360 // keeps RemoteViews payload small
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private var memory: LruCache<String, Bitmap>? = null

    private fun memCache(): LruCache<String, Bitmap> = memory ?: LruCache<String, Bitmap>(64).also { memory = it }

    fun attribution(style: MapStyle) = when (style) {
        MapStyle.STANDARD -> "© OpenStreetMap"
        MapStyle.SATELLITE -> "© Esri, Maxar"
    }

    private fun tileUrl(style: MapStyle, z: Int, x: Int, y: Int): String = when (style) {
        MapStyle.STANDARD -> "https://tile.openstreetmap.org/$z/$x/$y.png"
        MapStyle.SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }

    private fun lonToWorldX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * 2.0.pow(z) * TILE

    private fun latToWorldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z) * TILE
    }

    /** Meters per screen pixel at this latitude and zoom (for drawing the accuracy circle). */
    private fun metersPerPixel(lat: Double, z: Int) = 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(z)

    /**
     * Renders a [width] x [height] px map centered on lat/lng with a blue location dot.
     * Returns a bitmap with rounded corners and an attribution line.
     */
    fun render(
        context: Context,
        lat: Double,
        lng: Double,
        accuracyMeters: Float,
        zoom: Int,
        style: MapStyle,
        darkMode: Boolean,
        width: Int,
        height: Int,
        cornerRadiusPx: Float,
    ): Bitmap {
        val scale = minOf(1f, MAX_BITMAP_SIDE.toFloat() / maxOf(width, height).coerceAtLeast(1))
        val bw = (width * scale).toInt().coerceAtLeast(1)
        val bh = (height * scale).toInt().coerceAtLeast(1)

        val map = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(map)
        c.scale(scale, scale)

        val n = 2.0.pow(zoom).toInt()
        val cx = lonToWorldX(lng, zoom)
        val cy = latToWorldY(lat, zoom)
        val left = cx - width / 2.0
        val top = cy - height / 2.0

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = tileColorFilter(darkMode)

        val tx0 = floor(left / TILE).toInt()
        val tx1 = floor((left + width) / TILE).toInt()
        val ty0 = floor(top / TILE).toInt()
        val ty1 = floor((top + height) / TILE).toInt()
        for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            for (tx in tx0..tx1) {
                val wx = ((tx % n) + n) % n
                val tile = loadTile(context, style, zoom, wx, ty)
                val dx = (tx * TILE - left).toFloat()
                val dy = (ty * TILE - top).toFloat()
                if (tile != null) {
                    c.drawBitmap(tile, dx, dy, paint)
                } else {
                    c.drawRect(dx, dy, dx + TILE, dy + TILE, placeholderPaint(darkMode))
                }
            }
        }

        drawLocationDot(c, width / 2f, height / 2f, accuracyMeters, metersPerPixel(lat, zoom), darkMode)
        drawAttribution(c, width, height, attribution(style))

        // Round the corners by masking the result.
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = cornerRadiusPx * scale
        oc.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), r, r, mask)
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(map, 0f, 0f, mask)
        map.recycle()
        return out
    }

    private fun drawLocationDot(c: Canvas, x: Float, y: Float, accuracy: Float, mpp: Double, dark: Boolean) {
        val blue = Color.rgb(30, 136, 229)
        val radius = if (mpp > 0) (accuracy / mpp).toFloat().coerceIn(12f, 120f) else 12f
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue
            alpha = if (dark) 60 else 40
            style = Paint.Style.FILL
        }
        c.drawCircle(x, y, radius, halo)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue
            alpha = 120
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawCircle(x, y, radius, ring)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(x, y, 14f, white)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        c.drawCircle(x, y, 10f, dot)
    }

    private fun drawAttribution(c: Canvas, w: Int, h: Int, text: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val tw = p.measureText(text)
        c.drawText(text, w - tw - 6f, h - 6f, p)
    }

    private fun placeholderPaint(dark: Boolean) = Paint().apply {
        color = if (dark) Color.rgb(40, 44, 52) else Color.rgb(226, 230, 236)
    }

    private fun tileColorFilter(dark: Boolean): ColorMatrixColorFilter? {
        if (!dark) return null
        // Dim and desaturate the map tiles so they sit well on a dark home screen.
        val cm = ColorMatrix()
        cm.setSaturation(0.35f)
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.6f, 0f, 0f, 0f, 0f,
            0f, 0.6f, 0f, 0f, 0f,
            0f, 0f, 0.6f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        return ColorMatrixColorFilter(cm)
    }

    /** Memory cache, then disk cache, then network. Returns null if offline and not cached. */
    private fun loadTile(context: Context, style: MapStyle, z: Int, x: Int, y: Int): Bitmap? {
        val key = "${style.key}/$z/$x/$y"
        memCache().get(key)?.let { return it }

        val file = File(context.cacheDir, "tiles/${style.key}/$z/$x/$y.png")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS) {
            BitmapFactory.decodeFile(file.path)?.let { memCache().put(key, it); return it }
        }

        val bmp = download(tileUrl(style, z, x, y), file) ?: return if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.also { memCache().put(key, it) }
        } else null
        memCache().put(key, bmp)
        return bmp
    }

    private fun download(url: String, out: File): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            // OpenStreetMap's tile usage policy requires an identifying User-Agent.
            setRequestProperty("User-Agent", "LiveMapWidget/1.0 (Android; personal home-screen widget)")
            setRequestProperty("Accept", "image/png,image/*")
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            null
        } else {
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (e: Exception) {
        null
    }

    /** Draws a neutral "waiting" image before the first GPS fix. */
    fun placeholder(width: Int, height: Int, message: String, dark: Boolean, cornerRadiusPx: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.rgb(32, 35, 42) else Color.rgb(232, 236, 242)
        }
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerRadiusPx, cornerRadiusPx, bg)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.LTGRAY else Color.DKGRAY
            textSize = 14f * (height / 110f).coerceIn(1f, 3f)
            textAlign = Paint.Align.CENTER
        }
        c.drawText(message, width / 2f, height / 2f, tp)
        return bmp
    }
}
