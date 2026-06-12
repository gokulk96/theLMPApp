package com.gokul.lmpapp.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gokul.lmpapp.data.NodeDirectory
import com.gokul.lmpapp.data.NyisoApiClient
import com.gokul.lmpapp.data.ZoneSeries
import com.gokul.lmpapp.ui.theme.isCarbonFree
import java.util.concurrent.TimeUnit

/**
 * Background refresh for the home-screen widget. Re-fetches data for the
 * zone the app last resolved (no background location use).
 *
 * Android caps periodic work at a 15-minute minimum and may batch executions,
 * so the widget refreshes roughly every 30 minutes — less often than the
 * 5-minute in-app cadence.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val saved = WidgetStateStore.load(context)
        return try {
            val api = NyisoApiClient()
            val directory = NodeDirectory.loadFromAssets(context, "nyiso_nodes.csv")
            val node = directory.findByNodeId(saved.zoneId)
            val keys = node?.matchKeys ?: setOf(saved.zoneId.uppercase())

            val snapshot = api.fetchLmpSnapshot()
            val price = keys.firstNotNullOfOrNull { snapshot.prices[it] }
            // Secondary data is best-effort; the price alone is enough to update
            val series = runCatching { api.fetchZoneSeries(keys) }
                .getOrDefault(ZoneSeries(emptyList()))
            val loads = runCatching { api.fetchZoneLoads() }.getOrNull()
            val mix = runCatching { api.fetchFuelMix() }.getOrNull()

            WidgetStateStore.save(
                context,
                saved.copy(
                    zoneName = node?.displayName ?: saved.zoneName,
                    lmp = price?.lmp ?: saved.lmp,
                    trend = series.trendVsHourAgo() ?: saved.trend,
                    refId = snapshot.refId,
                    curve = series.hourlyAverages().map { it.price }
                        .ifEmpty { saved.curve },
                    loadMw = loads?.totalMw ?: saved.loadMw,
                    cleanPct = mix?.let { m ->
                        if (m.totalMw > 0) {
                            m.categories.filter { isCarbonFree(it.name) }
                                .sumOf { it.mw } / m.totalMw * 100
                        } else null
                    } ?: saved.cleanPct,
                    mix = mix?.categories?.map {
                        WidgetMixEntry(it.name, mix.share(it) * 100)
                    } ?: saved.mix,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            LmpWidget().updateAll(context)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "lmp_widget_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** One-shot refresh so a newly placed widget fills in within seconds. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
