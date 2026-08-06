package com.pankaj.mlbbdraft.engine.model

import kotlinx.serialization.Serializable

/**
 * How strong a hero is at each stage of the game, 0..10.
 *
 * Used both for the power-curve graph and for tempo reasoning
 * ("their comp wins the first 8 minutes, so don't draft double late-game").
 */
@Serializable
data class PowerCurve(
    val early: Int,
    val mid: Int,
    val late: Int,
) {
    init {
        require(listOf(early, mid, late).all { it in 0..10 }) {
            "PowerCurve values must be 0..10, got $this"
        }
    }
}

/**
 * Hero capabilities on a 0..10 scale.
 *
 * These are expert estimates, not scraped statistics — they encode "what does this
 * hero *do* for a team", which is what drafting actually turns on. Treat 5 as
 * average for a hero of that role, not average across all heroes: a tank with
 * `burst = 5` still does far less damage than a mage with `burst = 5`.
 */
@Serializable
data class HeroAttrs(
    /** Effective HP under pressure — tankiness plus defensive kit. */
    val durability: Int,
    /** Damage delivered in a short window. */
    val burst: Int,
    /** Damage delivered over a long fight. */
    val sustainedDamage: Int,
    /** Amount and reliability of hard CC. */
    val crowdControl: Int,
    /** Ability to reposition: dashes, blinks, speed-ups. */
    val mobility: Int,
    /** Speed of clearing minion waves and jungle camps. */
    val waveclear: Int,
    /** Ability to start a fight on the enemy's terms being broken. */
    val engage: Int,
    /** Ability to protect a teammate: peel, shields, heals, disengage. */
    val peel: Int,
    /** Healing / regeneration the hero brings (drives anti-heal advice). */
    val sustain: Int,
    /** Damage to Turtle / Lord and to towers. */
    val objectiveDamage: Int,
    /** Ability to delete or remove one target before a fight starts. */
    val pickPotential: Int,
    /** Value in a 5v5 grouped fight. */
    val teamfight: Int,
    /** Effective threat range, 0 = melee, 10 = screen-wide. */
    val range: Int,
    val curve: PowerCurve,
) {
    init {
        val scaled = listOf(
            durability, burst, sustainedDamage, crowdControl, mobility, waveclear,
            engage, peel, sustain, objectiveDamage, pickPotential, teamfight, range,
        )
        require(scaled.all { it in 0..10 }) { "HeroAttrs values must be 0..10, got $this" }
    }
}

@Serializable
data class Hero(
    /** Stable kebab-case slug. Never change it — matchup edges reference it. */
    val id: String,
    val name: String,
    val roles: Set<Role>,
    /** Lanes this hero is actually played in, not lanes they could theoretically fill. */
    val lanes: Set<Lane>,
    val damageType: DamageType,
    val attrs: HeroAttrs,
    val traits: Set<Trait> = emptySet(),
    /**
     * Meta strength 0..10 per lane for the patch this dataset describes.
     * Absent lane = playable but not a meta pick there.
     */
    val tier: Map<Lane, Double> = emptyMap(),
    /** Mechanical difficulty 1..5. Feeds a soft penalty when you have no mastery data. */
    val difficulty: Int = 3,
    val notes: String? = null,
) {
    val primaryRole: Role get() = roles.first()

    fun tierIn(lane: Lane?): Double? = if (lane == null) tier.values.maxOrNull() else tier[lane]

    fun has(trait: Trait): Boolean = trait in traits
}
