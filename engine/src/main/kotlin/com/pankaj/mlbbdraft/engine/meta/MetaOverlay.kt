package com.pankaj.mlbbdraft.engine.meta

import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.MatchupEdge
import kotlinx.serialization.Serializable

/**
 * Live meta statistics for one hero, as published by whatever source you point at.
 *
 * [name] may be our slug (`yi-sun-shin`) or the source's display name
 * (`Yi Sun-shin`, `X.Borg`) — [HeroNameResolver] handles both, so a new data source
 * does not need to know our ids.
 *
 * Rates accept either 0..1 or 0..100; they are normalised on parse, because half the
 * sources publish `54.2` and the other half `0.542`.
 */
@Serializable
data class MetaHero(
    val name: String,
    val winRate: Double? = null,
    val pickRate: Double? = null,
    val banRate: Double? = null,
    /** Optional: which lane these numbers are for. Null applies to every lane the hero plays. */
    val lane: Lane? = null,
    /** If the source already grades heroes 0..10, that wins over the derived value. */
    val tier: Double? = null,
) {
    /** Rates as 0..1 regardless of how the source expressed them. */
    fun normalised(): MetaHero = copy(
        winRate = winRate?.let(::toFraction),
        pickRate = pickRate?.let(::toFraction),
        banRate = banRate?.let(::toFraction),
    )

    private fun toFraction(value: Double): Double =
        (if (value > 1.0) value / 100.0 else value).coerceIn(0.0, 1.0)
}

/** A source hero identity. It may add an OCR alias to an already-authored hero, never a new playable hero. */
@Serializable
data class CatalogueHero(
    val sourceId: String,
    val name: String,
    val roles: List<String> = emptyList(),
)

/** A source equipment identity. It may update a known item price/name alias, never its semantic tags. */
@Serializable
data class CatalogueItem(
    val sourceId: String,
    val name: String,
    val priceGold: Int? = null,
)

/**
 * A constrained, reviewable snapshot transformed from the selected upstream catalogue.
 * It never transports artwork, item categories, counter tags, core builds, or new playable heroes.
 */
@Serializable
data class CatalogueOverlay(
    val upstreamCommit: String,
    val heroes: List<CatalogueHero> = emptyList(),
    val equipment: List<CatalogueItem> = emptyList(),
)

/**
 * A patch's worth of live data, layered on top of the bundled dataset.
 *
 * Deliberately additive: it can move meta tiers and add matchup edges, but it cannot
 * delete heroes or overwrite hand-authored counter notes. A bad or hostile feed can
 * therefore make the advice worse, but it cannot make the app broken or empty.
 */
@Serializable
data class MetaOverlay(
    val patch: String,
    /** ISO-8601 UTC, e.g. `2026-08-06T10:00:00Z`. Free-form; only shown to the user. */
    val updatedAt: String,
    /** Where the numbers came from, for display and debugging. */
    val source: String = "",
    val heroes: List<MetaHero> = emptyList(),
    /** Optional extra matchup edges. Seed edges always win on conflict. */
    val counters: List<MatchupEdge> = emptyList(),
    /** Optional controlled catalogue supplement; it has no authority over advisory semantics. */
    val catalogue: CatalogueOverlay? = null,
) {
    val isEmpty: Boolean get() = heroes.isEmpty() && counters.isEmpty() && catalogue == null
}

/** What actually happened when an overlay was applied. Surfaced in the UI and logs. */
data class MetaApplyReport(
    val patch: String,
    val updatedAt: String,
    val source: String,
    val heroesMatched: Int,
    val tiersChanged: Int,
    val countersAdded: Int,
    /** Source heroes resolved to existing authored heroes; no new draftable heroes are created. */
    val catalogueHeroesMatched: Int = 0,
    /** Source equipment resolved to existing non-spell equipment. */
    val catalogueItemsMatched: Int = 0,
    /** Names in the feed that do not map to any hero we know. */
    val unknownNames: List<String>,
    val warnings: List<String>,
) {
    val isUsable: Boolean get() =
        heroesMatched >= MIN_MATCHED ||
            (catalogueHeroesMatched >= MIN_CATALOGUE_HEROES && catalogueItemsMatched >= MIN_CATALOGUE_ITEMS)

    companion object {
        /**
         * A feed that resolves fewer heroes than this is almost certainly the wrong
         * shape — a renamed field, an HTML error page, a truncated download. Better to
         * keep yesterday's cache than to half-apply garbage.
         */
        const val MIN_MATCHED = 20

        /** A catalogue-only payload must still look like the selected complete upstream source. */
        const val MIN_CATALOGUE_HEROES = 100
        const val MIN_CATALOGUE_ITEMS = 45
    }
}

/**
 * Matches a source's hero naming to our slugs.
 *
 * Normalising away everything except letters and digits covers the cases that actually
 * occur: `Yi Sun-shin`/`yi-sun-shin`, `X.Borg`/`x-borg`, `Luo Yi`/`luo-yi`,
 * `Yu Zhong`/`yu-zhong`.
 */
class HeroNameResolver(heroIdsAndNames: List<Pair<String, String>>) {
    private val index: Map<String, String> = buildMap {
        heroIdsAndNames.forEach { (id, name) ->
            put(normalise(id), id)
            put(normalise(name), id)
        }
    }

    fun resolve(name: String): String? = index[normalise(name)]

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
