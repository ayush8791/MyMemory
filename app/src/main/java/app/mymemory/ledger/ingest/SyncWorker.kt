package app.mymemory.ledger.ingest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mymemory.MyMemoryApp
import java.util.concurrent.TimeUnit

/**
 * Ingest new SMS / e-mail, then run the model over everything pending.
 * The first run can have a few hundred messages, so it works in small batches against a time
 * budget and asks WorkManager to run again if anything is left.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = MyMemoryApp.container(applicationContext)
        c.ingest.syncSources()
        val deadline = System.currentTimeMillis() + 8 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            if (c.processor.processPending(10) == 0) break
        }
        return if (c.dao.pendingNow() > 0) Result.retry() else Result.success()
    }

    companion object {
        fun syncNow(ctx: Context, delaySeconds: Long = 0) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("periodic-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
