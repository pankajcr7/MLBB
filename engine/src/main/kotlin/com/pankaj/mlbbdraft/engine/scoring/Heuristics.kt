package com.pankaj.mlbbdraft.engine.scoring

import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Role
import com.pankaj.mlbbdraft.engine.model.Trait

/** One reason a matchup or pairing leans a particular way. */
data class HeuristicHit(
    val delta: Double,
    val note: String,
)

/**
 * Trait- and attribute-driven matchup reasoning.
 *
 * This is the fallback that lets the engine say something useful about the ~99% of
 * hero pairs that have no hand-authored edge. Every rule here describes an
 * interaction between two kits, so it generalises to heroes added later without
 * anyone writing a new edge.
 *
 * Only the candidate-favours direction needs a rule: the caller evaluates both
 * directions and takes the difference, so "Phoveus punishes dashes" automatically
 * makes Ling a worse pick into Phoveus.
 */
object CounterHeuristics {
    fun hits(candidate: Hero, enemy: Hero): List<HeuristicHit> = buildList {
        val c = candidate.attrs
        val e = enemy.attrs
        val mobileEnemy = enemy.has(Trait.DASH_HEAVY) || enemy.has(Trait.BLINK)

        if (candidate.has(Trait.PUNISH_DASH) && mobileEnemy) {
            add(
                HeuristicHit(
                    0.55,
                    "${enemy.name} lives on dashes, which is exactly what ${candidate.name} is built to punish",
                ),
            )
        }
        if (candidate.has(Trait.ANTI_BLINK) && mobileEnemy) {
            add(HeuristicHit(0.4, "${candidate.name} can block ${enemy.name}'s dash or blink outright"))
        }
        if ((candidate.has(Trait.PERCENT_HP_DAMAGE) || candidate.has(Trait.TRUE_DAMAGE)) && e.durability >= 8) {
            add(
                HeuristicHit(
                    0.4,
                    "${candidate.name} damages through defences, so ${enemy.name}'s health pool does not protect them",
                ),
            )
        }
        if (candidate.has(Trait.ANTI_HEAL_KIT) && (enemy.has(Trait.HEAVY_HEAL) || e.sustain >= 8)) {
            add(HeuristicHit(0.4, "${candidate.name} cuts the healing ${enemy.name} depends on"))
        }
        if (candidate.has(Trait.ARMOR_SHRED) && e.durability >= 7) {
            add(HeuristicHit(0.3, "${candidate.name} strips the defence ${enemy.name} is built around"))
        }
        if ((candidate.has(Trait.HOOK) || candidate.has(Trait.SUPPRESSION) || candidate.has(Trait.LONG_RANGE_CC)) &&
            (enemy.has(Trait.IMMOBILE) || e.mobility <= 3)
        ) {
            add(HeuristicHit(0.35, "${enemy.name} has no way out of ${candidate.name}'s lock-down"))
        }
        if (candidate.has(Trait.ANTI_CC_TEAM) && e.crowdControl >= 8) {
            add(HeuristicHit(0.35, "${candidate.name} cleanses the crowd control ${enemy.name} is drafted for"))
        }
        if (candidate.has(Trait.ANTI_BASIC_ATTACK) && Role.MARKSMAN in enemy.roles) {
            add(HeuristicHit(0.3, "${candidate.name} blanks ${enemy.name}'s basic attacks"))
        }
        if (c.burst >= 8 && e.durability <= 4 && e.mobility <= 5) {
            add(HeuristicHit(0.3, "${enemy.name} is squishy and slow enough for ${candidate.name} to delete"))
        }
        if (candidate.has(Trait.BACKLINE_ACCESS) && enemy.has(Trait.SCALING_CARRY) && e.mobility <= 5) {
            add(HeuristicHit(0.3, "${candidate.name} reaches ${enemy.name} before that scaling ever matters"))
        }
        if (c.range >= 8 && e.range <= 4 && e.mobility <= 5) {
            add(HeuristicHit(0.25, "${candidate.name} out-ranges ${enemy.name} and pokes for free"))
        }
        if (candidate.has(Trait.EARLY_BULLY) && e.curve.early <= 5) {
            add(HeuristicHit(0.25, "${enemy.name} is weak early, which is when ${candidate.name} is strongest"))
        }
        if (c.mobility >= 8 && e.crowdControl <= 3 && e.mobility <= 5) {
            add(HeuristicHit(0.2, "${enemy.name} has nothing to catch ${candidate.name} with"))
        }

        // Rules that make the candidate worse. Kept in the same list so both
        // directions of a matchup come out of one code path.
        if (candidate.has(Trait.IMMOBILE) && e.pickPotential >= 8) {
            add(
                HeuristicHit(
                    -0.35,
                    "${enemy.name} hunts immobile heroes and ${candidate.name} has no escape",
                ),
            )
        }
        if (c.durability <= 3 && e.burst >= 9) {
            add(HeuristicHit(-0.25, "${enemy.name}'s burst kills ${candidate.name} before they act"))
        }
    }

    fun score(candidate: Hero, enemy: Hero): Double =
        hits(candidate, enemy).sumOf { it.delta }.coerceIn(-1.0, 1.0)
}

/** Pairing reasoning: what makes two heroes better together than apart. */
object SynergyHeuristics {
    fun hits(candidate: Hero, ally: Hero): List<HeuristicHit> =
        oneWay(candidate, ally) + oneWay(ally, candidate) + mutual(candidate, ally)

    fun score(candidate: Hero, ally: Hero): Double =
        hits(candidate, ally).sumOf { it.delta }.coerceIn(-1.0, 1.0)

    /** [setup] provides something [payoff] cashes in. */
    private fun oneWay(setup: Hero, payoff: Hero): List<HeuristicHit> = buildList {
        if (setup.attrs.engage >= 8 && payoff.attrs.burst >= 8 && payoff.attrs.range >= 7) {
            add(HeuristicHit(0.3, "${setup.name} holds the enemy still for ${payoff.name}'s burst"))
        }
        if (setup.attrs.peel >= 7 &&
            (payoff.has(Trait.IMMOBILE) || payoff.attrs.mobility <= 3) &&
            payoff.attrs.sustainedDamage >= 8
        ) {
            add(HeuristicHit(0.3, "${setup.name} is the protection ${payoff.name} needs to deal damage at all"))
        }
        if (setup.has(Trait.HEAVY_HEAL) && payoff.attrs.sustainedDamage >= 8) {
            add(HeuristicHit(0.25, "${setup.name}'s healing turns ${payoff.name}'s long fights into won ones"))
        }
        if (setup.has(Trait.ANTI_CC_TEAM) && (payoff.has(Trait.IMMOBILE) || payoff.attrs.mobility <= 3)) {
            add(HeuristicHit(0.25, "${setup.name} cleanses the lock-down that would otherwise kill ${payoff.name}"))
        }
        if (setup.has(Trait.ANTI_HEAL_KIT) && payoff.has(Trait.PERCENT_HP_DAMAGE)) {
            add(HeuristicHit(0.2, "${setup.name}'s healing cut plus ${payoff.name}'s percent-HP damage answers any tank"))
        }
    }

    private fun mutual(a: Hero, b: Hero): List<HeuristicHit> = buildList {
        if ((a.has(Trait.IMMOBILE) || a.attrs.mobility <= 3) && (b.has(Trait.IMMOBILE) || b.attrs.mobility <= 3)) {
            add(
                HeuristicHit(
                    -0.25,
                    "${a.name} and ${b.name} are both immobile — a single engage catches them together",
                ),
            )
        }
        if (a.attrs.engage <= 3 && b.attrs.engage <= 3 && a.attrs.peel <= 4 && b.attrs.peel <= 4) {
            add(HeuristicHit(-0.15, "Neither ${a.name} nor ${b.name} can start or stop a fight"))
        }
    }
}
