package com.asherbarr.livemap.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

class LiveMapWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Runs on the main thread, so hand the (possibly networked) draw to the worker.
        com.asherbarr.livemap.data.workerExecutor.execute {
            appWidgetIds.forEach { WidgetUpdater.updateOne(context, manager, it, render = true) }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        com.asherbarr.livemap.data.workerExecutor.execute {
            WidgetUpdater.updateOne(context, manager, appWidgetId, render = true)
        }
    }
}
