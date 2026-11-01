package com.asherbarr.livemap.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.asherbarr.livemap.ui.MainActivity
import com.asherbarr.livemap.R
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.asherbarr.livemap.data.LiveStatus
import com.asherbarr.livemap.data.Prefs
import com.asherbarr.livemap.data.workerExecutor
import com.asherbarr.livemap.widget.WidgetUpdater

/**
 * Foreground service (type = location) that follows the device with the Fused Location Provider.
 *
 * - Only runs after the user taps Start in the app (never silently).
 * - Shows a persistent notification with a Stop action while active.
 * - Stores the last fix and pushes text updates to the widget; redraws the map only when moved.
 * - Survives process death via START_STICKY, but will not start location in the background
 *   if Android refuses; in that case it switches to "paused" and posts a resume notification.
 */
class TrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var prefs: Prefs
    private var updating = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onFix)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable && prefs.trackingEnabled) {
                prefs.status = LiveStatus.UNAVAILABLE
                WidgetUpdater.refreshAsync(applicationContext, render = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        prefs = Prefs(this)
        ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // Interval changed in settings. Only meaningful while we are already tracking.
                if (updating) requestUpdates() else stopSelf()
                return START_STICKY
            }
        }

        // ACTION_START, or a null intent when Android restarts us after process death.
        if (intent == null && !prefs.trackingEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasFineLocation(this)) {
            prefs.trackingEnabled = false
            prefs.status = LiveStatus.UNAVAILABLE
            WidgetUpdater.refreshAsync(applicationContext, render = false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) return START_NOT_STICKY

        prefs.trackingEnabled = true
        if (prefs.status != LiveStatus.LIVE) prefs.status = LiveStatus.WAITING
        requestUpdates()
        updating = true
        WidgetUpdater.refreshAsync(applicationContext, render = true)
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIF_TRACKING,
            buildTrackingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (background start) or SecurityException.
        prefs.trackingEnabled = false
        prefs.status = LiveStatus.PAUSED
        WidgetUpdater.refreshAsync(applicationContext, render = false)
        postResumeNotification(this)
        stopSelf()
        false
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand via hasFineLocation()
    private fun requestUpdates() {
        val interval = prefs.intervalMs
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            prefs.status = LiveStatus.UNAVAILABLE
            WidgetUpdater.refreshAsync(applicationContext, render = false)
        }
    }

    private fun onFix(loc: Location) {
        prefs.saveFix(loc)
        val app = applicationContext
        workerExecutor.execute {
            val moved = prefs.needsRedraw(loc.latitude, loc.longitude)
            WidgetUpdater.updateAll(app, render = moved)
        }
    }

    private fun stopTracking() {
        if (updating) fused.removeLocationUpdates(callback)
        updating = false
        prefs.trackingEnabled = false
        prefs.status = LiveStatus.PAUSED
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        WidgetUpdater.refreshAsync(applicationContext, render = false)
        stopSelf()
    }

    override fun onDestroy() {
        if (updating) fused.removeLocationUpdates(callback)
        updating = false
        super.onDestroy()
    }

    private fun buildTrackingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.asherbarr.livemap.action.START"
        const val ACTION_STOP = "com.asherbarr.livemap.action.STOP"
        const val ACTION_RELOAD = "com.asherbarr.livemap.action.RELOAD"
        private const val NOTIF_TRACKING = 1001
        private const val NOTIF_RESUME = 1002
        private const val CHANNEL_TRACKING = "tracking"
        private const val CHANNEL_RESUME = "resume"

        fun hasFineLocation(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        fun hasNotificationPermission(context: Context) =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }

        /** Tell a running service to re-request updates with the new interval. */
        fun reload(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_RELOAD))
        }

        fun ensureChannels(context: Context) {
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_TRACKING,
                    context.getString(R.string.channel_tracking),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_tracking_desc) },
            )
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_RESUME,
                    context.getString(R.string.channel_resume),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        /** Asks the user to tap, instead of starting location silently from the background. */
        @SuppressLint("MissingPermission")
        fun postResumeNotification(context: Context) {
            if (!hasNotificationPermission(context)) return
            ensureChannels(context)
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_RESUME)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(context.getString(R.string.resume_notification_title))
                .setContentText(context.getString(R.string.resume_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_RESUME, n)
        }
    }
}
