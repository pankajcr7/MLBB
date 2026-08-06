package com.pankaj.mlbbdraft.engine.scoring

import com.pankaj.mlbbdraft.engine.model.DamageType
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Trait

/** The things a five-hero team has to cover. */
enum class CompAxis {
    PHYSICAL_DAMAGE,
    MAGIC_DAMAGE,
    FRONTLINE,
    CROWD_CONTROL,
    WAVECLEAR,
    ENGAGE,
    PEEL,
    LATE_GAME,
    ;

    val label: String
        get() = when (this) {
            PHYSICAL_DAMAGE -> "physical damage"
            MAGIC_DAMAGE -> "magic damage"
            FRONTLINE -> "a frontline"
            CROWD_CONTROL -> "crowd control"
            WAVECLEAR -> "waveclear"
            ENGAGE -> "engage"
            PEEL -> "peel for the carry"
            LATE_GAME -> "late-game scaling"
        }
}

/** How badly the team still needs each axis, 0 (covered) to 1 (missing entirely). */
data class CompNeeds(val values: Map<CompAxis, Double>) {
    operator fun get(axis: CompAxis): Double = values[axis] ?: 0.0

    /** Most urgent first. */
    val ranked: List<Pair<CompAxis, Double>>
        get() = values.entries.sortedByDescending { it.value }.map { it.key to it.value }

    val missing: List<CompAxis> get() = ranked.filter { it.second >= 0.5 }.map { it.first }
}

object CompNeedAnalyzer {
    /**
     * Before anything is picked every axis is equally open. Using a mid value rather
     * than 1.0 keeps comp needs from dominating a first pick, where meta strength
     * and comfort matter more.
     */
    private const val EMPTY_TEAM_NEED = 0.35

    fun needs(team: List<Hero>): CompNeeds {
        if (team.isEmpty()) {
            return CompNeeds(CompAxis.entries.associateWith { EMPTY_TEAM_NEED })
        }

        val damageDealers = team.filter { maxOf(it.attrs.burst, it.attrs.sustainedDamage) >= 7 }
        val physicalSources = damageDealers.count {
            it.damageType == DamageType.PHYSICAL || it.damageType == DamageType.MIXED
        }
        val magicSources = damageDealers.count {
            it.damageType == DamageType.MAGIC || it.damageType == DamageType.MIXED
        }

        return CompNeeds(
            mapOf(
                // One source is a start, two is comfortable. A team with only one
                // damage type gets blunted by a single defensive item.
                CompAxis.PHYSICAL_DAMAGE to presenceNeed(physicalSources),
                CompAxis.MAGIC_DAMAGE to presenceNeed(magicSources),
                CompAxis.FRONTLINE to presenceNeed(team.count { it.attrs.durability >= 8 }),
                // For these, one hero who is genuinely good at it is enough.
                CompAxis.CROWD_CONTROL to bestNeed(team.maxOf { it.attrs.crowdControl }, target = 8),
                CompAxis.WAVECLEAR to bestNeed(team.maxOf { it.attrs.waveclear }, target = 7),
                CompAxis.ENGAGE to bestNeed(team.maxOf { it.attrs.engage }, target = 8),
                CompAxis.PEEL to peelNeed(team),
                CompAxis.LATE_GAME to bestNeed(team.maxOf { it.attrs.curve.late }, target = 9) * 0.6,
            ),
        )
    }

    /** What a candidate would contribute on each axis, 0..1. */
    fun supply(hero: Hero): Map<CompAxis, Double> {
        val a = hero.attrs
        val damageWeight = maxOf(a.burst, a.sustainedDamage) / 10.0
        val physical = when (hero.damageType) {
            DamageType.PHYSICAL -> damageWeight
            DamageType.MIXED, DamageType.TRUE -> damageWeight * 0.6
            DamageType.MAGIC -> 0.0
        }
        val magic = when (hero.damageType) {
            DamageType.MAGIC -> damageWeight
            DamageType.MIXED, DamageType.TRUE -> damageWeight * 0.6
            DamageType.PHYSICAL -> 0.0
        }
        return mapOf(
            CompAxis.PHYSICAL_DAMAGE to physical,
            CompAxis.MAGIC_DAMAGE to magic,
            CompAxis.FRONTLINE to a.durability / 10.0,
            CompAxis.CROWD_CONTROL to a.crowdControl / 10.0,
            CompAxis.WAVECLEAR to a.waveclear / 10.0,
            CompAxis.ENGAGE to a.engage / 10.0,
            CompAxis.PEEL to a.peel / 10.0,
            CompAxis.LATE_GAME to a.curve.late / 10.0,
        )
    }

    /**
     * Weighted average of what the candidate supplies on the axes that are actually
     * needed, centred so an average filler pick scores 0 rather than 0.5.
     */
    fun score(hero: Hero, needs: CompNeeds): Double {
        val supply = supply(hero)
        val totalNeed = needs.values.values.sum()
        if (totalNeed <= 0.0) return 0.0
        val covered = needs.values.entries.sumOf { (axis, need) -> need * (supply[axis] ?: 0.0) }
        return ((covered / totalNeed) - 0.5).times(2.0).coerceIn(-1.0, 1.0)
    }

    /** Which needs this hero would actually fix, most urgent first. */
    fun fills(hero: Hero, needs: CompNeeds): List<CompAxis> {
        val supply = supply(hero)
        return needs.ranked
            .filter { (axis, need) -> need >= 0.4 && (supply[axis] ?: 0.0) >= 0.7 }
            .map { it.first }
    }

    private fun presenceNeed(count: Int): Double = when (count) {
        0 -> 1.0
        1 -> 0.3
        else -> 0.0
    }

    private fun bestNeed(best: Int, target: Int): Double =
        ((target - best).toDouble() / target).coerceIn(0.0, 1.0)

    /** Peel only matters if there is something fragile worth peeling for. */
    private fun peelNeed(team: List<Hero>): Double {
        val needsProtecting = team.any { hero ->
            maxOf(hero.attrs.burst, hero.attrs.sustainedDamage) >= 8 &&
                (hero.has(Trait.IMMOBILE) || hero.attrs.mobility <= 4) &&
                hero.attrs.durability <= 5
        }
        val best = team.maxOf { it.attrs.peel }
        val raw = bestNeed(best, target = 7)
        return if (needsProtecting) raw else raw * 0.4
    }
}
