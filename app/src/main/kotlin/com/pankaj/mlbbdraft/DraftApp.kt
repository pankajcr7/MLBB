package com.pankaj.mlbbdraft

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.pankaj.mlbbdraft.data.MetaRefreshWorker
import com.pankaj.mlbbdraft.data.MetaRepository
import java.util.concurrent.TimeUnit
import com.pankaj.mlbbdraft.data.ProfileStore
import com.pankaj.mlbbdraft.data.SuggestionSpeaker
import com.pankaj.mlbbdraft.engine.data.DatasetLoader

/**
 * Owns the one [DraftSession] that both the main activity and the floating overlay
 * service read from. The dataset is parsed once here rather than per-surface.
 */
class DraftApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<MetaRefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            META_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    val session: DraftSession by lazy {
        DraftSession(
            baseDb = DatasetLoader.fromResources(),
            profileStore = ProfileStore(this),
            metaRepository = MetaRepository(this),
        ).also { it.attachSpeaker(SuggestionSpeaker(this)) }
    }

    private companion object {
        const val META_REFRESH_WORK_NAME = "mlbb-verified-data-refresh"
    }
}

/** Convenience for reaching the shared session from an Activity or Service. */
val android.content.Context.draftSession: DraftSession
    get() = (applicationContext as DraftApp).session
