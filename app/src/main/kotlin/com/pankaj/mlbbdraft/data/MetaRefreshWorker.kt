package com.pankaj.mlbbdraft.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pankaj.mlbbdraft.engine.data.DatasetLoader

/**
 * Periodically refreshes the app-controlled static feed. The worker has no authority to change
 * the live session directly: [MetaRepository] validates before caching and the next app/session
 * read applies only a last-known-good cache over the bundled dataset.
 */
class MetaRefreshWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        return when (MetaRepository(applicationContext).sync(DatasetLoader.fromResources(), force = false)) {
            is SyncOutcome.Updated,
            SyncOutcome.AlreadyCurrent,
            SyncOutcome.NotStale,
            SyncOutcome.NotPublished,
            -> Result.success()

            is SyncOutcome.Failed -> Result.retry()
        }
    }
}
