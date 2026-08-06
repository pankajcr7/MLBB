package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.PlayerProfile
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.scoring.MatchupScorer
import kotlin.math.exp

/** One driver of the draft advantage. [delta] is −1..1, positive favouring your team. */
data class WinFactor(
    val label: String,
    val delta: Double,
    val detail: String,
)

enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
    ;

    val label: String
        get() = when (this) {
            LOW -> "low confidence"
            MEDIUM -> "medium confidence"
            HIGH -> "full draft"
        }
}

/**
 * A **draft** advantage estimate, not a match prediction.
 *
 * It answers "who is favoured by these ten picks", which is the only question a draft
 * tool can honestly answer. It knows nothing about mechanical skill, rotations or who
 * tilts — which in practice matter more than the draft does. The percentage is
 * deliberately clamped well away from 0 and 100 for that reason.
 */
data class WinProbability(
    val allyPercent: Int,
    /** −1..1. The raw signal behind [allyPercent]. */
    val advantage: Double,
    val factors: List<WinFactor>,
    val confidence: Confidence,
    val headline: String,
    val caveat: String,
) {
    val enemyPercent: Int get() = 100 - allyPercent

    /** Factors worth showing, largest effect first. */
    val topFactors: List<WinFactor>
        get() = factors.filter { kotlin.math.abs(it.delta) >= 0.05 }
            .sortedByDescending { kotlin.math.abs(it.delta) }
}

class WinProbabilityModel(private val scorer: MatchupScorer) {

    fun evaluate(
        allies: List<Hero>,
        enemies: List<Hero>,
        profile: PlayerProfile = PlayerProfile(),
    ): WinProbability {
        val picksKnown = allies.size + enemies.size
        if (allies.isEmpty() && enemies.isEmpty()) {
            return WinProbability(
                allyPercent = 50,
                advantage = 0.0,
                factors = emptyList(),
                confidence = Confidence.LOW,
                headline = "Nothing drafted yet.",
                caveat = CAVEAT,
            )
        }

        val factors = buildList {
            add(matchupFactor(allies, enemies))
            add(metaFactor(allies, enemies))
            add(structureFactor(allies, enemies))
            masteryFactor(allies, profile)?.let { add(it) }
        }

        // Weighted so that matchups and composition dominate; meta tiers are a nudge.
        val weights = mapOf(
            LABEL_MATCHUP to 1.0,
            LABEL_META to 0.5,
            LABEL_STRUCTURE to 0.9,
            LABEL_MASTERY to 0.4,
        )
        val totalWeight = factors.sumOf { weights[it.label] ?: 0.0 }
        val advantage = if (totalWeight <= 0.0) {
            0.0
        } else {
            factors.sumOf { (weights[it.label] ?: 0.0) * it.delta } / totalWeight
        }.coerceIn(-1.0, 1.0)

        val confidence = when {
            picksKnown >= 10 -> Confidence.HIGH
            picksKnown >= 5 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        // Logistic, then clamped. A draft is rarely worth more than 25 points of edge.
        val raw = 1.0 / (1.0 + exp(-STEEPNESS * advantage))
        val percent = (raw * 100).coerceIn(MIN_PERCENT, MAX_PERCENT).toInt()

        return WinProbability(
            allyPercent = percent,
            advantage = advantage,
            factors = factors,
            confidence = confidence,
            headline = headline(percent, confidence),
            caveat = CAVEAT,
        )
    }

    // --- factors ---

    private fun matchupFactor(allies: List<Hero>, enemies: List<Hero>): WinFactor {
        if (allies.isEmpty() || enemies.isEmpty()) {
            return WinFactor(LABEL_MATCHUP, 0.0, "Not enough picks to judge matchups yet.")
        }
        val perAlly = allies.map { ally -> ally to scorer.counter(ally, enemies).raw }
        val delta = perAlly.map { it.second }.average().coerceIn(-1.0, 1.0)

        val best = perAlly.maxByOrNull { it.second }
        val worst = perAlly.minByOrNull { it.second }
        val detail = when {
            delta >= 0.15 && best != null ->
                "${best.first.name} in particular has a good matchup into their draft."
            delta <= -0.15 && worst != null ->
                "${worst.first.name} is on the wrong side of this matchup."
            else -> "Neither side has a decisive matchup edge."
        }
        return WinFactor(LABEL_MATCHUP, delta, detail)
    }

    private fun metaFactor(allies: List<Hero>, enemies: List<Hero>): WinFactor {
        val ours = allies.mapNotNull { it.tier.values.maxOrNull() }
        val theirs = enemies.mapNotNull { it.tier.values.maxOrNull() }
        if (ours.isEmpty() || theirs.isEmpty()) {
            return WinFactor(LABEL_META, 0.0, "Not enough picks to compare patch strength.")
        }
        val delta = ((ours.average() - theirs.average()) / 3.0).coerceIn(-1.0, 1.0)
        val detail = when {
            delta >= 0.1 -> "Your picks are stronger in the current patch."
            delta <= -0.1 -> "Their picks are stronger in the current patch."
            else -> "Both drafts are around the same patch strength."
        }
        return WinFactor(LABEL_META, delta, detail)
    }

    private fun structureFactor(allies: List<Hero>, enemies: List<Hero>): WinFactor {
        val ourReport = CompReportBuilder.build(Side.ALLY, allies)
        val theirReport = CompReportBuilder.build(Side.ENEMY, enemies)
        val ours = structural(ourReport, allies)
        val theirs = structural(theirReport, enemies)
        val delta = ((ours - theirs) * 2.0).coerceIn(-1.0, 1.0)

        val detail = when {
            delta >= 0.1 -> ourReport.strengths.firstOrNull()
                ?: "Your composition covers more of what a team needs."
            delta <= -0.1 -> ourReport.warnings.firstOrNull()
                ?: "Their composition is the more complete one."
            else -> "Both compositions cover roughly the same ground."
        }
        return WinFactor(LABEL_STRUCTURE, delta, detail)
    }

    private fun masteryFactor(allies: List<Hero>, profile: PlayerProfile): WinFactor? {
        if (!profile.isConfigured || allies.isEmpty()) return null
        val rated = allies.map { profile.comfortOf(it.id) }
        val delta = ((rated.average() / 5.0) - 0.5).times(2.0).coerceIn(-1.0, 1.0)
        val detail = when {
            delta >= 0.1 -> "You are on heroes you actually play well."
            delta <= -0.1 -> "Some of these are heroes you have not rated — that costs more than the draft does."
            else -> "Comfort on these picks is average for you."
        }
        return WinFactor(LABEL_MASTERY, delta, detail)
    }

    /** How completely a team covers the things a team has to cover, 0..1. */
    private fun structural(report: CompReport, heroes: List<Hero>): Double {
        if (heroes.isEmpty()) return 0.5
        return listOf(
            if (report.frontlineCount >= 1) 1.0 else 0.0,
            (report.crowdControl / 10.0).coerceIn(0.0, 1.0),
            (report.engage / 10.0).coerceIn(0.0, 1.0),
            (report.peel / 10.0).coerceIn(0.0, 1.0),
            (report.waveclear / 10.0).coerceIn(0.0, 1.0),
            if (report.damage.isBalanced) 1.0 else 0.45,
        ).average()
    }

    private fun headline(percent: Int, confidence: Confidence): String {
        val body = when {
            percent >= 62 -> "Your draft is clearly favoured."
            percent >= 55 -> "Your draft is slightly ahead."
            percent > 45 -> "This draft is close to even."
            percent > 38 -> "Their draft is slightly ahead."
            else -> "Their draft is clearly favoured."
        }
        return if (confidence == Confidence.HIGH) {
            body
        } else {
            "$body Still ${confidence.label} — this will move as picks come in."
        }
    }

    private companion object {
        const val STEEPNESS = 2.2
        const val MIN_PERCENT = 22.0
        const val MAX_PERCENT = 78.0
        const val LABEL_MATCHUP = "Matchups"
        const val LABEL_META = "Patch strength"
        const val LABEL_STRUCTURE = "Team composition"
        const val LABEL_MASTERY = "Your mastery"
        const val CAVEAT =
            "Draft advantage only. Mechanics, rotations and objective calls decide more games than the draft does."
    }
}
