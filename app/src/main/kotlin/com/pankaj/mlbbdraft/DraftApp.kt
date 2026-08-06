package com.pankaj.mlbbdraft

import android.app.Application
import com.pankaj.mlbbdraft.data.MetaRepository
import com.pankaj.mlbbdraft.data.ProfileStore
import com.pankaj.mlbbdraft.engine.data.DatasetLoader

/**
 * Owns the one [DraftSession] that both the main activity and the floating overlay
 * service read from. The dataset is parsed once here rather than per-surface.
 */
class DraftApp : Application() {
    val session: DraftSession by lazy {
        DraftSession(
            baseDb = DatasetLoader.fromResources(),
            profileStore = ProfileStore(this),
            metaRepository = MetaRepository(this),
        )
    }
}

/** Convenience for reaching the shared session from an Activity or Service. */
val android.content.Context.draftSession: DraftSession
    get() = (applicationContext as DraftApp).session
