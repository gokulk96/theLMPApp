# LMP Near Me (Android)

An Android app that shows the **MISO locational marginal price (LMP) at the
pricing node nearest to your location**, plus the MISO-wide **generation
(fuel) mix**, refreshed hourly.

Like a weather app, but for electricity prices.

## What it does

- Gets your location (FusedLocationProvider, coarse or fine).
- Finds the nearest MISO pricing point from a bundled directory of the
  8 trading hubs and ~30 load-zone CPNodes, each tagged with approximate
  service-territory coordinates ([`miso_nodes.csv`](app/src/main/assets/miso_nodes.csv)).
- Fetches the current 5-minute consolidated LMP table from MISO's **public
  Data Broker API** (no API key required) and shows the nearest node's LMP
  with its energy / congestion / loss components, plus the next 6 nearest
  pricing points.
- Fetches the MISO system fuel mix and renders it as share bars
  (MW and % per category).
- Refresh cadence while the app is open: **LMP every 5 minutes, fuel mix
  every 60 minutes**, plus a manual refresh button.

## Data source

MISO real-time Data Broker (free, public):

```
https://api.misoenergy.org/MISORTWDDataBroker/DataBrokerServices.asmx?messageType=getlmpconsolidatedtable&returnType=json
https://api.misoenergy.org/MISORTWDDataBroker/DataBrokerServices.asmx?messageType=getfuelmix&returnType=json
```

JSON key casing in these feeds has drifted over time, so the parser
(`MisoApiClient`) is lenient and tries known variants (`loss`/`MLC`,
`congestion`/`MCC`, etc.). If MISO renames a field, add the variant there.

## Caveats

- **Node coordinates are approximate.** MISO does not publish CPNode
  geographic coordinates. Hubs are placed at a representative city in their
  region; load zones at the centroid of the utility's service territory.
  "Nearest" is therefore zone-level, not substation-level accuracy.
- **Node-name matching.** The directory matches consolidated-table rows by
  CPNode name, with per-node aliases (e.g. `ALTW` vs `ALTW.LZ`). If a
  directory entry doesn't appear in the live table the app shows it as
  `n/a` rather than hiding it — check the live table and add the actual
  name as an alias in `miso_nodes.csv`.
- The fuel mix is MISO system-wide; MISO does not publish a public
  per-node generation mix.
- MISO market data is for informational use; check MISO's data terms
  before redistributing.

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
│   ├── MisoApiClient.kt         # Data Broker HTTP + lenient JSON parsing
│   ├── NodeDirectory.kt         # bundled node CSV + haversine nearest-node
│   └── LmpRepository.kt         # joins live prices with the directory
├── location/LocationProvider.kt # FusedLocationProvider wrapper
└── ui/
    ├── LmpViewModel.kt          # state + 5-min / hourly refresh loops
    ├── LmpScreen.kt             # nearest-node card, nearby list, fuel mix
    └── theme/Theme.kt
app/src/main/assets/miso_nodes.csv   # node directory with coordinates
```

## Ideas for later

- Other ISOs (PJM, ERCOT, CAISO) behind the same `NodeDirectory` +
  repository interfaces, selected by which footprint contains the user.
- LMP history sparkline (Data Broker also serves ex-ante/ex-post feeds).
- Home-screen widget and price-spike notifications via WorkManager.
- Finer-grained node directory (generator CPNodes near known plants).
