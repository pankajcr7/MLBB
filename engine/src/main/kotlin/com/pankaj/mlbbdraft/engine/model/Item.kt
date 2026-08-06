package com.pankaj.mlbbdraft.engine.model

import kotlinx.serialization.Serializable

@Serializable
enum class ItemCategory {
    ATTACK,
    MAGIC,
    DEFENSE,
    MOVEMENT,
    SPELL,
    ;

    val label: String
        get() = when (this) {
            ATTACK -> "Attack"
            MAGIC -> "Magic"
            DEFENSE -> "Defense"
            MOVEMENT -> "Boots"
            SPELL -> "Battle spell"
        }
}

/**
 * What an item *does*, at the level the advisor reasons about.
 *
 * Tags rather than raw stat numbers on purpose: the recommendation "buy anti-heal
 * against Estes" depends on the item cutting healing, not on whether it gives 60 or 65
 * attack. Stat numbers also change every patch; these tags almost never do.
 */
@Serializable
enum class ItemTag {
    // Offence
    PHYSICAL_ATTACK,
    MAGIC_POWER,
    ATTACK_SPEED,
    CRIT,
    LIFESTEAL,
    SPELL_VAMP,
    ARMOR_PEN,
    MAGIC_PEN,
    PERCENT_HP_DAMAGE,
    TRUE_DAMAGE,

    // Utility
    COOLDOWN,
    MANA,
    MOVEMENT,
    CC_REDUCTION,

    // Defence
    PHYSICAL_DEFENSE,
    MAGIC_DEFENSE,
    HP,
    SHIELD,
    ANTI_BURST,
    ANTI_CRIT,
    DAMAGE_REFLECT,
    PHYSICAL_IMMUNE,
    CC_IMMUNE,
    REVIVE,

    // Answers to specific enemy kits
    ANTI_HEAL,
    ANTI_SHIELD,
    ANTI_ATTACK_SPEED,

    // Support
    HEAL,
    TEAM_BUFF,
    ;

    val label: String
        get() = name.split('_').joinToString(" ") { it.lowercase() }
            .replaceFirstChar { it.uppercase() }
}

@Serializable
data class Item(
    /** Slug. Must match the icon file at `assets/items/<id>.webp`. */
    val id: String,
    val name: String,
    val category: ItemCategory,
    /** Approximate gold cost — indicative for build order, not patch-exact. */
    val cost: Int = 0,
    val tags: Set<ItemTag> = emptySet(),
    /** Roles that normally buy this. Empty means anyone can. */
    val roles: Set<Role> = emptySet(),
    /** One line on the effect that makes it worth recommending. */
    val summary: String,
) {
    fun has(tag: ItemTag): Boolean = tag in tags

    fun buildableBy(hero: Hero): Boolean {
        if (roles.isNotEmpty() && hero.roles.none { it in roles }) return false
        return when (category) {
            ItemCategory.ATTACK -> hero.damageType != DamageType.MAGIC
            ItemCategory.MAGIC -> hero.damageType != DamageType.PHYSICAL
            ItemCategory.DEFENSE, ItemCategory.MOVEMENT, ItemCategory.SPELL -> true
        }
    }
}

@Serializable
data class ItemFile(
    val items: List<Item>,
)
