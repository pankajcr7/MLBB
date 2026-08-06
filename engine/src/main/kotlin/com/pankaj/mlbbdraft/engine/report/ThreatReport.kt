package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Trait

data class Threat(
    val hero: Hero,
    /** 0..1. */
    val score: Double,
    val tip: String,
)

/**
 * What to expect from the enemy draft once picks are locked. This is the part players
 * actually use after the draft ends, so it should read like a coach talking, not a
 * stat sheet.
 */
data class ThreatReport(
    val threats: List<Threat>,
    /** One sentence on who wins which stage of the game. */
    val tempo: String,
    val tips: List<String>,
)

object ThreatAnalyzer {
    fun analyze(enemies: List<Hero>, allies: List<Hero>): ThreatReport {
        if (enemies.isEmpty()) {
            return ThreatReport(emptyList(), "Pick some enemy heroes to see a threat report.", emptyList())
        }

        val threats = enemies
            .map { hero -> Threat(hero, threatScore(hero), tipFor(hero)) }
            .sortedByDescending { it.score }
            .take(3)

        return ThreatReport(
            threats = threats,
            tempo = tempo(enemies, allies),
            tips = tips(enemies),
        )
    }

    private fun threatScore(hero: Hero): Double {
        val a = hero.attrs
        return (a.burst * 0.35 + a.pickPotential * 0.35 + a.teamfight * 0.2 + a.mobility * 0.1) / 10.0
    }

    private fun tipFor(hero: Hero): String {
        hero.notes?.let { return it }
        val a = hero.attrs
        return when {
            a.pickPotential >= 9 -> "Will look to catch someone alone — move as a pair near their side of the map."
            a.burst >= 9 -> "Kills you from full HP in one rotation; do not be the closest target when it starts."
            a.teamfight >= 9 -> "Strongest in a grouped 5v5 — split the map instead of grouping into them."
            a.durability >= 9 -> "Will not die in a normal fight; play around them rather than through them."
            else -> "Standard threat — respect their cooldowns and rotate when they use them."
        }
    }

    private fun tempo(enemies: List<Hero>, allies: List<Hero>): String {
        if (allies.isEmpty()) {
            val avgEarly = enemies.map { it.attrs.curve.early }.average()
            val avgLate = enemies.map { it.attrs.curve.late }.average()
            return when {
                avgEarly >= 7.0 -> "Their draft is built to win early — expect pressure from the first minute."
                avgLate >= 8.5 -> "Their draft scales; the longer the game runs the worse it gets for you."
                else -> "Their draft has no extreme timing — the game is decided on objectives."
            }
        }

        val ourEarly = allies.map { it.attrs.curve.early }.average()
        val theirEarly = enemies.map { it.attrs.curve.early }.average()
        val ourLate = allies.map { it.attrs.curve.late }.average()
        val theirLate = enemies.map { it.attrs.curve.late }.average()
        val earlyEdge = ourEarly - theirEarly
        val lateEdge = ourLate - theirLate

        return when {
            earlyEdge >= 0.7 && lateEdge <= -0.5 ->
                "You are stronger early and weaker late — force objectives before 10 minutes."
            earlyEdge <= -0.7 && lateEdge >= 0.5 ->
                "You lose the early game and win the late one — give up the first Turtle and farm."
            earlyEdge <= -0.7 ->
                "They are stronger early and it does not get better — play safe and defend."
            lateEdge >= 0.7 ->
                "You out-scale them; every minute you survive is a minute in your favour."
            else -> "The two drafts have similar timing — this comes down to objectives and rotations."
        }
    }

    private fun tips(enemies: List<Hero>): List<String> = buildList {
        if (enemies.any { it.has(Trait.INVISIBILITY) }) {
            add("Someone on their team goes invisible — keep vision at objectives and do not rotate alone.")
        }
        if (enemies.any { it.has(Trait.HOOK) || it.has(Trait.SUPPRESSION) }) {
            add("Do not walk in the open near fog. Hold Purify or Flicker if you are the target.")
        }
        if (enemies.any { it.has(Trait.GLOBAL_PRESENCE) }) {
            add("They have map-wide pressure — never sit at low HP thinking you are safe.")
        }
        if (enemies.any { it.has(Trait.HEAVY_HEAL) || it.attrs.sustain >= 8 }) {
            add("Anti-heal is mandatory, not optional. Buy it on your main damage dealer first.")
        }
        if (enemies.count { it.attrs.durability >= 8 } >= 2) {
            add("Double frontline: you need percent-HP or true damage, not raw attack.")
        }
        if (enemies.any { it.has(Trait.DASH_HEAVY) || it.has(Trait.BLINK) }) {
            add("Their dive relies on dashes — anti-dash CC and blocking terrain are worth more than raw damage.")
        }
        if (enemies.count { it.attrs.range >= 8 } >= 2) {
            add("They out-range you: do not walk into their poke, force fights from fog or terrain.")
        }
        if (enemies.any { it.has(Trait.SPLIT_PUSH) }) {
            add("They have a side-lane threat — track them or you lose towers while winning fights.")
        }
    }
}
