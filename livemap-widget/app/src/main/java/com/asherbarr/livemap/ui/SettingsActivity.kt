package com.asherbarr.livemap.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.asherbarr.livemap.data.MapStyle
import com.asherbarr.livemap.data.Prefs
import com.asherbarr.livemap.location.TrackingService
import com.asherbarr.livemap.widget.WidgetUpdater
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme.typography
import kotlinx.coroutines.delay

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveMapTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val tracking = remember(tick) { prefs.trackingEnabled }
    val interval = remember(tick) { prefs.intervalMs }
    val zoom = remember(tick) { prefs.zoom }
    val style = remember(tick) { prefs.mapStyle }
    val batteryOk = remember(tick) { isBatteryUnrestricted(context) }
    val hasFine = remember(tick) { TrackingService.hasFineLocation(context) }
    val hasNotif = remember(tick) { TrackingService.hasNotificationPermission(context) }
    val start = rememberStartTracking()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Tracking") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live tracking", style = typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            if (tracking) "On. A notification is shown while active." else "Off. Nothing is tracked.",
                            style = typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = tracking,
                        onCheckedChange = { on ->
                            if (on) start() else TrackingService.stop(context)
                        },
                    )
                }
            }

            Section("Update interval") {
                val options = listOf(5_000L to "5 s (target)", 10_000L to "10 s (fallback)")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (ms, label) ->
                        SegmentedButton(
                            selected = interval == ms,
                            onClick = {
                                prefs.intervalMs = ms
                                if (tracking) TrackingService.reload(context)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Android decides the exact cadence. It may slow down when you're still.",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Map zoom: $zoom") {
                Slider(
                    value = zoom.toFloat(),
                    onValueChange = { v ->
                        val z = v.roundToInt()
                        if (z != prefs.zoom) {
                            prefs.zoom = z
                            prefs.invalidateRender()
                            WidgetUpdater.refreshAsync(context, render = true)
                        }
                    },
                    valueRange = Prefs.MIN_ZOOM.toFloat()..Prefs.MAX_ZOOM.toFloat(),
                    steps = Prefs.MAX_ZOOM - Prefs.MIN_ZOOM - 1,
                )
                Text(
                    "Higher zoom shows more street detail and less area.",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Map style") {
                val styles = MapStyle.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    styles.forEachIndexed { index, s ->
                        SegmentedButton(
                            selected = style == s,
                            onClick = {
                                prefs.mapStyle = s
                                prefs.invalidateRender()
                                WidgetUpdater.refreshAsync(context, render = true)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                        ) { Text(s.label) }
                    }
                }
                Text(
                    "Standard: OpenStreetMap. Satellite: Esri World Imagery. Both are free, with no API key.",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Battery") {
                Text(
                    "Battery use (not measured on your phone, estimated from how Android works):",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "• Tracking off: no battery used.\n" +
                        "• 10 s: about half the GPS fixes of 5 s, so noticeably lighter.\n" +
                        "• 5 s: the heaviest mode. Expect a clearly higher drain, mostly from GPS.\n" +
                        "• Map: tiles are cached, so the network is only used for new areas.",
                    style = typography.bodyMedium,
                )
                Text(
                    "Battery optimisation: " + if (batteryOk) "Unrestricted (good)" else "Optimised (may pause tracking with the screen off)",
                    style = typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open battery settings") }
                Text(
                    "On Samsung: set LiveMap to Unrestricted, and make sure it isn't in \"Sleeping apps\".",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Permissions") {
                PermissionRow("Precise location", hasFine)
                PermissionRow("Notifications", hasNotif)
                Text(
                    "Background location is not needed. Tracking runs as a foreground service that you start.",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app permissions")
                }
            }
            Spacer8()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = typography.bodyMedium)
        Text(
            if (granted) "Granted" else "Not granted",
            style = typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
