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
 * Client for NYISO's public market data CSVs on mis.nyiso.com (the same feeds
 * the NYISOToolkit Python package wraps). No API key required.
 *
 * Daily files accumulate 5-minute intervals through the day; the latest
 * interval is the current value. Just after midnight ET today's file may not
 * exist yet, so the previous day is used as a fallback.
 *
 * Sign convention: NYISO publishes LBMP = energy + losses - congestion, so the
 * congestion component is negated here to match the app-wide additive
 * convention (lmp = energy + congestion + loss) shared with MISO.
 */
class NyisoApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val marketZone: ZoneId = ZoneId.of("America/New_York"),
) : MarketDataSource {

    override suspend fun fetchLmpSnapshot(): LmpSnapshot = withContext(Dispatchers.IO) {
        parseLmpCsv(getDailyCsv("realtime", "realtime_zone.csv"))
    }

    override suspend fun fetchFuelMix(): FuelMix = withContext(Dispatchers.IO) {
        parseFuelMixCsv(getDailyCsv("rtfuelmix", "rtfuelmix.csv"))
    }

    private fun getDailyCsv(dir: String, fileSuffix: String): String {
        val today = LocalDate.now(marketZone)
        var lastError: IOException? = null
        for (date in listOf(today, today.minusDays(1))) {
            val stamp = date.format(DateTimeFormatter.BASIC_ISO_DATE)
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://mis.nyiso.com/public/csv/$dir/$stamp$fileSuffix"
                try {
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return response.body?.string()
                                ?: throw IOException("NYISO returned an empty body")
                        }
                        lastError = IOException("NYISO returned HTTP ${response.code}")
                    }
                } catch (e: IOException) {
                    lastError = e
                }
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
            val header = rows.firstOrNull()
                ?: throw IOException("NYISO LBMP CSV is empty")
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
                val lbmp = fields.getOrNull(lbmpCol)?.toDoubleOrNull()
                    ?: return@mapNotNull null
                val loss = fields.getOrNull(lossCol)?.toDoubleOrNull()
                val mcc = fields.getOrNull(congCol)?.toDoubleOrNull()
                Row(
                    ts = ts,
                    price = NodePrice(
                        cpNodeName = name,
                        lmp = lbmp,
                        // negate: NYISO MCC enters LBMP with a minus sign
                        congestion = mcc?.let { -it },
                        loss = loss,
                    ),
                )
            }
            if (parsed.isEmpty()) throw IOException("NYISO LBMP CSV contained no parsable rows")

            val latest = parsed.maxOf { it.ts }
            val prices = parsed.filter { it.ts == latest }
                .associate { it.price.cpNodeName.uppercase() to it.price }
            return LmpSnapshot(
                refId = "${latest.format(REF_ID_FORMAT)} ET",
                prices = prices,
            )
        }

        fun parseFuelMixCsv(csv: String): FuelMix {
            val rows = parseRows(csv)
            val header = rows.firstOrNull()
                ?: throw IOException("NYISO fuel mix CSV is empty")
            val tsCol = columnIndex(header, "Time Stamp")
            val categoryCol = columnIndex(header, "Fuel Category")
            val mwCol = columnIndex(header, "Gen MW")

            data class Row(val ts: LocalDateTime, val category: FuelCategory)

            val parsed = rows.drop(1).mapNotNull { fields ->
                val ts = parseTimestamp(fields.getOrNull(tsCol)) ?: return@mapNotNull null
                val name = fields.getOrNull(categoryCol)?.trim().orEmpty()
                    .ifEmpty { return@mapNotNull null }
                val mw = fields.getOrNull(mwCol)?.toDoubleOrNull() ?: return@mapNotNull null
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
            csv.lineSequence()
                .filter { it.isNotBlank() }
                .map(::splitCsvLine)
                .toList()

        private fun columnIndex(header: List<String>, keyword: String): Int =
            header.indexOfFirst { it.contains(keyword, ignoreCase = true) }
                .also { if (it < 0) throw IOException("NYISO CSV is missing a '$keyword' column") }

        private fun parseTimestamp(raw: String?): LocalDateTime? {
            val value = raw?.trim().orEmpty().ifEmpty { return null }
            for (format in TIMESTAMP_FORMATS) {
                runCatching { return LocalDateTime.parse(value, format) }
            }
            return null
        }

        /** Minimal RFC-4180 field splitter (handles quoted fields with commas). */
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
