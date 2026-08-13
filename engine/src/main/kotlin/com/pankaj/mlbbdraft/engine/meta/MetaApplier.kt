package com.pankaj.mlbbdraft.engine.meta

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.model.Lane
import kotlinx.serialization.json.Json

/**
 * Applies a [MetaOverlay] to a bundled [HeroDatabase]. Live tiers and a controlled catalogue
 * supplement are intentionally narrow overlays: neither can delete authored data, create an
 * unreviewed playable hero, alter recommendation tags, or permit a battle spell as equipment.
 */
object MetaApplier {
    /** How much of the final tier comes from live data vs the bundled seed. */
    private const val LIVE_WEIGHT = 0.65

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): MetaOverlay = json.decodeFromString<MetaOverlay>(body)

    fun encode(overlay: MetaOverlay): String = json.encodeToString(overlay)

    /**
     * Returns the updated database and an audit report. A feed is a no-op when it does not have
     * enough valid tier records or a complete enough catalogue snapshot to clear the safety floor.
     */
    fun apply(db: HeroDatabase, overlay: MetaOverlay): Pair<HeroDatabase, MetaApplyReport> {
        val warnings = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val catalogue = applyCatalogue(db, overlay.catalogue, warnings, unknown)
        val workingDb = if (catalogue.isUsable) catalogue.database else db

        val resolver = heroResolver(workingDb)
        val resolved = LinkedHashMap<String, MutableList<MetaHero>>()
        overlay.heroes.forEach { raw ->
            val id = resolver.resolve(raw.name)
            if (id == null) {
                unknown += raw.name
            } else {
                resolved.getOrPut(id) { mutableListOf() } += raw.normalised()
            }
        }

        val liveUsable = resolved.size >= MetaApplyReport.MIN_MATCHED
        if (!liveUsable && !catalogue.isUsable) {
            warnings += "Only ${resolved.size} of ${overlay.heroes.size} heroes resolved and catalogue " +
                "matched ${catalogue.heroesMatched} heroes / ${catalogue.itemsMatched} equipment — keeping bundled data."
            return db to report(
                overlay = overlay,
                heroesMatched = resolved.size,
                tiersChanged = 0,
                countersAdded = 0,
                catalogue = catalogue,
                unknown = unknown,
                warnings = warnings,
            )
        }

        var tiersChanged = 0
        val updatedHeroes = if (liveUsable) {
            workingDb.heroes.map { hero ->
                val entries = resolved[hero.id] ?: return@map hero
                val newTiers = tiersFor(hero, entries, warnings)
                if (newTiers == hero.tier) hero else hero.copy(tier = newTiers).also { tiersChanged++ }
            }
        } else {
            workingDb.heroes
        }

        // Only add edges for pairs we have not authored ourselves, in either direction.
        val existingPairs = workingDb.counters.map { it.hero to it.vs }.toSet()
        val extraCounters = if (liveUsable) {
            overlay.counters.mapNotNull { edge ->
                val hero = resolver.resolve(edge.hero) ?: return@mapNotNull null
                val vs = resolver.resolve(edge.vs) ?: return@mapNotNull null
                if (hero == vs || (hero to vs) in existingPairs) null else edge.copy(hero = hero, vs = vs)
            }
        } else {
            emptyList()
        }

        val merged = HeroDatabase(
            patch = if (liveUsable) "${workingDb.patch} + ${overlay.patch}" else workingDb.patch,
            heroes = updatedHeroes,
            counters = workingDb.counters + extraCounters,
            synergies = workingDb.synergies,
            items = workingDb.items,
            coreBuilds = workingDb.coreBuilds,
        )
        val integrity = merged.validate()
        if (integrity.isNotEmpty()) {
            warnings += "Merged feed failed integrity validation: ${integrity.first()}. Keeping bundled data."
            return db to report(
                overlay = overlay,
                heroesMatched = resolved.size,
                tiersChanged = 0,
                countersAdded = 0,
                catalogue = CatalogueResult.empty(db),
                unknown = unknown,
                warnings = warnings,
            )
        }

        return merged to report(
            overlay = overlay,
            heroesMatched = resolved.size,
            tiersChanged = tiersChanged,
            countersAdded = extraCounters.size,
            catalogue = catalogue,
            unknown = unknown,
            warnings = warnings,
        )
    }

    /** Derives a 0..10 tier from published rate data, or null when there is no usable win rate. */
    fun deriveTier(entry: MetaHero): Double? {
        entry.tier?.let { return it.coerceIn(0.0, 10.0) }
        val win = entry.winRate ?: return null

        val fromWin = 5.5 + (win - 0.50) * 50.0
        val fromBan = (entry.banRate ?: 0.0) * 3.0
        val fromPick = ((entry.pickRate ?: 0.0) * 15.0).coerceAtMost(0.5)
        return (fromWin + fromBan + fromPick).coerceIn(0.0, 10.0)
    }

    /**
     * Merges only the source fields that cannot change recommendation semantics. Each source name
     * becomes an OCR alias for an existing hero/item; a known non-spell item's displayed price may
     * be refreshed. Source roles, artwork, categories, counter tags, core builds, and unknown ids
     * are intentionally ignored.
     */
    private fun applyCatalogue(
        db: HeroDatabase,
        overlay: CatalogueOverlay?,
        warnings: MutableList<String>,
        unknown: MutableList<String>,
    ): CatalogueResult {
        if (overlay == null) return CatalogueResult.empty(db)
        if (overlay.upstreamCommit.length !in 7..128 || overlay.upstreamCommit.any { !it.isLetterOrDigit() }) {
            warnings += "Catalogue has an invalid upstream commit marker."
            return CatalogueResult.empty(db)
        }

        val heroResolver = heroResolver(db)
        val heroAliases = mutableMapOf<String, MutableSet<String>>()
        var heroesMatched = 0
        overlay.heroes.forEach { source ->
            if (!isValidSourceRecord(source.sourceId, source.name)) {
                warnings += "Ignored malformed catalogue hero record."
                return@forEach
            }
            val id = heroResolver.resolve(source.name)
            if (id == null) {
                unknown += "hero:${source.name}"
            } else {
                heroesMatched++
                val hero = db.hero(id) ?: return@forEach
                if (normalise(source.name) != normalise(hero.name)) {
                    heroAliases.getOrPut(id) { linkedSetOf() } += source.name.trim()
                }
            }
        }

        val itemIndex = itemIndex(db.items)
        val itemAliases = mutableMapOf<String, MutableSet<String>>()
        val itemPrices = mutableMapOf<String, Int>()
        var itemsMatched = 0
        overlay.equipment.forEach { source ->
            if (!isValidSourceRecord(source.sourceId, source.name) || source.priceGold !in 0..20_000) {
                warnings += "Ignored malformed catalogue equipment record."
                return@forEach
            }
            val id = itemIndex[normalise(source.name)]
            if (id == null) {
                unknown += "equipment:${source.name}"
            } else {
                val item = db.item(id) ?: return@forEach
                // A current battle spell must never become equipment, even if a source calls it one.
                if (item.category == ItemCategory.SPELL) return@forEach
                itemsMatched++
                if (normalise(source.name) != normalise(item.name)) {
                    itemAliases.getOrPut(id) { linkedSetOf() } += source.name.trim()
                }
                source.priceGold?.let { itemPrices[id] = it }
            }
        }

        val usable = heroesMatched >= MetaApplyReport.MIN_CATALOGUE_HEROES &&
            itemsMatched >= MetaApplyReport.MIN_CATALOGUE_ITEMS
        if (!usable) {
            warnings += "Catalogue matched $heroesMatched heroes and $itemsMatched equipment — below the complete-snapshot safety floor."
            return CatalogueResult(db, heroesMatched, itemsMatched, false)
        }

        val heroes = db.heroes.map { hero ->
            val aliases = heroAliases[hero.id].orEmpty()
            if (aliases.isEmpty()) hero else hero.copy(aliases = hero.aliases + aliases)
        }
        val items = db.items.map { item ->
            val aliases = itemAliases[item.id].orEmpty()
            val price = itemPrices[item.id]
            if (aliases.isEmpty() && price == null) item else item.copy(
                aliases = item.aliases + aliases,
                cost = price ?: item.cost,
            )
        }
        val resultDb = HeroDatabase(
            patch = db.patch,
            heroes = heroes,
            counters = db.counters,
            synergies = db.synergies,
            items = items,
            coreBuilds = db.coreBuilds,
        )
        return CatalogueResult(resultDb, heroesMatched, itemsMatched, true)
    }

    private fun report(
        overlay: MetaOverlay,
        heroesMatched: Int,
        tiersChanged: Int,
        countersAdded: Int,
        catalogue: CatalogueResult,
        unknown: List<String>,
        warnings: List<String>,
    ) = MetaApplyReport(
        patch = overlay.patch,
        updatedAt = overlay.updatedAt,
        source = overlay.source,
        heroesMatched = heroesMatched,
        tiersChanged = tiersChanged,
        countersAdded = countersAdded,
        catalogueHeroesMatched = catalogue.heroesMatched,
        catalogueItemsMatched = catalogue.itemsMatched,
        unknownNames = unknown.distinct().take(40),
        warnings = warnings.distinct().take(40),
    )

    private fun heroResolver(db: HeroDatabase): HeroNameResolver = HeroNameResolver(
        db.heroes.flatMap { hero ->
            (setOf(hero.id, hero.name) + hero.aliases).map { hero.id to it }
        },
    )

    private fun itemIndex(items: List<Item>): Map<String, String> {
        val entries = buildList {
            items.filter { it.category != ItemCategory.SPELL }.forEach { item ->
                add(normalise(item.name) to item.id)
                item.aliases.forEach { add(normalise(it) to item.id) }
            }
            // Verified source naming changes that differ from the authored catalogue. This is a
            // closed map, not a fuzzy matcher; new mappings require a source/test review.
            add(normalise("Magic Boots") to "magic-shoes")
            add(normalise("Demon Boots") to "demon-shoes")
        }
        return entries
            .filter { (key, _) -> key.isNotBlank() }
            .groupBy { it.first }
            .mapNotNull { (key, matches) ->
                matches.map { it.second }.distinct().singleOrNull()?.let { key to it }
            }
            .toMap()
    }

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
                if (lane in hero.lanes) listOf(lane) else {
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

    private fun isValidSourceRecord(sourceId: String, name: String): Boolean =
        sourceId.isNotBlank() && sourceId.length <= 80 &&
            name.isNotBlank() && name.length in 2..100 && name.none { it.isISOControl() }

    private fun normalise(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun blend(live: Double, seed: Double): Double =
        ((LIVE_WEIGHT * live) + ((1 - LIVE_WEIGHT) * seed)).coerceIn(0.0, 10.0)

    private data class CatalogueResult(
        val database: HeroDatabase,
        val heroesMatched: Int,
        val itemsMatched: Int,
        val isUsable: Boolean,
    ) {
        companion object {
            fun empty(database: HeroDatabase) = CatalogueResult(database, 0, 0, false)
        }
    }

    private const val DEFAULT_SEED_TIER = 5.0
}
