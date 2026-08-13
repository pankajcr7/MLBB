package com.pankaj.mlbbdraft.data

import android.content.Context
import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.meta.MetaApplier
import com.pankaj.mlbbdraft.engine.meta.MetaApplyReport
import com.pankaj.mlbbdraft.engine.meta.MetaFetcher
import com.pankaj.mlbbdraft.engine.meta.MetaOverlay
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a sync attempt did, for the status line in the UI. */
sealed interface SyncOutcome {
    data class Updated(val report: MetaApplyReport) : SyncOutcome
    data object AlreadyCurrent : SyncOutcome
    data object NotStale : SyncOutcome

    /** No feed at the URL yet. Expected until the publishing job has run once. */
    data object NotPublished : SyncOutcome
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * Keeps live meta data on disk and applies it over the bundled dataset.
 *
 * Offline-first, and in that order deliberately: the app is fully usable with no
 * network, ever. A sync can only improve the tiers it already has — every failure path
 * falls back to the last good cache, and then to the bundled seed.
 */
class MetaRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "meta.json")
    private val fetcher = MetaFetcher()

    /**
     * Where to fetch from. Defaults to a file in your own repo, published by the
     * scheduled workflow in `.github/workflows/meta.yml` — so the app only ever depends
     * on a URL you control, not on a third-party API staying alive.
     */
    var feedUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_FEED_URL) ?: DEFAULT_FEED_URL
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    val lastSyncedAtMillis: Long get() = prefs.getLong(KEY_FETCHED_AT, 0L)

    val lastReportSummary: String? get() = prefs.getString(KEY_SUMMARY, null)

    fun cachedOverlay(): MetaOverlay? {
        if (!cacheFile.isFile) return null
        return runCatching { MetaApplier.parse(cacheFile.readText()) }.getOrNull()
    }

    /** Bundled dataset with the cached overlay applied, if there is a usable one. */
    fun applyCached(base: HeroDatabase): Pair<HeroDatabase, MetaApplyReport?> {
        val overlay = cachedOverlay() ?: return base to null
        val (db, report) = MetaApplier.apply(base, overlay)
        return if (report.isUsable) db to report else base to report
    }

    suspend fun sync(
        base: HeroDatabase,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
        force: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val age = nowMillis - lastSyncedAtMillis
        if (!force && cacheFile.isFile && age < maxAgeMillis) return@withContext SyncOutcome.NotStale

        val storedEtag = if (cacheFile.isFile) prefs.getString(KEY_ETAG, null) else null
        when (val result = fetcher.fetch(feedUrl, storedEtag)) {
            is MetaFetcher.Result.NotModified -> {
                prefs.edit().putLong(KEY_FETCHED_AT, nowMillis).apply()
                SyncOutcome.AlreadyCurrent
            }

            is MetaFetcher.Result.NotPublished -> SyncOutcome.NotPublished

            is MetaFetcher.Result.Failure -> SyncOutcome.Failed(result.reason)

            is MetaFetcher.Result.Body -> {
                val overlay = runCatching { MetaApplier.parse(result.json) }.getOrNull()
                    ?: return@withContext SyncOutcome.Failed("Feed is not in the expected format.")

                // Validate against the real dataset before letting it near the cache:
                // a feed that resolves almost nothing is worse than yesterday's data.
                val (_, report) = MetaApplier.apply(base, overlay)
                if (!report.isUsable) {
                    return@withContext SyncOutcome.Failed(
                        "Feed only matched ${report.heroesMatched} live heroes and " +
                            "${report.catalogueHeroesMatched} catalogue heroes / " +
                            "${report.catalogueItemsMatched} equipment — keeping the previous data.",
                    )
                }

                cacheFile.writeText(result.json)
                prefs.edit()
                    .putLong(KEY_FETCHED_AT, nowMillis)
                    .putString(KEY_ETAG, result.etag)
                    .putString(KEY_SUMMARY, summarise(report))
                    .apply()
                SyncOutcome.Updated(report)
            }
        }
    }

    fun clearCache() {
        cacheFile.delete()
        prefs.edit()
            .remove(KEY_FETCHED_AT)
            .remove(KEY_ETAG)
            .remove(KEY_SUMMARY)
            .apply()
    }

    private fun summarise(report: MetaApplyReport): String = buildString {
        append("${report.patch} · ${report.heroesMatched} heroes, ${report.tiersChanged} tiers updated")
        if (report.catalogueHeroesMatched > 0 || report.catalogueItemsMatched > 0) {
            append(" · catalogue: ${report.catalogueHeroesMatched} heroes, ")
            append("${report.catalogueItemsMatched} equipment")
        }
    }

    companion object {
        /**
         * Points at the repo this app is built from. Change it in Settings, or change
         * the branch/path if you publish the feed somewhere else.
         */
        const val DEFAULT_FEED_URL =
            "https://raw.githubusercontent.com/pankajcr7/MLBB/main/data/meta.json"

        /** Ranked stats move slowly; twice a day is plenty and costs one 304 response. */
        val DEFAULT_MAX_AGE_MILLIS = 12L * 60 * 60 * 1000

        private const val PREFS = "meta_sync"
        private const val KEY_URL = "feed_url"
        private const val KEY_ETAG = "etag"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_SUMMARY = "summary"
    }
}
