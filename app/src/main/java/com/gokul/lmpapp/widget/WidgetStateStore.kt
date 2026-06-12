package com.gokul.lmpapp.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One fuel category share for the widget's mix strip. */
data class WidgetMixEntry(val name: String, val pct: Double)

/** Snapshot of everything the widgets display. */
data class WidgetState(
    val market: String = "NYISO",              // "NYISO" or "ERCOT"
    val zoneId: String = "N.Y.C.",
    val zoneName: String = "New York City (Zone J)",
    val lmp: Double? = null,
    val trend: Double? = null,
    val refId: String = "",
    val curve: List<Double> = emptyList(),     // today's hourly averages
    val loadMw: Double? = null,
    val cleanPct: Double? = null,
    val mix: List<WidgetMixEntry> = emptyList(),
    val updatedAtMillis: Long = 0L,
)

/**
 * SharedPreferences bridge between the app and the widget worker, serialized
 * as one JSON blob. The widget never requests location itself: the app saves
 * the zone it last resolved, and the worker re-fetches data for that zone.
 */
object WidgetStateStore {

    private const val PREFS = "widget_state"
    private const val KEY = "state_json"

    fun save(context: Context, state: WidgetState) {
        val json = JSONObject().apply {
            put("market", state.market)
            put("zoneId", state.zoneId)
            put("zoneName", state.zoneName)
            state.lmp?.let { put("lmp", it) }
            state.trend?.let { put("trend", it) }
            put("refId", state.refId)
            put("curve", JSONArray(state.curve))
            state.loadMw?.let { put("loadMw", it) }
            state.cleanPct?.let { put("cleanPct", it) }
            put("mix", JSONArray(state.mix.map {
                JSONObject().put("name", it.name).put("pct", it.pct)
            }))
            put("updatedAt", state.updatedAtMillis)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString())
            .apply()
    }

    fun load(context: Context): WidgetState {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return WidgetState()
        return runCatching {
            val json = JSONObject(raw)
            WidgetState(
                market = json.optString("market", "NYISO"),
                zoneId = json.optString("zoneId", "N.Y.C."),
                zoneName = json.optString("zoneName", "New York City (Zone J)"),
                lmp = json.optDouble("lmp").takeIf { !it.isNaN() },
                trend = json.optDouble("trend").takeIf { !it.isNaN() },
                refId = json.optString("refId", ""),
                curve = json.optJSONArray("curve")?.let { arr ->
                    (0 until arr.length()).map { arr.getDouble(it) }
                }.orEmpty(),
                loadMw = json.optDouble("loadMw").takeIf { !it.isNaN() },
                cleanPct = json.optDouble("cleanPct").takeIf { !it.isNaN() },
                mix = json.optJSONArray("mix")?.let { arr ->
                    (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        WidgetMixEntry(o.getString("name"), o.getDouble("pct"))
                    }
                }.orEmpty(),
                updatedAtMillis = json.optLong("updatedAt", 0L),
            )
        }.getOrDefault(WidgetState())
    }
}
