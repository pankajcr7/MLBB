package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.model.DamageType
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.Trait
import com.pankaj.mlbbdraft.engine.scoring.CompNeedAnalyzer
import com.pankaj.mlbbdraft.engine.scoring.CompNeeds

/** Shares of the team's damage output, each 0..1, summing to 1 when any damage exists. */
data class DamageSplit(
    val physical: Double,
    val magic: Double,
    val trueDamage: Double,
) {
    val isBalanced: Boolean get() = physical in 0.3..0.7

    companion object {
        val EMPTY = DamageSplit(0.0, 0.0, 0.0)
    }
}

data class CurveSummary(val early: Double, val mid: Double, val late: Double)

/**
 * Team composition health. [warnings] and [strengths] are the parts worth showing
 * during a draft; the numbers behind them drive the meters and the curve graph.
 */
data class CompReport(
    val side: Side,
    val heroes: List<Hero>,
    val damage: DamageSplit,
    val frontlineCount: Int,
    /** Team average, 0..10. */
    val crowdControl: Double,
    /** Best hero on the team, 0..10 — one strong clearer is enough. */
    val waveclear: Double,
    val engage: Double,
    val peel: Double,
    /** Team average, 0..10. */
    val sustain: Double,
    val curve: CurveSummary,
    val needs: CompNeeds,
    val warnings: List<String>,
    val strengths: List<String>,
)

object CompReportBuilder {
    fun build(side: Side, heroes: List<Hero>): CompReport {
        if (heroes.isEmpty()) {
            return CompReport(
                side = side,
                heroes = emptyList(),
                damage = DamageSplit.EMPTY,
                frontlineCount = 0,
                crowdControl = 0.0,
                waveclear = 0.0,
                engage = 0.0,
                peel = 0.0,
                sustain = 0.0,
                curve = CurveSummary(0.0, 0.0, 0.0),
                needs = CompNeedAnalyzer.needs(emptyList()),
                warnings = emptyList(),
                strengths = emptyList(),
            )
        }

        val damage = damageSplit(heroes)
        val frontline = heroes.count { it.attrs.durability >= 8 }
        val crowdControl = heroes.map { it.attrs.crowdControl }.average()
        val waveclear = heroes.maxOf { it.attrs.waveclear }.toDouble()
        val engage = heroes.maxOf { it.attrs.engage }.toDouble()
        val peel = heroes.maxOf { it.attrs.peel }.toDouble()
        val sustain = heroes.map { it.attrs.sustain }.average()
        val curve = CurveSummary(
            early = heroes.map { it.attrs.curve.early }.average(),
            mid = heroes.map { it.attrs.curve.mid }.average(),
            late = heroes.map { it.attrs.curve.late }.average(),
        )

        val fragileCarry = heroes.any {
            maxOf(it.attrs.burst, it.attrs.sustainedDamage) >= 8 &&
                (it.has(Trait.IMMOBILE) || it.attrs.mobility <= 4) &&
                it.attrs.durability <= 5
        }
        val immobileCount = heroes.count { it.has(Trait.IMMOBILE) || it.attrs.mobility <= 3 }

        val warnings = buildList {
            if (damage.physical >= 0.8) {
                add("Almost all physical damage — one Antique Cuirass blunts the whole team.")
            }
            if (damage.magic >= 0.8) {
                add("Almost all magic damage — Radiant Armor shuts the team down.")
            }
            if (frontline == 0 && heroes.size >= 3) {
                add("No frontline: nobody can absorb the enemy engage.")
            }
            if (crowdControl < 4.5) {
                add("Very little crowd control — mobile enemies will simply walk away.")
            }
            if (fragileCarry && peel < 6) {
                add("Your carry is fragile and nobody on the team can peel for them.")
            }
            if (waveclear < 7) {
                add("Weak waveclear — expect to defend under tower and lose map control.")
            }
            if (immobileCount >= 2) {
                add("$immobileCount immobile heroes — a single group engage catches them together.")
            }
            if (heroes.size >= 4 && curve.early < 5.8) {
                add("Slow start: avoid early skirmishes and farm to your power spike.")
            }
            if (engage < 6 && peel < 6 && heroes.size >= 4) {
                add("The team can neither start a fight nor stop one.")
            }
        }

        val strengths = buildList {
            if (engage >= 9) add("Elite engage — you decide when fights happen.")
            if (crowdControl >= 7) add("Chain crowd control: targets stay locked long enough to die.")
            if (sustain >= 7) add("Heavy sustain — you win long fights and sieges.")
            if (curve.late >= 8.5) add("Strong late game — trade map pressure for scaling time.")
            if (curve.early >= 7.2) add("Strong early game — force fights before the enemy comes online.")
            if (damage.isBalanced) add("Balanced damage types — the enemy cannot itemise cheaply against you.")
            if (frontline >= 2) add("Two frontliners: hard to burst down and hard to run through.")
        }

        return CompReport(
            side = side,
            heroes = heroes,
            damage = damage,
            frontlineCount = frontline,
            crowdControl = crowdControl,
            waveclear = waveclear,
            engage = engage,
            peel = peel,
            sustain = sustain,
            curve = curve,
            needs = CompNeedAnalyzer.needs(heroes),
            warnings = warnings,
            strengths = strengths,
        )
    }

    /**
     * Each hero contributes in proportion to how much damage they actually deal, so a
     * tank does not swing the split the way a carry does.
     */
    private fun damageSplit(heroes: List<Hero>): DamageSplit {
        var physical = 0.0
        var magic = 0.0
        var trueDamage = 0.0
        heroes.forEach { hero ->
            val weight = maxOf(hero.attrs.burst, hero.attrs.sustainedDamage) / 10.0
            when (hero.damageType) {
                DamageType.PHYSICAL -> physical += weight
                DamageType.MAGIC -> magic += weight
                DamageType.TRUE -> trueDamage += weight
                DamageType.MIXED -> {
                    physical += weight / 2
                    magic += weight / 2
                }
            }
        }
        val total = physical + magic + trueDamage
        if (total <= 0.0) return DamageSplit.EMPTY
        return DamageSplit(physical / total, magic / total, trueDamage / total)
    }
}
