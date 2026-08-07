package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Trait

/**
 * What a draft is *trying to do*.
 *
 * Five separate meters take longer to read than a draft timer allows. One label plus the
 * answer to it is something a player can act on in two seconds, which is the whole point.
 */
enum class CompArchetype {
    DIVE,
    POKE,
    PICK_OFF,
    GROUP_FIGHT,
    SCALING,
    SPLIT_PUSH,
    SUSTAIN,
    BALANCED,
}

data class ArchetypeVerdict(
    val archetype: CompArchetype,
    val label: String,
    /** What this team wants the game to look like. */
    val summary: String,
    /** What to do about it. Empty for [CompArchetype.BALANCED]. */
    val counterplay: String,
    /** The heroes that made it this archetype, so the call is checkable. */
    val evidence: List<Hero>,
    /** 0..1. Below [ArchetypeAnalyzer.THRESHOLD] the verdict is BALANCED. */
    val confidence: Double,
) {
    val isDistinct: Boolean get() = archetype != CompArchetype.BALANCED
}

object ArchetypeAnalyzer {
    /** Two of the three heroes a pattern needs before it is worth naming. */
    const val THRESHOLD = 0.66

    /** Fewer than this and a draft has no identity yet. */
    private const val MIN_HEROES = 3

    fun classify(heroes: List<Hero>): ArchetypeVerdict {
        if (heroes.size < MIN_HEROES) {
            return balanced(heroes, "Too few picks to read the draft's plan yet.")
        }

        // Listed most-specific first. Scores are capped at 1.0 so this really asks "did
        // this pattern fire", not "by how many multiples" — otherwise a pattern needing
        // only two heroes outranks one that five heroes satisfy. Ties go to the earlier
        // entry, which is why SCALING is last: almost every comp scales somewhat, so it is
        // the least useful thing to call a draft.
        val scored = listOf(
            score(CompArchetype.DIVE, heroes, need = 3) { hero ->
                (hero.has(Trait.DASH_HEAVY) || hero.has(Trait.BLINK) || hero.has(Trait.BACKLINE_ACCESS)) &&
                    hero.attrs.mobility >= 7
            },
            // Deliberately keyed on hook/suppression rather than raw pick potential, so
            // assassin dive does not get mislabelled as a Franco-style pick comp.
            score(CompArchetype.PICK_OFF, heroes, need = 2) { hero ->
                hero.has(Trait.HOOK) || hero.has(Trait.SUPPRESSION) ||
                    (hero.has(Trait.LONG_RANGE_CC) && hero.attrs.pickPotential >= 8)
            },
            score(CompArchetype.POKE, heroes, need = 3) { hero ->
                hero.attrs.range >= 8 && (hero.has(Trait.POKE) || hero.attrs.sustainedDamage >= 7)
            },
            score(CompArchetype.SUSTAIN, heroes, need = 2) { hero ->
                hero.has(Trait.HEAVY_HEAL) || hero.attrs.sustain >= 8
            },
            score(CompArchetype.SPLIT_PUSH, heroes, need = 2) { hero ->
                hero.has(Trait.SPLIT_PUSH) || hero.has(Trait.ANTI_TOWER)
            },
            score(CompArchetype.GROUP_FIGHT, heroes, need = 3) { hero ->
                hero.attrs.teamfight >= 9 && hero.attrs.crowdControl >= 6
            },
            score(CompArchetype.SCALING, heroes, need = 2) { hero ->
                hero.has(Trait.SCALING_CARRY) || hero.has(Trait.LATE_MONSTER) || hero.attrs.curve.late >= 9
            },
        )

        val best = scored.maxByOrNull { it.second } ?: return balanced(heroes, NO_PATTERN)
        val (archetype, confidence) = best
        if (confidence < THRESHOLD) return balanced(heroes, NO_PATTERN)

        val evidence = heroes.filter { matches(archetype, it) }
        return ArchetypeVerdict(
            archetype = archetype,
            label = labelFor(archetype),
            summary = summaryFor(archetype, evidence),
            counterplay = counterplayFor(archetype),
            evidence = evidence,
            confidence = confidence.coerceAtMost(1.0),
        )
    }

    private fun score(
        archetype: CompArchetype,
        heroes: List<Hero>,
        need: Int,
        predicate: (Hero) -> Boolean,
    ): Pair<CompArchetype, Double> =
        archetype to (heroes.count(predicate).toDouble() / need).coerceAtMost(1.0)

    private fun matches(archetype: CompArchetype, hero: Hero): Boolean = when (archetype) {
        CompArchetype.DIVE ->
            (hero.has(Trait.DASH_HEAVY) || hero.has(Trait.BLINK) || hero.has(Trait.BACKLINE_ACCESS)) &&
                hero.attrs.mobility >= 7

        CompArchetype.POKE ->
            hero.attrs.range >= 8 && (hero.has(Trait.POKE) || hero.attrs.sustainedDamage >= 7)

        CompArchetype.PICK_OFF ->
            hero.has(Trait.HOOK) || hero.has(Trait.SUPPRESSION) ||
                (hero.has(Trait.LONG_RANGE_CC) && hero.attrs.pickPotential >= 8)

        CompArchetype.SUSTAIN -> hero.has(Trait.HEAVY_HEAL) || hero.attrs.sustain >= 8

        CompArchetype.GROUP_FIGHT -> hero.attrs.teamfight >= 9 && hero.attrs.crowdControl >= 6

        CompArchetype.SCALING ->
            hero.has(Trait.SCALING_CARRY) || hero.has(Trait.LATE_MONSTER) || hero.attrs.curve.late >= 9

        CompArchetype.SPLIT_PUSH -> hero.has(Trait.SPLIT_PUSH) || hero.has(Trait.ANTI_TOWER)

        CompArchetype.BALANCED -> false
    }

    private fun labelFor(archetype: CompArchetype): String = when (archetype) {
        CompArchetype.DIVE -> "Dive comp"
        CompArchetype.POKE -> "Poke comp"
        CompArchetype.PICK_OFF -> "Pick-off comp"
        CompArchetype.GROUP_FIGHT -> "Teamfight comp"
        CompArchetype.SCALING -> "Scaling comp"
        CompArchetype.SPLIT_PUSH -> "Split-push comp"
        CompArchetype.SUSTAIN -> "Sustain comp"
        CompArchetype.BALANCED -> "Balanced comp"
    }

    private fun summaryFor(archetype: CompArchetype, evidence: List<Hero>): String {
        val names = evidence.take(3).joinToString(", ") { it.name }
        return when (archetype) {
            CompArchetype.DIVE ->
                "$names want to jump your backline the moment someone is reachable."

            CompArchetype.POKE ->
                "$names want to chip you down from range and never take a straight fight."

            CompArchetype.PICK_OFF ->
                "$names want to catch one person out of position and win the 5v4."

            CompArchetype.GROUP_FIGHT ->
                "$names want everyone stacked together so their crowd control chains."

            CompArchetype.SCALING ->
                "$names want the game to go long — they are stronger every minute."

            CompArchetype.SPLIT_PUSH ->
                "$names want to trade towers on the side lanes instead of fighting."

            CompArchetype.SUSTAIN ->
                "$names want long fights they can heal through."

            CompArchetype.BALANCED -> ""
        }
    }

    private fun counterplayFor(archetype: CompArchetype): String = when (archetype) {
        CompArchetype.DIVE ->
            "Anti-dash crowd control beats this better than damage — Khufra, Minsitthar, " +
                "Phoveus, Chou. Stay near terrain and never be the closest target."

        CompArchetype.POKE ->
            "Do not walk down the lane at them. Move through fog and terrain, buy sustain, " +
                "and force the fight the moment you are in range."

        CompArchetype.PICK_OFF ->
            "Never move alone. Purify or Winter Crown on whoever they hunt, and keep " +
                "vision on the fog they want to hook from."

        CompArchetype.GROUP_FIGHT ->
            "Do not group into their setup. Split the map, make them come to you one lane " +
                "at a time, and save your own crowd control for their engage."

        CompArchetype.SCALING ->
            "Force objectives now. Every minute you let them farm is a minute you lose — " +
                "take Turtle and pressure towers before their items land."

        CompArchetype.SPLIT_PUSH ->
            "Track the split-pusher rather than chasing fights. Match the wave, and only " +
                "commit when you know where they are."

        CompArchetype.SUSTAIN ->
            "Anti-heal is mandatory and early, not optional. Then burst inside the healing " +
                "window rather than trading with them over time."

        CompArchetype.BALANCED -> ""
    }

    private fun balanced(heroes: List<Hero>, summary: String) = ArchetypeVerdict(
        archetype = CompArchetype.BALANCED,
        label = labelFor(CompArchetype.BALANCED),
        summary = summary,
        counterplay = "",
        evidence = emptyList(),
        confidence = 0.0,
    )

    private const val NO_PATTERN =
        "No single plan stands out — this draft can play several ways, so read the game."
}
