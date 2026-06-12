package com.gokul.lmpapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * ERCOT market data via the ERCOT API v2 (api.ercot.com).
 * Obtain a free subscription key at developer.ercot.com and set
 * ercot.api.key in local.properties — it is injected via BuildConfig.
 *
 * ERCOT terminology vs NYISO:
 *   Settlement Point Price (SPP) = LMP equivalent
 *   Delivery Hour (HE) = Hour Ending: HE1 = 00:00-01:00, HE24 = 23:00-24:00
 *   Load zones: LZ_NORTH, LZ_HOUSTON, LZ_SOUTH, LZ_WEST + 4 small utility zones
 *
 * Fuel mix uses the public v1 endpoint (no auth) that powers ERCOT's own
 * grid dashboard — the authenticated v2 equivalent is not stable.
 */
class ErcotApiClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val marketZone: ZoneId = ZoneId.of("America/Chicago"),
) : MarketDataSource {

    init {
        if (apiKey.isBlank()) {
            error("ERCOT API key not configured — add ercot.api.key=<your_key> to local.properties")
        }
    }

    /** Latest real-time settlement point prices for all load zones. */
    override suspend fun fetchLmpSnapshot(): LmpSnapshot = withContext(Dispatchers.IO) {
        val json = getV2("np6-785-cd/rtd-spp", mapOf("size" to "800"))
        val data = json.getJSONArray("data")
        val rows = (0 until data.length()).map { data.getJSONObject(it) }
        if (rows.isEmpty()) throw IOException("ERCOT RTD SPP response is empty")

        val latest = rows.maxByOrNull { ercotDateTime(it) }!!
        val latestTime = ercotDateTime(latest)

        val prices = rows
            .filter { ercotDateTime(it) == latestTime }
            .mapNotNull { row ->
                val sp = row.optString("settlementPoint").trim().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val price = row.optDouble("settlementPointPrice").takeIf { !it.isNaN() }
                    ?: return@mapNotNull null
                sp.uppercase() to NodePrice(cpNodeName = sp, lmp = price, congestion = null, loss = null)
            }
            .toMap()
        if (prices.isEmpty()) throw IOException("ERCOT SPP: no parsable rows at latest timestamp")

        LmpSnapshot(refId = "${latestTime.format(REF_ID_FMT)} CT", prices = prices)
    }

    /**
     * Fuel mix from ERCOT's public v1 endpoint — no API key needed.
     * Response: { "data": { "time": "...", "list": [{ "fuelType": "...", "genMW": N }] } }
     */
    override suspend fun fetchFuelMix(): FuelMix = withContext(Dispatchers.IO) {
        val body = getPublic("https://www.ercot.com/api/1/services/report/fuelmix")
        val outer = JSONObject(body)
        val data = outer.getJSONObject("data")
        val list = data.getJSONArray("list")
        val categories = (0 until list.length()).mapNotNull { i ->
            val item = list.getJSONObject(i)
            val name = item.optString("fuelType").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val mw = item.optDouble("genMW").takeIf { !it.isNaN() && it >= 0.0 }
                ?: return@mapNotNull null
            FuelCategory(name = name, mw = mw)
        }.sortedByDescending { it.mw }
        if (categories.isEmpty()) throw IOException("ERCOT fuel mix: no parsable categories")
        FuelMix(
            refId = data.optString("time", ""),
            totalMw = categories.sumOf { it.mw },
            categories = categories,
        )
    }

    /** Actual system load summed across all ERCOT forecast zones. */
    override suspend fun fetchZoneLoads(): LoadSnapshot = withContext(Dispatchers.IO) {
        val json = getV2("np6-346-cd/act-sys-load-by-fzn", mapOf("size" to "200"))
        val data = json.getJSONArray("data")
        val rows = (0 until data.length()).map { data.getJSONObject(it) }
        if (rows.isEmpty()) throw IOException("ERCOT load response is empty")

        val latest = rows.maxByOrNull { ercotDateTime(it) }!!
        val latestTime = ercotDateTime(latest)

        val loads = rows
            .filter { ercotDateTime(it) == latestTime }
            .mapNotNull { row ->
                val zone = row.optString("forecastZone").trim().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val mw = row.optDouble("systemLoad").takeIf { !it.isNaN() }
                    ?: return@mapNotNull null
                zone.uppercase() to ZoneLoad(zoneName = zone, mw = mw)
            }
            .toMap()
        if (loads.isEmpty()) throw IOException("ERCOT load: no parsable zones")
        LoadSnapshot(refId = "${latestTime.format(REF_ID_FMT)} CT", loads = loads)
    }

    /** Today's 15-minute RTD price series for one settlement point (e.g. LZ_HOUSTON). */
    override suspend fun fetchZoneSeries(matchKeys: Set<String>): ZoneSeries =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(marketZone).format(DATE_FMT)
            val json = getV2("np6-785-cd/rtd-spp", mapOf(
                "deliveryDateFrom" to today,
                "deliveryDateTo" to today,
                "size" to "2000",
            ))
            val data = json.getJSONArray("data")
            val points = (0 until data.length()).mapNotNull { i ->
                val row = data.getJSONObject(i)
                val sp = row.optString("settlementPoint").trim().uppercase()
                if (sp !in matchKeys) return@mapNotNull null
                val price = row.optDouble("settlementPointPrice").takeIf { !it.isNaN() }
                    ?: return@mapNotNull null
                PricePoint(ts = ercotDateTime(row), lmp = price)
            }.sortedBy { it.ts }
            ZoneSeries(points)
        }

    /**
     * Day-ahead hourly prices for one settlement point.
     * Fetches today's posted hours and tomorrow's (when available after ~10:30 CT).
     * Hour slots: 0–23 for today, 24–47 for tomorrow.
     */
    override suspend fun fetchDayAheadCurve(matchKeys: Set<String>): List<HourPrice> =
        withContext(Dispatchers.IO) {
            val today = LocalDate.now(marketZone)
            val result = ArrayList<HourPrice>(48)
            for ((offset, date) in listOf(0 to today, 24 to today.plusDays(1))) {
                val dateStr = date.format(DATE_FMT)
                val json = runCatching {
                    getV2("np4-190-cd/dam-spp", mapOf(
                        "deliveryDateFrom" to dateStr,
                        "deliveryDateTo" to dateStr,
                        "size" to "1000",
                    ))
                }.getOrNull() ?: continue
                val data = json.optJSONArray("data") ?: continue
                for (i in 0 until data.length()) {
                    val row = data.getJSONObject(i)
                    val sp = row.optString("settlementPoint").trim().uppercase()
                    if (sp !in matchKeys) continue
                    val he = row.optInt("deliveryHour", -1).takeIf { it in 1..24 } ?: continue
                    val price = row.optDouble("settlementPointPrice").takeIf { !it.isNaN() } ?: continue
                    result += HourPrice(hour = he - 1 + offset, price = price)
                }
            }
            result.sortedBy { it.hour }
        }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private fun getV2(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val qs = if (params.isEmpty()) "" else
            "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val request = Request.Builder()
            .url("$V2_BASE$path$qs")
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ERCOT API ${response.code} for $path")
            }
            val body = response.body?.string()
                ?: throw IOException("Empty ERCOT API response for $path")
            return JSONObject(body)
        }
    }

    private fun getPublic(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body?.string() ?: throw IOException("Empty response from $url")
        }
    }

    companion object {
        private const val V2_BASE = "https://api.ercot.com/api/public-reports/"
        private val REF_ID_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")

        /**
         * Converts an ERCOT row's HE (Hour Ending) fields to [LocalDateTime].
         * HE1 starts at 00:00; interval 1–4 maps to :00/:15/:30/:45.
         */
        fun ercotDateTime(row: JSONObject): LocalDateTime {
            val raw = row.optString("deliveryDate").trim()
            val date = if (raw.isNotEmpty()) LocalDate.parse(raw, DATE_FMT) else LocalDate.now()
            val he = row.optInt("deliveryHour", 1).coerceIn(1, 24)
            val interval = row.optInt("deliveryInterval", 1).coerceIn(1, 4)
            return date.atTime(he - 1, (interval - 1) * 15)
        }
    }
}
