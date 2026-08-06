package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Trait

enum class PickVerdict {
    STRONG,
    FINE,
    RISKY,
    BAD,
    ;

    val label: String
        get() = when (this) {
            STRONG -> "Strong pick"
            FINE -> "Fine"
            RISKY -> "Risky"
            BAD -> "Bad pick"
        }

    val needsAttention: Boolean get() = this == RISKY || this == BAD
}

/**
 * A verdict on a pick that has **already been locked in**, which is a different question
 * from "what should I pick".
 *
 * Suggestions rank heroes you could still take. This tells you a teammate just picked
 * something that loses to what the enemy already has — while you can still adapt the rest
 * of the draft, your bans, or at least your items.
 */
data class PickAssessment(
    val hero: Hero,
    val lane: Lane?,
    val verdict: PickVerdict,
    /** −1..1, the net matchup value of this hero against the enemy picks. */
    val matchup: Double,
    /** Plain-English problems, worst first. Empty when the pick is fine. */
    val problems: List<String>,
    /** What to do about it now that the pick cannot be changed. */
    val advice: String?,
) {
    val isProblem: Boolean get() = verdict.needsAttention
}

object PickAssessor {
    /**
     * Thresholds are deliberately forgiving. Calling a pick "bad" is a strong claim, and a
     * tool that flags half the draft gets ignored — so only a genuinely losing matchup or a
     * hero the enemy specifically answers gets called out.
     */
    private const val BAD_MATCHUP = -0.30
    private const val RISKY_MATCHUP = -0.12

    fun assess(
        hero: Hero,
        lane: Lane?,
        matchup: Double,
        counteredBy: List<Pair<Hero, String?>>,
        enemies: List<Hero>,
        allies: List<Hero>,
    ): PickAssessment {
        val problems = mutableListOf<String>()

        // Always lead with the enemy's name. Authored notes are written from that hero's
        // point of view ("his airborne CC pulls Ling off walls"), so quoting one on its own
        // leaves the player asking *whose* CC.
        counteredBy.take(2).forEach { (enemy, note) ->
            problems += if (note != null) "${enemy.name} — $note" else "${enemy.name} beats this matchup"
        }

        if (lane != null && lane !in hero.lanes) {
            problems += "${hero.name} is not normally played in ${lane.label}"
        }

        // Structural problems that are about this hero specifically, not the whole comp.
        val fragile = hero.attrs.durability <= 4 &&
            (hero.has(Trait.IMMOBILE) || hero.attrs.mobility <= 4)
        val hunters = enemies.filter { it.attrs.pickPotential >= 9 }
        if (fragile && hunters.isNotEmpty()) {
            problems += "${hero.name} cannot escape, and ${hunters.joinToString(", ") { it.name }} " +
                "hunt heroes who cannot escape"
        }

        val enemyTanks = enemies.count { it.attrs.durability >= 8 }
        val cutsThroughTanks = hero.has(Trait.PERCENT_HP_DAMAGE) ||
            hero.has(Trait.TRUE_DAMAGE) ||
            hero.has(Trait.ARMOR_SHRED)
        if (enemyTanks >= 2 && !cutsThroughTanks && hero.attrs.sustainedDamage >= 7) {
            problems += "Flat damage into $enemyTanks frontliners — ${hero.name} needs penetration to matter"
        }

        if (hero.has(Trait.DASH_HEAVY) || hero.has(Trait.BLINK)) {
            enemies.firstOrNull { it.has(Trait.PUNISH_DASH) }?.let {
                problems += "${it.name} punishes every dash, and ${hero.name} lives on dashes"
            }
        }

        val verdict = when {
            matchup <= BAD_MATCHUP || counteredBy.size >= 2 -> PickVerdict.BAD
            matchup <= RISKY_MATCHUP || problems.isNotEmpty() -> PickVerdict.RISKY
            matchup >= 0.25 -> PickVerdict.STRONG
            else -> PickVerdict.FINE
        }

        return PickAssessment(
            hero = hero,
            lane = lane,
            verdict = verdict,
            matchup = matchup,
            problems = problems.distinct().take(3),
            advice = adviceFor(verdict, hero, enemies, allies, counteredBy),
        )
    }

    /**
     * The pick is locked, so advice has to be about something the player can still change:
     * a later pick, a ban, an item, or how they play the lane.
     */
    private fun adviceFor(
        verdict: PickVerdict,
        hero: Hero,
        enemies: List<Hero>,
        allies: List<Hero>,
        counteredBy: List<Pair<Hero, String?>>,
    ): String? {
        if (!verdict.needsAttention) return null

        val threat = counteredBy.firstOrNull()?.first
        val hasPeel = allies.any { it.attrs.peel >= 7 && it.id != hero.id }

        return when {
            threat != null && !hasPeel ->
                "Draft a roamer who can peel ${threat.name} off ${hero.name}, or ban ${threat.name} next phase."

            threat != null ->
                "Play ${hero.name} behind your frontline and hold an escape for ${threat.name}."

            hero.attrs.durability <= 4 && enemies.any { it.attrs.pickPotential >= 9 } ->
                "Buy Immortality or Winter Crown on ${hero.name} and never rotate alone."

            enemies.count { it.attrs.durability >= 8 } >= 2 ->
                "Build penetration on ${hero.name} — flat damage will not get through their frontline."

            else -> "Cover this with your remaining picks rather than trying to out-play it."
        }
    }
}
