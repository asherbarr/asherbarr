# 🗺️ LiveMap Widget

A live map on your Android home screen. It follows you as you move, shows a blue location dot, and opens Google Maps at your location when you tap it.

Built for Samsung Galaxy / One UI (Android 12 and newer). Written in Kotlin, built with Gradle, opens in Android Studio.

---

## ⬇️ Download & install

1. On your phone, tap **[⬇️ LiveMap-v1.1.1.apk](https://github.com/asherbarr/asherbarr/releases/download/livemap-v1.1.1/LiveMap-v1.1.1.apk)** (or open the [release page](https://github.com/asherbarr/asherbarr/releases/tag/livemap-v1.1.1)).
2. Open the file. When Android asks, allow **Install unknown apps** for your browser or file manager.
3. Open **LiveMap** → tap **Start tracking** → allow **Precise location** and **Notifications**.
4. Long-press the home screen → **Widgets** → **LiveMap**, then drag it onto the screen and resize it however you like.
5. Recommended on Samsung: **Settings → Apps → LiveMap → Battery → Unrestricted**, and make sure LiveMap isn't in **Sleeping apps**.

The release APK is shrunk with R8 (~2 MB) and signed with a debug key, so it installs by sideloading. It isn't a Play Store build.

---

## ✨ What it does

| Feature | How |
|---|---|
| Real map in the widget | OpenStreetMap tiles (streets, roads, rivers, landmarks) drawn into an image |
| Blue location dot | Drawn at the center, with a pale accuracy circle |
| Follows you | Map is re-centered on every new fix and redrawn once you've moved about 8 m |
| 5 s updates, 10 s fallback | Fused Location Provider, high accuracy, interval set in Settings |
| Screen-off tracking | Location foreground service with a persistent notification |
| Last update time | Shown on the widget pill (`● Live · 14:32:05`) |
| Paused / unavailable state | `⏸ Paused` (amber) or `⚠ Unavailable` (red) on the widget |
| Tap → Google Maps | Official Maps URL `https://www.google.com/maps/search/?api=1&query=lat,lng`. Directions is one tap away |
| Start / Stop | In the app, in Settings, and a **Stop** button on the notification |
| Light / dark | Map tiles are dimmed in dark mode, and the app uses Material You dynamic colors |
| Satellite with names | Esri World Imagery plus road and place-name overlays (Hebrew + English) |
| Zoom from the widget | **+ / −** buttons in the corner, like the Google Maps widget (zoom 10–19) |

---

## 🧠 How it works (and why)

### Why the widget is an image, not a live MapView
Home-screen widgets are drawn by the launcher from `RemoteViews`. They can only hold a fixed set of basic views (`ImageView`, `TextView`, layouts). **A Google Maps `MapView`, a `SurfaceView`, or any custom view can't be put in a widget**, and widgets can't be panned or zoomed with gestures. That's an Android limit, not something this app can work around.

So the closest valid solution is:

```
FusedLocationProvider (every 5 s)
        │
        ▼
TrackingService (foreground, type=location)
        │  saves the fix on the device
        ▼
WidgetUpdater ──moved ≥ 8 m?──► TileMap.render()
        │                         ├─ memory cache (64 tiles)
        │                         ├─ disk cache (7 days)
        │                         └─ network (missing tiles only)
        ▼
AppWidgetManager.updateAppWidget()      ← new map image
AppWidgetManager.partiallyUpdateAppWidget() ← time/status text only
```

- **Moved?** The map is redrawn (re-centered under the blue dot). Usually 0 new downloads, because the nearby tiles are already cached.
- **Didn't move?** Only the time text updates. No image is redrawn and nothing is downloaded.
- The map is composed at full size (no seams between tiles), then shrunk once to at most 640 px on its longest side, so `RemoteViews` stays well under Android's binder size limit. On a big widget it looks slightly soft, and that's the trade-off.

### Files

```
app/src/main/java/com/asherbarr/livemap/
├── data/Prefs.kt                 settings + last fix (SharedPreferences, on the device only)
├── location/TrackingService.kt   foreground service, fused location, notification
├── location/BootReceiver.kt      after a reboot: "tap to resume" (never auto-tracks)
├── map/TileMap.kt                Web Mercator math, tile cache, map + marker drawing
├── map/OpenInMapsActivity.kt     widget tap → Google Maps (falls back to the browser)
├── widget/LiveMapWidgetProvider.kt
├── widget/WidgetUpdater.kt
└── ui/                           Compose screens: Main, Settings, theme
```

---

## 🗺️ Map provider: cost, keys and rules

**Chosen: OpenStreetMap standard tiles. Free, no API key, no billing.**

| Option | Cost | Key | Why / why not |
|---|---|---|---|
| **OpenStreetMap** ✅ | Free | None | Full street, river and POI detail. Allowed for light use with caching, an identifying User-Agent and attribution |
| **Esri World Imagery + Reference layers** (Satellite) ✅ | Free for non-commercial use | None | Imagery, roads and place names. Attribution required. Commercial use needs an ArcGIS license. Uses up to 3 tiles per map square, all cached |
| Google Static Maps API | ~$2 per 1,000 maps after the free credit | Yes + billing | At one image per 5 s, it could cost money on a long drive |
| Mapbox / MapTiler | Free tier, then paid | Yes | Good fallback if OSM ever rate-limits you |
| Scraping Google Maps tiles | — | — | ❌ Breaks Google's Terms. Not used |

What the app does to respect the **[OSM Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/)**:
- Sends a clear `User-Agent: LiveMapWidget/1.0 (...)`.
- Caches every tile on disk for 7 days and in memory, and only downloads tiles it hasn't seen.
- Never pre-fetches or bulk-downloads areas.
- Shows `© OpenStreetMap` (or `© Esri, Maxar`) on the widget image.

⚠️ **Limit:** this is fine for a personal app. If you ever publish LiveMap to lots of users, OSM's public servers aren't meant for that. Switch to a paid or own tile server (MapTiler, Stadia, Thunderforest) by changing `tileUrl()` in `TileMap.kt`.

---

## 🔋 Battery

These figures are estimates from how Android location works. They haven't been measured on a phone.

| Mode | Drain |
|---|---|
| Tracking **off** | None. The service is fully stopped and no location is requested |
| **10 s** | Medium. About half the GPS fixes of 5 s |
| **5 s** | High. GPS stays mostly on. This is the price of "live" |
| Map redraws | Small. Only when you move, mostly from cache |
| Network | Small. Typically 0 KB when you stay in the same area, a few tiles (~10–40 KB each) when you enter a new one |

Use 10 s for long trips, and **Stop** when you don't need it.

---

## 🚧 Android limits (honest list)

| Want | Reality | What LiveMap does instead |
|---|---|---|
| Interactive Google map inside the widget | Impossible with `RemoteViews` | Renders a real map image and updates it |
| Exactly 5 s every time | Android/Fused may batch or slow down when you're still or the battery is low | Requests 5 s, and accepts what the system delivers |
| Keep tracking with the screen off | Works with a foreground service, but Samsung may still kill it if battery is "Optimized" | Shows battery status in Settings, with a button to open battery settings |
| Restart tracking after a reboot | Android 12+ blocks starting a location service from the background, and silent tracking isn't allowed anyway | Posts a "Tracking paused, tap to resume" notification |
| Restart after the app is killed | Android may refuse a background restart | Tries once, otherwise marks the widget paused and notifies you |
| Map rotation by heading | Phone heading while walking is unreliable | Map stays north-up |
| Widget refresh without the app | Widgets can only refresh every 30 min on their own | All live updates come from the tracking service |

---

## 🔒 Privacy

- Location is stored **only on the phone** (app-private storage), and is never uploaded, logged or shared.
- The only network requests go to the map tile server, asking for map squares (`zoom/x/y`) around you, which is unavoidable to draw a map. No account or ID is sent.
- Tracking only starts when you tap **Start**, and a notification is always visible while it runs.
- No background-location permission is requested.

---

## 🛠️ Build it yourself

Requires JDK 17 and the Android SDK (platform 36). Or just open the folder in Android Studio.

```bash
cd livemap-widget
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk (R8 shrunk)
adb install -r app/build/outputs/apk/release/app-release.apk
```

Every push that touches `livemap-widget/` builds the APK in GitHub Actions and publishes it as a release (`.github/workflows/livemap-release.yml`).

**Target SDK:** compiled against API 36. Android 17 runs it normally. Bump `compileSdk`/`targetSdk` to 37 once that SDK is installed in Android Studio.

**Updating from a different build:** if Android says the app "conflicts with an existing package", uninstall LiveMap first. Each CI build is signed with its own debug key.
