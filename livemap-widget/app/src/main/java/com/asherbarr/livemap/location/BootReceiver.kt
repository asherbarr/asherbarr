package com.asherbarr.livemap.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.asherbarr.livemap.data.LiveStatus
import com.asherbarr.livemap.data.Prefs
import com.asherbarr.livemap.widget.WidgetUpdater

/**
 * After a reboot we never restart location on our own (Android blocks background location
 * starts and silent tracking is not allowed). If tracking was on, we mark it paused and ask
 * the user to tap to resume.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = Prefs(context)
        if (!prefs.trackingEnabled) {
            WidgetUpdater.refreshAsync(context, render = false)
            return
        }
        prefs.trackingEnabled = false
        prefs.status = LiveStatus.PAUSED
        TrackingService.postResumeNotification(context)
        WidgetUpdater.refreshAsync(context, render = false)
    }
}
