# LMP Near Me (Android)

An Android app that shows the **locational marginal price (LMP) at the
pricing point nearest to your location** — MISO or NYISO, picked
automatically — plus that market's **generation (fuel) mix**, refreshed
hourly.

Like a weather app, but for electricity prices.

## What it does

- Gets your location (FusedLocationProvider, coarse or fine).
- Picks the market whose pricing point is nearest (manual override in the
  top-bar menu: Auto / MISO / NYISO).
- Finds the nearest pricing point from the bundled directory for that
  market and shows its current LMP with energy / congestion / loss
  components, plus the next 6 nearest pricing points.
- Fetches the market's system fuel mix and renders it as share bars
  (MW and % per category).
- Refresh cadence while the app is open: **LMP every 5 minutes, fuel mix
  every 60 minutes**, plus a manual refresh button.

## Markets and data sources

Both feeds are free and public; no API keys.

### MISO

Real-time Data Broker (5-min consolidated LMP table + fuel mix):

```
https://api.misoenergy.org/MISORTWDDataBroker/DataBrokerServices.asmx?messageType=getlmpconsolidatedtable&returnType=json
https://api.misoenergy.org/MISORTWDDataBroker/DataBrokerServices.asmx?messageType=getfuelmix&returnType=json
```

JSON key casing in these feeds has drifted over time, so the parser
(`MisoApiClient`) is lenient and tries known variants (`loss`/`MLC`,
`congestion`/`MCC`, etc.). If MISO renames a field, add the variant there.

Node directory: 8 trading hubs + ~30 load-zone CPNodes
([`miso_nodes.csv`](app/src/main/assets/miso_nodes.csv)).

### NYISO (New York / NYC)

Daily CSVs on `mis.nyiso.com` — the same feeds the
[NYISOToolkit](https://github.com/m4rz910/NYISOToolkit) Python package
wraps (`lbmp_rt_5m` and `fuel_mix_5m` in its dataset map):

```
https://mis.nyiso.com/public/csv/realtime/{YYYYMMDD}realtime_zone.csv
https://mis.nyiso.com/public/csv/rtfuelmix/{YYYYMMDD}rtfuelmix.csv
```

`NyisoApiClient` fetches today's file (Eastern Time), takes the latest
5-minute interval, and falls back to yesterday's file just after midnight.
HTTPS is tried first with a cleartext-HTTP fallback scoped to
`mis.nyiso.com` only (see `network_security_config.xml`).

**Sign convention:** NYISO publishes `LBMP = energy + losses − congestion`,
whereas MISO uses `LMP = energy + congestion + losses`. The NYISO client
negates the congestion column so every `NodePrice` in the app satisfies the
same additive identity. (NYC's typically negative published MCC therefore
shows up as a positive congestion contribution — the intuitive reading.)

Node directory: all 11 NYISO load zones A–K, including **N.Y.C. (Zone J)**
and Long Island (Zone K)
([`nyiso_nodes.csv`](app/src/main/assets/nyiso_nodes.csv)). NYISO's public
real-time zonal feed is zone-level, so within NYC the answer is always
Zone J.

## Caveats

- **Node coordinates are approximate.** Neither ISO publishes pricing-node
  geographic coordinates. Hubs are placed at a representative city in their
  region; load zones at a representative point in their territory.
  "Nearest" is therefore zone-level, not substation-level accuracy.
- **Node-name matching.** The directory matches live rows by node name,
  with per-node aliases (e.g. `ALTW` vs `ALTW.LZ`, `N.Y.C.` vs `NYC`). If a
  directory entry doesn't appear in the live data the app shows it as
  `n/a` rather than hiding it — check the live feed and add the actual
  name as an alias in the market's nodes CSV.
- The fuel mix is system-wide per market; neither ISO publishes a public
  per-node generation mix.
- Market data is for informational use; check MISO's and NYISO's data
  terms before redistributing.

## Build

Requires Android Studio (or an Android SDK + JDK 17). No API keys or
`local.properties` secrets needed.

```bash
./gradlew :app:assembleDebug   # build APK
./gradlew :app:testDebugUnitTest   # run unit tests (parsers, haversine, CSV)
```

Open the project in Android Studio and run on a device/emulator with Google
Play services (the app uses FusedLocationProvider). minSdk 26, targetSdk 35.

> Note: this project was authored in a sandbox without an Android SDK, so it
> has not been compiled yet — expect at most minor fix-ups on first build.

## Project structure

```
app/src/main/java/com/gokul/lmpapp/
├── MainActivity.kt              # permission flow + Compose entry point
├── data/
│   ├── Models.kt                # NodeInfo, NodePrice, FuelMix, ...
│   ├── Market.kt                # Market enum + MarketDataSource interface
│   ├── MisoApiClient.kt         # MISO Data Broker JSON (lenient parsing)
│   ├── NyisoApiClient.kt        # NYISO mis.nyiso.com daily CSVs
│   ├── NodeDirectory.kt         # bundled node CSV + haversine nearest-node
│   └── LmpRepository.kt         # joins live prices with the directory
├── location/LocationProvider.kt # FusedLocationProvider wrapper
└── ui/
    ├── LmpViewModel.kt          # market selection + 5-min/hourly loops
    ├── LmpScreen.kt             # nearest-node card, nearby list, fuel mix
    └── theme/Theme.kt
app/src/main/assets/miso_nodes.csv    # MISO node directory
app/src/main/assets/nyiso_nodes.csv   # NYISO zone directory
```

## Ideas for later

- More ISOs (PJM, ERCOT, CAISO) — add a `MarketDataSource` implementation,
  a nodes CSV, and a `Market` enum entry; auto-selection already
  generalizes to any number of markets.
- LMP history sparkline (Data Broker also serves ex-ante/ex-post feeds).
- Home-screen widget and price-spike notifications via WorkManager.
- Finer-grained node directory (generator CPNodes near known plants).
