package com.asherbarr.livemap.map

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.asherbarr.livemap.data.Prefs
import java.util.Locale

/**
 * Opened by a widget tap. Uses the official Google Maps URL (search/?api=1&query=lat,lng),
 * sent to the Google Maps app when it is installed, otherwise to the default browser.
 * The result screen has Directions, so navigation starts from there.
 */
class OpenInMapsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        if (lat == null || lng == null) {
            Toast.makeText(this, "No location yet. Start tracking first.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val query = String.format(Locale.US, "%.6f,%.6f", lat, lng)
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")

        val mapsIntent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(GOOGLE_MAPS_PACKAGE)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val target = if (mapsIntent.resolveActivity(packageManager) != null) {
            mapsIntent
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        try {
            startActivity(target)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open a maps app", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}
