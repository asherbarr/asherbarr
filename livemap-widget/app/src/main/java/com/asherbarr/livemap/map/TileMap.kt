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
 * Tile sources (all free, no API key):
 *  - Standard: OpenStreetMap  https://tile.openstreetmap.org  (ODbL, attribution required)
 *  - Satellite: Esri World Imagery + Esri road and place-name overlays
 *    (free for non-commercial use, attribution required)
 *
 * Tiles are cached in memory and on disk (7 days), so a moving widget only downloads
 * the tiles it has not seen before.
 */
object TileMap {

    private const val TILE = 256
    private const val MAX_BITMAP_SIDE = 640 // sharp enough, still a small RemoteViews payload
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private var memory: LruCache<String, Bitmap>? = null

    private fun memCache(): LruCache<String, Bitmap> = memory ?: LruCache<String, Bitmap>(96).also { memory = it }

    /** One raster layer. Overlays are transparent PNGs drawn on top of the base layer. */
    private class Layer(val key: String, val url: (Int, Int, Int) -> String, val base: Boolean)

    private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services"

    private fun layers(style: MapStyle): List<Layer> = when (style) {
        MapStyle.STANDARD -> listOf(
            Layer("osm", { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" }, base = true),
        )
        MapStyle.SATELLITE -> listOf(
            Layer("esri-img", { z, x, y -> "$ESRI/World_Imagery/MapServer/tile/$z/$y/$x" }, base = true),
            Layer("esri-roads", { z, x, y -> "$ESRI/Reference/World_Transportation/MapServer/tile/$z/$y/$x" }, base = false),
            Layer("esri-places", { z, x, y -> "$ESRI/Reference/World_Boundaries_and_Places/MapServer/tile/$z/$y/$x" }, base = false),
        )
    }

    fun attribution(style: MapStyle) = when (style) {
        MapStyle.STANDARD -> "© OpenStreetMap"
        MapStyle.SATELLITE -> "© Esri, Maxar"
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
        // 1) Compose the map at full size with whole-pixel tile positions.
        //    Scaling tiles one by one leaves faint lines on the seams, so we never do that.
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)

        val n = 2.0.pow(zoom).toInt()
        val cx = lonToWorldX(lng, zoom)
        val cy = latToWorldY(lat, zoom)
        val left = floor(cx - width / 2.0).toLong()
        val top = floor(cy - height / 2.0).toLong()
        // Where the exact position falls inside the image (sub-pixel accurate).
        val dotX = (cx - left).toFloat()
        val dotY = (cy - top).toFloat()

        // Dark mode dims the bright street map; satellite imagery is already dark enough.
        val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        basePaint.colorFilter = tileColorFilter(darkMode && style == MapStyle.STANDARD)
        val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawColor(placeholderPaint(darkMode).color)

        val tx0 = Math.floorDiv(left, TILE.toLong()).toInt()
        val tx1 = Math.floorDiv(left + width, TILE.toLong()).toInt()
        val ty0 = Math.floorDiv(top, TILE.toLong()).toInt()
        val ty1 = Math.floorDiv(top + height, TILE.toLong()).toInt()
        for (layer in layers(style)) {
            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= n) continue
                for (tx in tx0..tx1) {
                    val wx = ((tx % n) + n) % n
                    val tile = loadTile(context, layer, zoom, wx, ty) ?: continue
                    val dx = (tx.toLong() * TILE - left).toFloat()
                    val dy = (ty.toLong() * TILE - top).toFloat()
                    c.drawBitmap(tile, dx, dy, if (layer.base) basePaint else overlayPaint)
                }
            }
        }

        val density = context.resources.displayMetrics.density
        drawLocationDot(c, dotX, dotY, accuracyMeters, metersPerPixel(lat, zoom), density)
        drawAttribution(c, width, height, attribution(style), density)

        // 2) Shrink the finished image once (smooth), to keep the widget payload small.
        val scale = minOf(1f, MAX_BITMAP_SIDE.toFloat() / maxOf(width, height))
        val bw = (width * scale).toInt().coerceAtLeast(1)
        val bh = (height * scale).toInt().coerceAtLeast(1)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(full, bw, bh, true).also { full.recycle() } else full

        // 3) Round the corners (the widget also clips, this keeps older launchers tidy).
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = cornerRadiusPx * scale
        oc.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), r, r, mask)
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(small, 0f, 0f, mask)
        small.recycle()
        return out
    }

    private fun drawLocationDot(c: Canvas, x: Float, y: Float, accuracy: Float, mpp: Double, density: Float) {
        val blue = Color.rgb(26, 115, 232)
        val minR = 10f * density
        val radius = if (mpp > 0) (accuracy / mpp).toFloat().coerceIn(minR, 60f * density) else minR
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blue
            alpha = 45
            style = Paint.Style.FILL
        }
        c.drawCircle(x, y, radius, halo)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(3f * density, 0f, 1f * density, Color.argb(90, 0, 0, 0))
        }
        c.drawCircle(x, y, 8f * density, shadow)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue }
        c.drawCircle(x, y, 6f * density, dot)
    }

    private fun drawAttribution(c: Canvas, w: Int, h: Int, text: String, density: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8f * density
            setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
        }
        val tw = p.measureText(text)
        // Keep clear of the rounded corner.
        c.drawText(text, w - tw - 14f * density, h - 6f * density, p)
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
    private fun loadTile(context: Context, layer: Layer, z: Int, x: Int, y: Int): Bitmap? {
        val key = "${layer.key}/$z/$x/$y"
        memCache().get(key)?.let { return it }

        val file = File(context.cacheDir, "tiles/${layer.key}/$z/$x/$y.img")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS) {
            BitmapFactory.decodeFile(file.path)?.let { memCache().put(key, it); return it }
        }

        val bmp = download(layer.url(z, x, y), file) ?: return if (file.exists()) {
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
