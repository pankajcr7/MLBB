package com.pankaj.mlbbdraft.engine.scoring

import com.pankaj.mlbbdraft.engine.model.Component
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.ScorePart

internal data class ScoreContext(
    val hero: Hero,
    val lane: Lane?,
    val parts: List<ScorePart>,
    val counter: AxisResult,
    val synergy: AxisResult,
    val exposure: AxisResult,
    val needs: CompNeeds,
    val state: DraftState,
)

/**
 * Turns a score breakdown into text a player can act on during a 25-second draft timer.
 *
 * Rules: strongest reason first, at most one line per idea, and always name the enemy
 * hero involved. "Good counter pick" is useless; "your ult follows Ling onto walls" is
 * something the user can verify and trust.
 */
internal object ReasonBuilder {
    private const val MAX_REASONS = 5

    fun build(ctx: ScoreContext): List<String> {
        val reasons = mutableListOf<String>()

        ctx.counter.strongest
            ?.takeIf { it.value >= 0.2 }
            ?.notes
            ?.take(2)
            ?.let { reasons += it }

        val fills = CompNeedAnalyzer.fills(ctx.hero, ctx.needs)
        if (fills.isNotEmpty()) {
            val what = fills.take(2).joinToString(" and ") { it.label }
            reasons += "Covers $what, which your team is missing"
        }

        ctx.synergy.strongest
            ?.takeIf { it.value >= 0.25 }
            ?.notes
            ?.firstOrNull()
            ?.let { reasons += it }

        val tier = ctx.hero.tierIn(ctx.lane)
        if (tier != null && tier >= 8.0) {
            val where = ctx.lane?.label ?: ctx.hero.lanes.first().label
            reasons += "Top-tier in $where this patch"
        }

        val comfort = ctx.state.profile.comfort[ctx.hero.id] ?: 0
        if (comfort >= 4) {
            reasons += "One of your best heroes (comfort $comfort/5)"
        } else if (!ctx.state.profile.isConfigured && ctx.hero.difficulty >= 5) {
            reasons += "Mechanically demanding — set up your hero profile for advice you can act on"
        }

        // Risks last, so they read as caveats rather than as the headline.
        ctx.counter.contributions
            .filter { it.value <= -0.25 }
            .sortedBy { it.value }
            .take(2)
            .forEach { contribution ->
                val note = contribution.notes.firstOrNull { it.startsWith("Risk") }
                    ?: "Risk — ${contribution.other.name} has the better of this matchup"
                reasons += note
            }

        ctx.exposure.contributions.firstOrNull()?.notes?.firstOrNull()?.let {
            reasons += "Counter-pick risk — $it"
        }

        if ((ctx.parts.firstOrNull { it.component == Component.LANE_FIT }?.raw ?: 0.0) < 0.0 &&
            ctx.lane != null
        ) {
            reasons += "Off-role: ${ctx.hero.name} is not normally played in ${ctx.lane.label}"
        }

        return reasons.distinct().take(MAX_REASONS)
    }
}
