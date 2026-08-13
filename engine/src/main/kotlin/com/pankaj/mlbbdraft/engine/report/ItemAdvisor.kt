package com.pankaj.mlbbdraft.engine.report

import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Role
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.Trait

/**
 * A build recommendation derived from the enemy composition.
 *
 * This is often more actionable than the pick advice itself: you can always buy the
 * right item, even when the draft went badly.
 */
data class ItemAdvice(
    val item: String,
    val reason: String,
    /** Who on your team should buy it. */
    val forWhom: String,
    /** 1..5, higher first. */
    val priority: Int,
)

object ItemAdvisor {
    fun advise(
        enemies: List<Hero>,
        allies: List<Hero> = emptyList(),
        confirmedBuildSignals: Set<EnemyBuildSignal> = emptySet(),
    ): List<ItemAdvice> {
        if (enemies.isEmpty()) return emptyList()

        val advice = mutableListOf<ItemAdvice>()
        val split = CompReportBuilder.build(side = Side.ENEMY, heroes = enemies).damage

        // Signals are intentionally explicit confirmations from the player, not a guess from
        // tiny or unlabelled item icons. They take priority over composition-only heuristics.
        if (EnemyBuildSignal.HEALING in confirmedBuildSignals) {
            advice += ItemAdvice(
                item = "Sea Halberd",
                reason = "Confirmed healing or lifesteal in their build — cut it before the next fight.",
                forWhom = "Physical damage heroes",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Necklace of Durance",
                reason = "Confirmed healing or lifesteal in their build — magic-side healing reduction.",
                forWhom = "Mages",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Dominance Ice",
                reason = "Confirmed healing build — reduce lifesteal from the tank / roam slot.",
                forWhom = "Tank / roam",
                priority = 5,
            )
        }
        if (EnemyBuildSignal.SHIELDS in confirmedBuildSignals) {
            advice += ItemAdvice(
                item = "Dominance Ice",
                reason = "Confirmed shield build — weaken shield value from the frontline slot.",
                forWhom = "Tank / roam",
                priority = 5,
            )
        }
        if (
            EnemyBuildSignal.ATTACK_SPEED in confirmedBuildSignals ||
            EnemyBuildSignal.CRITICAL_DAMAGE in confirmedBuildSignals
        ) {
            advice += ItemAdvice(
                item = "Blade Armor",
                reason = "Confirmed attack-speed or critical build — punish repeated basic attacks.",
                forWhom = "Frontline",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Dominance Ice",
                reason = "Confirmed attack-speed build — slow their damage pattern in every fight.",
                forWhom = "Tank / roam",
                priority = 4,
            )
        }
        if (EnemyBuildSignal.PHYSICAL_PENETRATION in confirmedBuildSignals) {
            advice += ItemAdvice(
                item = "Antique Cuirass",
                reason = "Confirmed physical penetration — reduce the damage of their ability users.",
                forWhom = "Anyone being focused",
                priority = 5,
            )
        }
        if (
            EnemyBuildSignal.MAGIC_BURST in confirmedBuildSignals ||
            EnemyBuildSignal.MAGIC_PENETRATION in confirmedBuildSignals
        ) {
            advice += ItemAdvice(
                item = "Athena's Shield",
                reason = "Confirmed magic burst or penetration — block the burst window before it lands.",
                forWhom = "Frontline and targeted carries",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Radiant Armor",
                reason = "Confirmed magic build — stack resistance during sustained spell damage.",
                forWhom = "Everyone who takes damage",
                priority = 4,
            )
        }
        if (EnemyBuildSignal.ARMOR in confirmedBuildSignals) {
            advice += ItemAdvice(
                item = "Malefic Roar",
                reason = "Confirmed armour stack — percentage armour penetration keeps physical damage relevant.",
                forWhom = "Physical damage heroes",
                priority = 5,
            )
        }
        if (EnemyBuildSignal.MAGIC_RESIST in confirmedBuildSignals) {
            advice += ItemAdvice(
                item = "Divine Glaive",
                reason = "Confirmed magic-resist stack — percentage magic penetration answers it.",
                forWhom = "Mages",
                priority = 5,
            )
        }
        if (EnemyBuildSignal.HIGH_HEALTH in confirmedBuildSignals) {
            advice += ItemAdvice(
                item = "Demon Hunter Sword",
                reason = "Confirmed high-health build — percent-HP damage scales with their investment.",
                forWhom = "Marksman",
                priority = 5,
            )
        }

        val healers = enemies.filter { it.has(Trait.HEAVY_HEAL) || it.attrs.sustain >= 8 }
        if (healers.isNotEmpty()) {
            val names = healers.joinToString(", ") { it.name }
            advice += ItemAdvice(
                item = "Sea Halberd",
                reason = "Cuts the healing on $names. Build it before the second Lord, not after.",
                forWhom = "Physical damage heroes",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Necklace of Durance",
                reason = "Magic-side healing reduction against $names.",
                forWhom = "Mages",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Dominance Ice",
                reason = "Reduces enemy lifesteal and shields — covers $names from the tank slot.",
                forWhom = "Tank / roam",
                priority = 4,
            )
        }

        val shielders = enemies.filter { it.has(Trait.SHIELD_HEAVY) }
        if (shielders.isNotEmpty() && healers.none { it in shielders }) {
            advice += ItemAdvice(
                item = "Dominance Ice",
                reason = "${shielders.joinToString(", ") { it.name }} rely on shields, which it weakens.",
                forWhom = "Tank / roam",
                priority = 4,
            )
        }

        if (split.physical >= 0.6) {
            advice += ItemAdvice(
                item = "Antique Cuirass",
                reason = "Enemy damage is ${percent(split.physical)} physical — stacking its debuff blunts them.",
                forWhom = "Everyone who takes damage",
                priority = 4,
            )
            advice += ItemAdvice(
                item = "Blade Armor",
                reason = "Reflects damage back at their physical carries.",
                forWhom = "Frontline",
                priority = 3,
            )
            advice += ItemAdvice(
                item = "Warrior Boots",
                reason = "Cheapest physical defence in a physical-heavy game.",
                forWhom = "Everyone",
                priority = 3,
            )
        }

        if (split.magic >= 0.6) {
            advice += ItemAdvice(
                item = "Radiant Armor",
                reason = "Enemy damage is ${percent(split.magic)} magic — the stacking resist scales into their whole comp.",
                forWhom = "Everyone who takes damage",
                priority = 4,
            )
            advice += ItemAdvice(
                item = "Athena's Shield",
                reason = "Periodic shield against repeated magic burst.",
                forWhom = "Frontline and fighters",
                priority = 3,
            )
            advice += ItemAdvice(
                item = "Tough Boots",
                reason = "Magic defence plus crowd-control reduction.",
                forWhom = "Everyone",
                priority = 3,
            )
        }

        val tanks = enemies.filter { it.attrs.durability >= 8 }
        if (tanks.size >= 2) {
            val names = tanks.joinToString(", ") { it.name }
            advice += ItemAdvice(
                item = "Malefic Roar",
                reason = "Percentage armour penetration for cutting through $names.",
                forWhom = "Physical damage heroes",
                priority = 4,
            )
            advice += ItemAdvice(
                item = "Demon Hunter Sword",
                reason = "Percent-HP damage on basic attacks — scales with how tanky they build.",
                forWhom = "Marksman",
                priority = 4,
            )
            advice += ItemAdvice(
                item = "Divine Glaive",
                reason = "Percentage magic penetration once they buy magic resist for $names.",
                forWhom = "Mages",
                priority = 3,
            )
        }

        val ccTotal = enemies.sumOf { it.attrs.crowdControl }
        val lockdown = enemies.filter {
            it.has(Trait.SUPPRESSION) || it.has(Trait.HOOK) || it.attrs.crowdControl >= 9
        }
        if (ccTotal >= 35 || lockdown.isNotEmpty()) {
            val names = lockdown.joinToString(", ") { it.name }.ifEmpty { "their comp" }
            advice += ItemAdvice(
                item = "Purify (battle spell)",
                reason = "Breaks the lock-down from $names. On a carry this is worth more than Flicker.",
                forWhom = "Carry without an escape",
                priority = 5,
            )
            advice += ItemAdvice(
                item = "Winter Crown",
                reason = "Freezes you out of a suppression or burst chain from $names.",
                forWhom = "Squishy carries",
                priority = 4,
            )
            advice += ItemAdvice(
                item = "Tough Boots",
                reason = "Crowd-control duration reduction against a high-CC draft.",
                forWhom = "Everyone",
                priority = 3,
            )
        }

        val bursters = enemies.filter { it.attrs.burst >= 9 }
        if (bursters.isNotEmpty()) {
            advice += ItemAdvice(
                item = "Athena's Shield",
                reason = "Blunts repeated burst from ${bursters.joinToString(", ") { it.name }}.",
                forWhom = "Anyone being targeted",
                priority = 3,
            )
        }

        val enemyMarksmen = enemies.filter { Role.MARKSMAN in it.roles }
        if (enemyMarksmen.isNotEmpty()) {
            advice += ItemAdvice(
                item = "Wind of Nature",
                reason = "Physical damage immunity to survive ${enemyMarksmen.joinToString(", ") { it.name }} in a fight.",
                forWhom = "Fighters and physical carries",
                priority = 3,
            )
        }

        val allyFragileCarry = allies.any {
            maxOf(it.attrs.burst, it.attrs.sustainedDamage) >= 8 && it.attrs.durability <= 4
        }
        if (allyFragileCarry && enemies.any { it.attrs.pickPotential >= 9 }) {
            advice += ItemAdvice(
                item = "Immortality",
                reason = "They have pick-off threats that will find your carry at least once per game.",
                forWhom = "Your carry",
                priority = 3,
            )
        }

        return advice
            .distinctBy { it.item to it.forWhom }
            .sortedWith(compareByDescending<ItemAdvice> { it.priority }.thenBy { it.item })
    }

    private fun percent(share: Double): String = "${(share * 100).toInt()}%"
}
