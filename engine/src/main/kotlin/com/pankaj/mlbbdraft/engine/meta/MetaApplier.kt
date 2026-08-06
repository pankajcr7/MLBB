package com.pankaj.mlbbdraft.engine.meta

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Lane
import kotlinx.serialization.json.Json

/**
 * Applies a [MetaOverlay] to a bundled [HeroDatabase], producing a new database with
 * live meta tiers.
 *
 * Two decisions worth knowing about:
 *
 * 1. **Derived tiers are blended with the seed, not substituted for it.** Public
 *    win-rate feeds are noisy and rank-dependent — a 51% win rate on a hero nobody
 *    picks says very little. Blending keeps hand-authored knowledge in the loop and
 *    stops one bad scrape from rewriting the whole tier list.
 * 2. **Nothing is ever deleted.** The overlay can move a tier and add a matchup edge.
 *    It cannot remove a hero or overwrite an authored counter note.
 */
object MetaApplier {
    /** How much of the final tier comes from live data vs the bundled seed. */
    private const val LIVE_WEIGHT = 0.65

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): MetaOverlay = json.decodeFromString<MetaOverlay>(body)

    fun encode(overlay: MetaOverlay): String = json.encodeToString(overlay)

    /**
     * Returns the updated database and a report. If the overlay resolves too few heroes
     * ([MetaApplyReport.MIN_MATCHED]) the original database is returned unchanged, so a
     * malformed feed is a no-op rather than a downgrade.
     */
    fun apply(db: HeroDatabase, overlay: MetaOverlay): Pair<HeroDatabase, MetaApplyReport> {
        val resolver = HeroNameResolver(db.heroes.map { it.id to it.name })
        val warnings = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        // Resolve the feed onto our ids first, so we can bail out before touching anything.
        val resolved = LinkedHashMap<String, MutableList<MetaHero>>()
        overlay.heroes.forEach { raw ->
            val id = resolver.resolve(raw.name)
            if (id == null) {
                unknown += raw.name
            } else {
                resolved.getOrPut(id) { mutableListOf() } += raw.normalised()
            }
        }

        if (resolved.size < MetaApplyReport.MIN_MATCHED) {
            return db to MetaApplyReport(
                patch = overlay.patch,
                updatedAt = overlay.updatedAt,
                source = overlay.source,
                heroesMatched = resolved.size,
                tiersChanged = 0,
                countersAdded = 0,
                unknownNames = unknown.take(20),
                warnings = warnings + "Only ${resolved.size} of ${overlay.heroes.size} " +
                    "heroes resolved — keeping the bundled tiers instead.",
            )
        }

        var tiersChanged = 0
        val updatedHeroes = db.heroes.map { hero ->
            val entries = resolved[hero.id] ?: return@map hero
            val newTiers = tiersFor(hero, entries, warnings)
            if (newTiers == hero.tier) hero else hero.copy(tier = newTiers).also { tiersChanged++ }
        }

        // Only add edges for pairs we have not authored ourselves, in either direction.
        val existingPairs = db.counters.map { it.hero to it.vs }.toSet()
        val extraCounters = overlay.counters.mapNotNull { edge ->
            val hero = resolver.resolve(edge.hero) ?: return@mapNotNull null
            val vs = resolver.resolve(edge.vs) ?: return@mapNotNull null
            if (hero == vs || (hero to vs) in existingPairs) null else edge.copy(hero = hero, vs = vs)
        }

        val merged = HeroDatabase(
            patch = "${db.patch} + ${overlay.patch}",
            heroes = updatedHeroes,
            counters = db.counters + extraCounters,
            synergies = db.synergies,
            items = db.items,
        )

        return merged to MetaApplyReport(
            patch = overlay.patch,
            updatedAt = overlay.updatedAt,
            source = overlay.source,
            heroesMatched = resolved.size,
            tiersChanged = tiersChanged,
            countersAdded = extraCounters.size,
            unknownNames = unknown.take(20),
            warnings = warnings,
        )
    }

    /**
     * Builds the new per-lane tier map for one hero.
     *
     * An entry with a lane updates only that lane. An entry without one updates every
     * lane the hero plays, because a global win rate says nothing about which lane it
     * came from.
     */
    private fun tiersFor(
        hero: Hero,
        entries: List<MetaHero>,
        warnings: MutableList<String>,
    ): Map<Lane, Double> {
        val updated = hero.tier.toMutableMap()

        entries.forEach { entry ->
            val derived = deriveTier(entry)
            if (derived == null) {
                warnings += "No usable numbers for ${hero.name} — left at the bundled tier."
                return@forEach
            }
            val lanes = entry.lane?.let { lane ->
                if (lane in hero.lanes) {
                    listOf(lane)
                } else {
                    warnings += "Feed reports ${hero.name} in $lane, which is not a lane we list."
                    emptyList()
                }
            } ?: hero.lanes.toList()

            lanes.forEach { lane ->
                val seed = hero.tier[lane] ?: DEFAULT_SEED_TIER
                updated[lane] = blend(live = derived, seed = seed)
            }
        }
        return updated
    }

    private fun blend(live: Double, seed: Double): Double =
        ((LIVE_WEIGHT * live) + ((1 - LIVE_WEIGHT) * seed)).coerceIn(0.0, 10.0)

    /**
     * Turns win / pick / ban rate into a 0..10 tier.
     *
     * Win rate carries the signal; ban rate is the strongest available proxy for "the
     * playerbase thinks this hero is a problem", which is exactly what a draft tool
     * cares about. Pick rate is a weak popularity signal and is capped accordingly.
     *
     * Real ranked win rates cluster in roughly 44–57%, so the mapping is centred on 50%
     * and scaled to spread that band across the usable tier range instead of leaving
     * every hero at 5.
     */
    fun deriveTier(entry: MetaHero): Double? {
        entry.tier?.let { return it.coerceIn(0.0, 10.0) }
        val win = entry.winRate ?: return null

        val fromWin = 5.5 + (win - 0.50) * 50.0
        val fromBan = (entry.banRate ?: 0.0) * 3.0
        val fromPick = ((entry.pickRate ?: 0.0) * 15.0).coerceAtMost(0.5)
        return (fromWin + fromBan + fromPick).coerceIn(0.0, 10.0)
    }

    private const val DEFAULT_SEED_TIER = 5.0
}
