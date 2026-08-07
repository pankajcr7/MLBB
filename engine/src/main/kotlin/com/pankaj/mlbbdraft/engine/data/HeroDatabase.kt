package com.pankaj.mlbbdraft.engine.data

import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.MatchupEdge
import com.pankaj.mlbbdraft.engine.model.Role
import com.pankaj.mlbbdraft.engine.model.SynergyEdge

/**
 * In-memory, indexed view of the dataset. Built once at startup; every lookup the
 * scorers do is a hash lookup so a full 100-candidate scoring pass stays sub-millisecond
 * (this matters when Phase 1 re-runs it on every detected draft change).
 */
class HeroDatabase(
    val patch: String,
    heroes: List<Hero>,
    val counters: List<MatchupEdge> = emptyList(),
    val synergies: List<SynergyEdge> = emptyList(),
    val items: List<Item> = emptyList(),
    /** heroId -> ordered core item ids. Absent heroes fall back to an archetype default. */
    val coreBuilds: Map<String, List<String>> = emptyMap(),
) {
    private val itemsById: Map<String, Item> = items.associateBy { it.id }

    fun item(id: String): Item? = itemsById[id]

    /** The hand-authored core for this hero, or empty if it uses the archetype default. */
    fun coreBuild(heroId: String): List<Item> = coreBuilds[heroId].orEmpty().mapNotNull { itemsById[it] }

    fun requireItem(id: String): Item = itemsById[id] ?: error("Unknown item id '$id'")

    fun items(ids: List<String>): List<Item> = ids.mapNotNull { itemsById[it] }

    val heroes: List<Hero> = heroes.sortedBy { it.name }

    private val byId: Map<String, Hero> = heroes.associateBy { it.id }

    /** hero -> (opponent -> edge). */
    private val counterIndex: Map<String, Map<String, MatchupEdge>> =
        counters.groupBy { it.hero }.mapValues { (_, edges) -> edges.associateBy { it.vs } }

    /** Stored both ways because synergy is symmetric. */
    private val synergyIndex: Map<String, Map<String, SynergyEdge>> =
        synergies
            .flatMap { listOf(it.a to it, it.b to it) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (owner, edges) ->
                edges.associateBy { if (it.a == owner) it.b else it.a }
            }

    private val byLane: Map<Lane, List<Hero>> =
        Lane.entries.associateWith { lane -> this.heroes.filter { lane in it.lanes } }

    val size: Int get() = heroes.size

    fun hero(id: String): Hero? = byId[id]

    fun require(id: String): Hero = byId[id] ?: error("Unknown hero id '$id'")

    fun heroes(ids: Collection<String>): List<Hero> = ids.mapNotNull { byId[it] }

    /** Authored edge for "[hero] counters [vs]", if one exists. */
    fun counterEdge(hero: String, vs: String): MatchupEdge? = counterIndex[hero]?.get(vs)

    fun synergyEdge(a: String, b: String): SynergyEdge? = synergyIndex[a]?.get(b)

    fun inLane(lane: Lane): List<Hero> = byLane[lane].orEmpty()

    fun inRole(role: Role): List<Hero> = heroes.filter { role in it.roles }

    fun search(query: String): List<Hero> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return heroes
        return heroes.filter { it.name.lowercase().contains(q) || it.id.contains(q) }
            .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
    }

    /**
     * Dataset integrity problems, as human-readable strings. Empty means clean.
     * A unit test asserts this is empty, so a typo in a hero id fails the build
     * rather than silently producing a hero with no counters.
     */
    fun validate(): List<String> = buildList {
        val duplicates = heroes.groupBy { it.id }.filterValues { it.size > 1 }.keys
        duplicates.forEach { add("Duplicate hero id '$it'") }

        heroes.forEach { hero ->
            if (hero.roles.isEmpty()) add("Hero '${hero.id}' has no roles")
            if (hero.lanes.isEmpty()) add("Hero '${hero.id}' has no lanes")
            if (hero.difficulty !in 1..5) {
                add("Hero '${hero.id}' difficulty ${hero.difficulty} outside 1..5")
            }
            hero.tier.keys.filterNot { it in hero.lanes }.forEach { lane ->
                add("Hero '${hero.id}' has a tier for $lane but does not list that lane")
            }
            hero.tier.filterValues { it !in 0.0..10.0 }.forEach { (lane, value) ->
                add("Hero '${hero.id}' tier for $lane is $value, outside 0..10")
            }
        }

        counters.forEach { edge ->
            if (edge.hero !in byId) add("Counter edge references unknown hero '${edge.hero}'")
            if (edge.vs !in byId) add("Counter edge references unknown hero '${edge.vs}'")
            if (edge.hero == edge.vs) add("Counter edge '${edge.hero}' points at itself")
        }
        counters.groupBy { it.hero to it.vs }
            .filterValues { it.size > 1 }
            .keys
            .forEach { (hero, vs) -> add("Duplicate counter edge '$hero' vs '$vs'") }

        synergies.forEach { edge ->
            if (edge.a !in byId) add("Synergy edge references unknown hero '${edge.a}'")
            if (edge.b !in byId) add("Synergy edge references unknown hero '${edge.b}'")
            if (edge.a == edge.b) add("Synergy edge '${edge.a}' points at itself")
        }

        items.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { add("Duplicate item id '$it'") }
        items.filter { it.summary.isBlank() }.forEach { add("Item '${it.id}' has no summary") }

        // Core builds are the most error-prone part of the dataset: it is very easy to
        // write an item that does not exist, or one the hero cannot buy.
        coreBuilds.forEach { (heroId, ids) ->
            val hero = byId[heroId]
            if (hero == null) {
                add("Core build references unknown hero '$heroId'")
                return@forEach
            }
            ids.filterNot { it in itemsById }.forEach {
                add("Core build for '$heroId' references unknown item '$it'")
            }
            ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                add("Core build for '$heroId' lists '$it' twice")
            }
            ids.mapNotNull { itemsById[it] }.forEach { item ->
                if (!item.buildableBy(hero)) {
                    add("Core build for '$heroId' includes '${item.id}', which ${hero.name} cannot use")
                }
                if (item.category == ItemCategory.MOVEMENT || item.category == ItemCategory.SPELL) {
                    add("Core build for '$heroId' includes '${item.id}' — boots and spells are chosen separately")
                }
            }
        }
    }
}
