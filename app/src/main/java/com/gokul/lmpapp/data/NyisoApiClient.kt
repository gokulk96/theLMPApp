package com.gokul.lmpapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Client for NYISO's public market data on mis.nyiso.com. No API key required.
 *
 * Feeds used:
 *   LBMP:     /public/realtime/realtime_zone_lbmp.csv   (live snapshot, no date)
 *             with /public/csv/realtime/{YYYYMMDD}realtime_zone.csv as fallback
 *   Fuel mix: /public/csv/rtfuelmix/{YYYYMMDD}rtfuelmix.csv
 *   Load:     /public/csv/pal/{YYYYMMDD}pal.csv          (real-time actual load)
 *
 * Dated files accumulate 5-minute rows through the day; the latest timestamp
 * is the current value. Just after midnight ET today's file may not exist
 * yet, so yesterday is tried as a fallback.
 *
 * Sign convention: NYISO publishes LBMP = energy + losses − congestion, so
 * the congestion component is negated here so that lmp = energy + congestion
 * + loss holds for every NodePrice in the app.
 */
class NyisoApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val marketZone: ZoneId = ZoneId.of("America/New_York"),
) : MarketDataSource {

    override suspend fun fetchLmpSnapshot(): LmpSnapshot = withContext(Dispatchers.IO) {
        val urls = latestUrls("realtime/realtime_zone_lbmp.csv") +
            dailyUrls("realtime", "realtime_zone.csv")
        parseLmpCsv(getFirstSuccessful(urls))
    }

    override suspend fun fetchFuelMix(): FuelMix = withContext(Dispatchers.IO) {
        parseFuelMixCsv(getFirstSuccessful(dailyUrls("rtfuelmix", "rtfuelmix.csv")))
    }

    override suspend fun fetchZoneLoads(): LoadSnapshot = withContext(Dispatchers.IO) {
        parseLoadCsv(getFirstSuccessful(dailyUrls("pal", "pal.csv")))
    }

    /** Live snapshot files that always reflect the current interval. */
    private fun latestUrls(path: String): List<String> =
        listOf("https://mis.nyiso.com/public/$path", "http://mis.nyiso.com/public/$path")

    /** Date-stamped daily files: today first, then yesterday; HTTPS then HTTP. */
    private fun dailyUrls(dir: String, fileSuffix: String): List<String> {
        val today = LocalDate.now(marketZone)
        return listOf(today, today.minusDays(1)).flatMap { date ->
            val stamp = date.format(DateTimeFormatter.BASIC_ISO_DATE)
            listOf(
                "https://mis.nyiso.com/public/csv/$dir/$stamp$fileSuffix",
                "http://mis.nyiso.com/public/csv/$dir/$stamp$fileSuffix",
            )
        }
    }

    private fun getFirstSuccessful(urls: List<String>): String {
        var lastError: IOException? = null
        for (url in urls) {
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            return body.removePrefix("﻿")
                        }
                        lastError = IOException("Empty body from $url")
                    } else {
                        lastError = IOException("HTTP ${response.code} from $url")
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("NYISO data unavailable")
    }

    companion object {
        private val TIMESTAMP_FORMATS = listOf(
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"),
        )
        private val REF_ID_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm")

        fun parseLmpCsv(csv: String): LmpSnapshot {
            val rows = parseRows(csv)
            val header = rows.firstOrNull() ?: throw IOException("NYISO LBMP CSV is empty")
            val tsCol = columnIndex(header, "Time Stamp")
            val nameCol = columnIndex(header, "Name")
            val lbmpCol = columnIndex(header, "LBMP")
            val lossCol = columnIndex(header, "Losses")
            val congCol = columnIndex(header, "Congestion")

            data class Row(val ts: LocalDateTime, val price: NodePrice)

            val parsed = rows.drop(1).mapNotNull { fields ->
                val ts = parseTimestamp(fields.getOrNull(tsCol)) ?: return@mapNotNull null
                val name = fields.getOrNull(nameCol)?.trim().orEmpty()
                    .ifEmpty { return@mapNotNull null }
                val lbmp = fields.getOrNull(lbmpCol)?.trim()?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val loss = fields.getOrNull(lossCol)?.trim()?.toDoubleOrNull()
                val mcc = fields.getOrNull(congCol)?.trim()?.toDoubleOrNull()
                Row(
                    ts = ts,
                    price = NodePrice(
                        cpNodeName = name,
                        lmp = lbmp,
                        congestion = mcc?.let { -it }, // NYISO MCC sign is negated in LBMP
                        loss = loss,
                    ),
                )
            }
            if (parsed.isEmpty()) throw IOException("NYISO LBMP CSV contained no parsable rows")

            val latest = parsed.maxOf { it.ts }
            val prices = parsed
                .filter { it.ts == latest }
                .associate { it.price.cpNodeName.uppercase() to it.price }
            return LmpSnapshot(refId = "${latest.format(REF_ID_FORMAT)} ET", prices = prices)
        }

        fun parseFuelMixCsv(csv: String): FuelMix {
            val rows = parseRows(csv)
            val header = rows.firstOrNull() ?: throw IOException("NYISO fuel mix CSV is empty")
            val tsCol = columnIndex(header, "Time Stamp")
            val categoryCol = columnIndex(header, "Fuel Category")
            val mwCol = columnIndex(header, "Gen MW")

            data class Row(val ts: LocalDateTime, val category: FuelCategory)

            val parsed = rows.drop(1).mapNotNull { fields ->
                val ts = parseTimestamp(fields.getOrNull(tsCol)) ?: return@mapNotNull null
                val name = fields.getOrNull(categoryCol)?.trim().orEmpty()
                    .ifEmpty { return@mapNotNull null }
                val mw = fields.getOrNull(mwCol)?.trim()?.toDoubleOrNull()
                    ?: return@mapNotNull null
                Row(ts, FuelCategory(name = name, mw = mw))
            }
            if (parsed.isEmpty()) throw IOException("NYISO fuel mix CSV contained no parsable rows")

            val latest = parsed.maxOf { it.ts }
            val categories = parsed.filter { it.ts == latest }.map { it.category }
            return FuelMix(
                refId = "${latest.format(REF_ID_FORMAT)} ET",
                totalMw = categories.sumOf { it.mw },
                categories = categories.sortedByDescending { it.mw },
            )
        }

        fun parseLoadCsv(csv: String): LoadSnapshot {
            val rows = parseRows(csv)
            val header = rows.firstOrNull() ?: throw IOException("NYISO load CSV is empty")
            val tsCol = columnIndex(header, "Time Stamp")
            val nameCol = columnIndex(header, "Name")
            val loadCol = columnIndex(header, "Load")

            data class Row(val ts: LocalDateTime, val load: ZoneLoad)

            val parsed = rows.drop(1).mapNotNull { fields ->
                val ts = parseTimestamp(fields.getOrNull(tsCol)) ?: return@mapNotNull null
                val name = fields.getOrNull(nameCol)?.trim().orEmpty()
                    .ifEmpty { return@mapNotNull null }
                // Load can be blank for the most recent interval while NYISO
                // is still publishing it
                val mw = fields.getOrNull(loadCol)?.trim()?.toDoubleOrNull()
                    ?: return@mapNotNull null
                Row(ts, ZoneLoad(zoneName = name, mw = mw))
            }
            if (parsed.isEmpty()) throw IOException("NYISO load CSV contained no parsable rows")

            val latest = parsed.maxOf { it.ts }
            val loads = parsed
                .filter { it.ts == latest }
                .associate { it.load.zoneName.uppercase() to it.load }
            return LoadSnapshot(refId = "${latest.format(REF_ID_FORMAT)} ET", loads = loads)
        }

        private fun parseRows(csv: String): List<List<String>> =
            csv.lineSequence().filter { it.isNotBlank() }.map(::splitCsvLine).toList()

        private fun columnIndex(header: List<String>, keyword: String): Int =
            header.indexOfFirst { it.contains(keyword, ignoreCase = true) }
                .also {
                    if (it < 0) throw IOException("NYISO CSV missing '$keyword' column; header: $header")
                }

        private fun parseTimestamp(raw: String?): LocalDateTime? {
            val value = raw?.trim().orEmpty().ifEmpty { return null }
            for (fmt in TIMESTAMP_FORMATS) {
                runCatching { return LocalDateTime.parse(value, fmt) }
            }
            return null
        }

        internal fun splitCsvLine(line: String): List<String> {
            val fields = ArrayList<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                        current.append('"'); i++
                    }
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> {
                        fields.add(current.toString()); current.setLength(0)
                    }
                    else -> current.append(c)
                }
                i++
            }
            fields.add(current.toString())
            return fields
        }
    }
}
