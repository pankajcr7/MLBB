package com.pankaj.mlbbdraft.engine.scoring

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Hero

/** One other hero's contribution to an axis, with the reasons behind it. */
data class MatchupContribution(
    val other: Hero,
    val value: Double,
    val notes: List<String>,
)

data class AxisResult(
    val raw: Double,
    val contributions: List<MatchupContribution>,
) {
    val strongest: MatchupContribution? get() = contributions.maxByOrNull { it.value }
    val weakest: MatchupContribution? get() = contributions.minByOrNull { it.value }

    companion object {
        val EMPTY = AxisResult(0.0, emptyList())
    }
}

/**
 * Blends hand-authored matchup edges with trait heuristics.
 *
 * Authored data dominates where it exists; heuristics fill the gaps. As the dataset
 * grows, lower [Weights.heuristicBlend] rather than deleting rules — the rules stay
 * useful for heroes released after the edges were written.
 */
class MatchupScorer(
    private val db: HeroDatabase,
    private val weights: Weights = Weights.DEFAULT,
) {
    /**
     * Net counter value of [candidate] against [enemy], -1..1.
     * Positive means the candidate is favoured.
     */
    fun counterPair(candidate: Hero, enemy: Hero): MatchupContribution {
        val forEdge = db.counterEdge(candidate.id, enemy.id)
        val againstEdge = db.counterEdge(enemy.id, candidate.id)
        val authored = (forEdge?.weight ?: 0.0) - (againstEdge?.weight ?: 0.0)

        val hitsFor = CounterHeuristics.hits(candidate, enemy)
        val hitsAgainst = CounterHeuristics.hits(enemy, candidate)
        val heuristic = hitsFor.sumOf { it.delta }.coerceIn(-1.0, 1.0) -
            hitsAgainst.sumOf { it.delta }.coerceIn(-1.0, 1.0)

        val value = (authored + weights.heuristicBlend * heuristic).coerceIn(-1.0, 1.0)

        val notes = buildList {
            forEdge?.note?.let { add(it) }
            if (forEdge != null && forEdge.note == null) {
                add("${candidate.name} counters ${enemy.name}")
            }
            againstEdge?.note?.let { add("Risk — $it") }
            if (againstEdge != null && againstEdge.note == null) {
                add("Risk — ${enemy.name} counters ${candidate.name}")
            }
            hitsFor.sortedByDescending { it.delta }.take(2).forEach { add(it.note) }
            hitsAgainst
                .filter { it.delta > 0 }
                .maxByOrNull { it.delta }
                ?.let { add("Risk — ${it.note}") }
        }

        return MatchupContribution(enemy, value, notes)
    }

    fun counter(candidate: Hero, enemies: List<Hero>): AxisResult {
        if (enemies.isEmpty()) return AxisResult.EMPTY
        val contributions = enemies.map { counterPair(candidate, it) }
        val values = contributions.map { it.value }
        // Mean plus a lean toward the single best matchup: hard-countering one key
        // enemy hero is worth more than being mildly fine against all five.
        val raw = (values.average() * 0.6 + values.max() * 0.4).coerceIn(-1.0, 1.0)
        return AxisResult(raw, contributions)
    }

    fun synergyPair(candidate: Hero, ally: Hero): MatchupContribution {
        val edge = db.synergyEdge(candidate.id, ally.id)
        val hits = SynergyHeuristics.hits(candidate, ally)
        val value = ((edge?.weight ?: 0.0) + weights.heuristicBlend * hits.sumOf { it.delta })
            .coerceIn(-1.0, 1.0)

        val notes = buildList {
            edge?.note?.let { add(it) }
            if (edge != null && edge.note == null) add("Works well with ${ally.name}")
            hits.sortedByDescending { it.delta }.take(2).forEach { add(it.note) }
        }
        return MatchupContribution(ally, value, notes)
    }

    fun synergy(candidate: Hero, allies: List<Hero>): AxisResult {
        if (allies.isEmpty()) return AxisResult.EMPTY
        val contributions = allies.map { synergyPair(candidate, it) }
        val values = contributions.map { it.value }
        val raw = (values.average() * 0.6 + values.max() * 0.4).coerceIn(-1.0, 1.0)
        return AxisResult(raw, contributions)
    }

    /**
     * How exposed [candidate] is to a counter-pick that the enemy can still make.
     *
     * Returns a negative raw value. Scales with how many picks the enemy has left
     * after ours — as last pick this is zero, which is exactly why last pick is
     * where you put your counter-pickable heroes.
     */
    fun exposure(candidate: Hero, state: DraftState, metaFloor: Double = 6.0): AxisResult {
        val picksAfter = state.enemyPicksAfterOurs
        if (picksAfter == 0) return AxisResult.EMPTY

        val threat = db.heroes
            .asSequence()
            .filter { it.id != candidate.id && it.id !in state.usedHeroIds }
            .filter { (it.tier.values.maxOrNull() ?: 0.0) >= metaFloor }
            .map { counterPair(it, candidate).let { c -> MatchupContribution(c.other, -c.value, c.notes) } }
            .filter { it.value > 0.0 }
            .maxByOrNull { it.value }
            ?: return AxisResult.EMPTY

        val urgency = (picksAfter / 3.0).coerceAtMost(1.0)
        val raw = -(threat.value * urgency).coerceIn(0.0, 1.0)
        val note = "${threat.other.name} is still open and answers this pick" +
            if (picksAfter > 1) " — the enemy has $picksAfter picks left" else ""
        return AxisResult(raw, listOf(threat.copy(notes = listOf(note))))
    }
}
