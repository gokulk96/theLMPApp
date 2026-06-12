package com.gokul.lmpapp.widget

import android.content.Context

/** Snapshot of what the widget displays. */
data class WidgetState(
    val zoneId: String,
    val zoneName: String,
    val lmp: Double?,
    val refId: String,
    val updatedAtMillis: Long,
)

/**
 * Tiny SharedPreferences store shared between the app and the widget worker.
 *
 * The widget never requests location itself: the app saves the zone it last
 * resolved here, and the background worker only re-fetches the price for
 * that zone. Defaults to N.Y.C. (Zone J) before the app has run.
 */
object WidgetStateStore {

    private const val PREFS = "widget_state"
    private const val KEY_ZONE_ID = "zone_id"
    private const val KEY_ZONE_NAME = "zone_name"
    private const val KEY_LMP = "lmp"
    private const val KEY_REF_ID = "ref_id"
    private const val KEY_UPDATED_AT = "updated_at"

    fun save(context: Context, state: WidgetState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ZONE_ID, state.zoneId)
            .putString(KEY_ZONE_NAME, state.zoneName)
            .putString(KEY_LMP, state.lmp?.toString() ?: "")
            .putString(KEY_REF_ID, state.refId)
            .putLong(KEY_UPDATED_AT, state.updatedAtMillis)
            .apply()
    }

    fun load(context: Context): WidgetState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetState(
            zoneId = prefs.getString(KEY_ZONE_ID, null) ?: "N.Y.C.",
            zoneName = prefs.getString(KEY_ZONE_NAME, null) ?: "New York City (Zone J)",
            lmp = prefs.getString(KEY_LMP, "")?.toDoubleOrNull(),
            refId = prefs.getString(KEY_REF_ID, "") ?: "",
            updatedAtMillis = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }
}
