package com.asherbarr.livemap.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme.typography
import com.asherbarr.livemap.data.LiveStatus
import com.asherbarr.livemap.data.Prefs
import com.asherbarr.livemap.location.TrackingService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveMapTheme {
                MainScreen(onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    // Recomposes every second, so the "last update" time stays current.
    val tracking = remember(tick) { prefs.trackingEnabled }
    val status = remember(tick) { prefs.status }
    val fixTime = remember(tick) { prefs.lastFixTime }
    val hasFine = remember(tick) { TrackingService.hasFineLocation(context) }
    val start = rememberStartTracking(onPermanentlyDenied = { denied = true })

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LiveMap", style = typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "A live map on your home screen",
                        style = typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }

            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        statusHeadline(tracking, status, hasFine),
                        style = typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (fixTime > 0) {
                            "Last update " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(fixTime))
                        } else {
                            "No location fix yet"
                        },
                        style = typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (tracking) {
                Button(
                    onClick = {
                        TrackingService.stop(context)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Stop tracking", style = typography.titleMedium) }
            } else {
                Button(
                    onClick = { denied = false; start() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) { Text("Start tracking", style = typography.titleMedium) }
            }

            if (denied || !hasFine) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Precise location is off. LiveMap can't follow you without it. " +
                                "Allow it in app permissions, or tap Start to be asked again.",
                            style = typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { openAppSettings(context) }) { Text("Open app permissions") }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Add the widget", style = typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Long-press your home screen → Widgets → LiveMap. Resize it freely. " +
                            "Tap the map to open Google Maps at your location.",
                        style = typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open settings")
            }

            Text(
                "Location stays on this device. The app only sends tile requests (map image coordinates) " +
                    "to the map provider.",
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusHeadline(tracking: Boolean, status: LiveStatus, hasFine: Boolean): String = when {
    !hasFine -> "Location permission needed"
    !tracking -> "Tracking is off"
    status == LiveStatus.LIVE -> "Live · following you"
    status == LiveStatus.UNAVAILABLE -> "Location unavailable"
    else -> "Waiting for GPS…"
}
