package com.gokul.lmpapp.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gokul.lmpapp.data.NodeDirectory
import com.gokul.lmpapp.data.NyisoApiClient
import java.util.concurrent.TimeUnit

/**
 * Background refresh for the home-screen widget. Re-fetches the LBMP for the
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
            val snapshot = NyisoApiClient().fetchLmpSnapshot()
            val directory = NodeDirectory.loadFromAssets(context, "nyiso_nodes.csv")
            val node = directory.findByNodeId(saved.zoneId)
            val price = node?.matchKeys?.firstNotNullOfOrNull { snapshot.prices[it] }
                ?: snapshot.prices[saved.zoneId.uppercase()]
            WidgetStateStore.save(
                context,
                saved.copy(
                    zoneName = node?.displayName ?: saved.zoneName,
                    lmp = price?.lmp ?: saved.lmp,
                    refId = snapshot.refId,
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

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
