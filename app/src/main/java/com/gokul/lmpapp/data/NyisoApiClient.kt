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
import java.util.zip.ZipInputStream

/**
 * Client for NYISO's public market data on mis.nyiso.com (same feeds the
 * NYISOToolkit Python package wraps). No API key required.
 *
 * NYISO publishes daily ZIP archives that accumulate 5-minute rows through
 * the day. The latest timestamp in the file is the current value.
 * Just after midnight ET today's file may not exist yet, so yesterday is
 * tried as a fallback.
 *
 * URL pattern (from NYISOToolkit dataset_url_map.yml):
 *   LMP:      mis.nyiso.com/public/csv/realtime/{YYYYMMDD}realtime_zone_csv.zip
 *   Fuel mix: mis.nyiso.com/public/csv/rtfuelmix/{YYYYMMDD}rtfuelmix_csv.zip
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
        parseLmpCsv(getDailyZipCsv("realtime", "realtime_zone_csv.zip"))
    }

    override suspend fun fetchFuelMix(): FuelMix = withContext(Dispatchers.IO) {
        parseFuelMixCsv(getDailyZipCsv("rtfuelmix", "rtfuelmix_csv.zip"))
    }

    private fun getDailyZipCsv(dir: String, zipSuffix: String): String {
        val today = LocalDate.now(marketZone)
        var lastError: IOException? = null
        for (date in listOf(today, today.minusDays(1))) {
            val stamp = date.format(DateTimeFormatter.BASIC_ISO_DATE)
            // Try HTTPS first; fall back to HTTP (mis.nyiso.com has served both)
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://mis.nyiso.com/public/csv/$dir/$stamp$zipSuffix"
                try {
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                                ?: throw IOException("NYISO returned empty body for $url")
                            return extractFirstCsvFromZip(bytes, url)
                        }
                        lastError = IOException("NYISO HTTP ${response.code} for $url")
                    }
                } catch (e: IOException) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IOException("NYISO data unavailable for $dir/$zipSuffix")
    }

    private fun extractFirstCsvFromZip(zipBytes: ByteArray, sourceUrl: String): String {
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    return zis.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        throw IOException("No CSV found inside NYISO ZIP from $sourceUrl")
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
            val tsCol   = columnIndex(header, "Time Stamp")
            val nameCol = columnIndex(header, "Name")
            val lbmpCol = columnIndex(header, "LBMP")
            val lossCol = columnIndex(header, "Losses")
            val congCol = columnIndex(header, "Congestion")

            data class Row(val ts: LocalDateTime, val price: NodePrice)

            val parsed = rows.drop(1).mapNotNull { fields ->
                val ts   = parseTimestamp(fields.getOrNull(tsCol)) ?: return@mapNotNull null
                val name = fields.getOrNull(nameCol)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                val lbmp = fields.getOrNull(lbmpCol)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                val loss = fields.getOrNull(lossCol)?.trim()?.toDoubleOrNull()
                val mcc  = fields.getOrNull(congCol)?.trim()?.toDoubleOrNull()
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
            val tsCol       = columnIndex(header, "Time Stamp")
            val categoryCol = columnIndex(header, "Fuel Category")
            val mwCol       = columnIndex(header, "Gen MW")

            data class Row(val ts: LocalDateTime, val category: FuelCategory)

            val parsed = rows.drop(1).mapNotNull { fields ->
                val ts   = parseTimestamp(fields.getOrNull(tsCol)) ?: return@mapNotNull null
                val name = fields.getOrNull(categoryCol)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                val mw   = fields.getOrNull(mwCol)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
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

        private fun parseRows(csv: String): List<List<String>> =
            csv.lineSequence().filter { it.isNotBlank() }.map(::splitCsvLine).toList()

        private fun columnIndex(header: List<String>, keyword: String): Int =
            header.indexOfFirst { it.contains(keyword, ignoreCase = true) }
                .also { if (it < 0) throw IOException("NYISO CSV missing '$keyword' column; header: $header") }

        private fun parseTimestamp(raw: String?): LocalDateTime? {
            val value = raw?.trim().orEmpty().ifEmpty { return null }
            for (fmt in TIMESTAMP_FORMATS) runCatching { return LocalDateTime.parse(value, fmt) }
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
                    c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> { fields.add(current.toString()); current.setLength(0) }
                    else -> current.append(c)
                }
                i++
            }
            fields.add(current.toString())
            return fields
        }
    }
}
