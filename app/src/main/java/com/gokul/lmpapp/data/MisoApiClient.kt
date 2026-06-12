package com.gokul.lmpapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for MISO's public real-time Data Broker API. No API key required.
 *
 * The JSON key casing in these feeds has drifted over time, so parsing is
 * deliberately lenient: each field is looked up under every name it has been
 * known to use.
 */
class MisoApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun fetchLmpSnapshot(): LmpSnapshot = withContext(Dispatchers.IO) {
        parseLmpSnapshot(get(LMP_URL))
    }

    suspend fun fetchFuelMix(): FuelMix = withContext(Dispatchers.IO) {
        parseFuelMix(get(FUEL_MIX_URL))
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("MISO API returned HTTP ${response.code}")
            }
            return response.body?.string()
                ?: throw IOException("MISO API returned an empty body")
        }
    }

    companion object {
        private const val BASE =
            "https://api.misoenergy.org/MISORTWDDataBroker/DataBrokerServices.asmx"
        const val LMP_URL = "$BASE?messageType=getlmpconsolidatedtable&returnType=json"
        const val FUEL_MIX_URL = "$BASE?messageType=getfuelmix&returnType=json"

        fun parseLmpSnapshot(body: String): LmpSnapshot {
            val root = JSONObject(body)
            val lmpData = root.optJSONObject("LMPData") ?: root
            val refId = lmpData.optString("RefId", "")
            val fiveMin = lmpData.optJSONObject("FiveMinLMP")
                ?: throw IOException("Unexpected LMP payload: missing FiveMinLMP")
            val nodes = asArray(fiveMin.opt("PricingNode"))
                ?: throw IOException("Unexpected LMP payload: missing PricingNode")

            val prices = HashMap<String, NodePrice>(nodes.length())
            for (i in 0 until nodes.length()) {
                val obj = nodes.optJSONObject(i) ?: continue
                val name = firstString(obj, "name", "Name", "CPNode") ?: continue
                val lmp = firstDouble(obj, "LMP", "lmp") ?: continue
                prices[name.uppercase()] = NodePrice(
                    cpNodeName = name,
                    lmp = lmp,
                    congestion = firstDouble(obj, "congestion", "Congestion", "MCC", "mcc"),
                    loss = firstDouble(obj, "loss", "Loss", "MLC", "mlc"),
                )
            }
            if (prices.isEmpty()) throw IOException("LMP table contained no parsable rows")
            return LmpSnapshot(refId = refId, prices = prices)
        }

        fun parseFuelMix(body: String): FuelMix {
            val root = JSONObject(body)
            val refId = root.optString("RefId", "")
            val fuel = root.optJSONObject("Fuel") ?: root
            val types = asArray(fuel.opt("Type"))
                ?: throw IOException("Unexpected fuel mix payload: missing Fuel.Type")

            val categories = ArrayList<FuelCategory>(types.length())
            for (i in 0 until types.length()) {
                val obj = types.optJSONObject(i) ?: continue
                val name = firstString(obj, "CATEGORY", "Category", "category") ?: continue
                val mw = firstDouble(obj, "ACT", "act", "MW", "mw") ?: continue
                categories.add(FuelCategory(name = name, mw = mw))
            }
            if (categories.isEmpty()) throw IOException("Fuel mix contained no parsable rows")

            val reportedTotal = firstDouble(root, "TotalMW", "totalmw")
            val total = reportedTotal ?: categories.sumOf { it.mw }
            return FuelMix(
                refId = refId,
                totalMw = total,
                categories = categories.sortedByDescending { it.mw },
            )
        }

        /** The broker collapses single-element arrays to a bare object. */
        private fun asArray(value: Any?): JSONArray? = when (value) {
            is JSONArray -> value
            is JSONObject -> JSONArray().put(value)
            else -> null
        }

        private fun firstString(obj: JSONObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                obj.optString(key, "").ifBlank { null }
            }

        private fun firstDouble(obj: JSONObject, vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { key ->
                obj.optString(key, "").trim().replace(",", "").toDoubleOrNull()
            }
    }
}
