package be.maikel.xplorer.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import be.maikel.xplorer.core.Analyzer
import be.maikel.xplorer.core.Pipeline
import be.maikel.xplorer.data.Repo
import java.util.concurrent.TimeUnit

/**
 * Synchronisation en arrière-plan, toutes les heures environ (Android décide du moment exact
 * et peut l'espacer en mode économie d'énergie : ce n'est pas une surveillance permanente).
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val repo = Repo.get(applicationContext)
        val report = repo.sync()
        val a = Analyzer.run(repo.snapshot(), repo.h4)
        Pipeline.afterSync(applicationContext, repo, a, report.newOps, notify = true)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("xp-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
