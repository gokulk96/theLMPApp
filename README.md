# NYC LMP (Android)

An Android app that shows the **NYISO locational marginal price (LBMP) at
the load zone nearest to your location** — Zone J for New York City — plus
the zone's **real-time actual load** and the NYISO **generation (fuel)
mix**.

Like a weather app, but for electricity prices.

## What it does

- Gets your location (FusedLocationProvider, with a LocationManager
  fallback for emulators).
- Finds the nearest NYISO load zone from a bundled directory of all 11
  zones A–K ([`nyiso_nodes.csv`](app/src/main/assets/nyiso_nodes.csv)) and
  shows its current LBMP with energy / congestion / loss components, plus
  the next 6 nearest zones.
- Shows the zone's real-time actual load and the NYISO system total.
- Shows the NYISO fuel mix as share bars (MW and % per category).
- Refresh cadence while the app is open: **LBMP + load every 5 minutes,
  fuel mix every 60 minutes**, plus a manual refresh button.
- **Home-screen widget** (Jetpack Glance) showing the zone name, LBMP, and
  interval timestamp; tap to open the app.

## Widget

Long-press the home screen → Widgets → LMP Near Me. Notes:

- The widget reuses the **zone the app last resolved** — it never requests
  location in the background (defaults to N.Y.C. Zone J before first run).
- A WorkManager job refreshes it roughly every **30 minutes**. Android
  enforces a 15-minute minimum for periodic background work and may batch
  it to save battery, so the widget cannot match the 5-minute in-app
  cadence. Opening the app also pushes a fresh price to the widget
  immediately.

## Data sources

All feeds are free public CSVs on `mis.nyiso.com` (no API key) — the same
data the [NYISOToolkit](https://github.com/m4rz910/NYISOToolkit) Python
package and the NYISO OASIS site expose:

| Data | URL |
|---|---|
| Zonal LBMP (live snapshot) | `https://mis.nyiso.com/public/realtime/realtime_zone_lbmp.csv` |
| Zonal LBMP (daily, fallback) | `https://mis.nyiso.com/public/csv/realtime/{YYYYMMDD}realtime_zone.csv` |
| Real-time actual load | `https://mis.nyiso.com/public/csv/pal/{YYYYMMDD}pal.csv` |
| Fuel mix | `https://mis.nyiso.com/public/csv/rtfuelmix/{YYYYMMDD}rtfuelmix.csv` |

Dated files accumulate 5-minute rows through the day; the app takes the
latest interval. Today's file is tried first, then yesterday's (covers the
minutes right after midnight ET). HTTPS is tried before HTTP; cleartext is
permitted for `mis.nyiso.com` only (`network_security_config.xml`).

**Sign convention:** NYISO publishes `LBMP = energy + losses − congestion`.
The client negates the congestion column so every price in the app
satisfies `lmp = energy + congestion + loss`; NYC's typically negative
published MCC therefore shows as a positive congestion contribution —
the intuitive reading.

## Caveats

- NYISO's public real-time pricing is **zonal**: anywhere in the five
  boroughs resolves to Zone J. Zone coordinates in the bundled directory
  are representative points, so "nearest" is zone-level accuracy.
- The fuel mix is NYISO system-wide; there is no public per-zone mix.
- NYISO market data is for informational use; check NYISO's data terms
  before redistributing.

## Build

Requires Android Studio (or an Android SDK + JDK 17). No API keys or
`local.properties` secrets needed.

```bash
./gradlew :app:assembleDebug        # build APK
./gradlew :app:testDebugUnitTest    # run unit tests (parsers, haversine, CSV)
```

Run on a device or emulator (API 26+). On an emulator, set a location via
Extended Controls (⋯) → Location — e.g. Times Square 40.758, −73.985 —
and click **Set Location**.

## Project structure

```
app/src/main/java/com/gokul/lmpapp/
├── MainActivity.kt              # permission flow + Compose entry point
├── data/
│   ├── Models.kt                # NodeInfo, NodePrice, FuelMix, LoadSnapshot
│   ├── Market.kt                # MarketDataSource interface
│   ├── NyisoApiClient.kt        # mis.nyiso.com CSV fetching + parsing
│   ├── NodeDirectory.kt         # bundled zone CSV + haversine nearest-zone
│   └── LmpRepository.kt         # joins live prices with the directory
├── location/LocationProvider.kt # Fused + LocationManager fallback
├── widget/
│   ├── LmpWidget.kt             # Glance widget UI + receiver
│   ├── WidgetRefreshWorker.kt   # 30-min WorkManager background refresh
│   └── WidgetStateStore.kt      # last-resolved zone shared with the app
└── ui/
    ├── LmpViewModel.kt          # state + 5-min / hourly refresh loops
    ├── LmpScreen.kt             # LBMP card, load card, zones, fuel mix
    └── theme/Theme.kt
app/src/main/assets/nyiso_nodes.csv   # 11 NYISO zones with coordinates
```

## Ideas for later

- Day-ahead vs real-time LBMP comparison (`damlbmp` feed).
- LBMP history sparkline from the accumulated daily file.
- Price-spike notifications via WorkManager.
- Other ISOs behind the same `MarketDataSource` interface.
