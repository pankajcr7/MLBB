package com.pankaj.mlbbdraft.engine.model

/**
 * A property confirmed from a readable enemy item name or by explicit player input.
 * These remain separate from icon artwork so recommendations never rely on a visual guess.
 */
enum class EnemyBuildSignal(
    val label: String,
    val shortLabel: String,
) {
    HEALING("Healing / lifesteal", "Healing"),
    SHIELDS("Shields", "Shields"),
    ATTACK_SPEED("Attack speed", "AS"),
    CRITICAL_DAMAGE("Critical damage", "Crit"),
    PHYSICAL_PENETRATION("Physical penetration", "Phys pen"),
    MAGIC_BURST("Magic burst", "Magic burst"),
    MAGIC_PENETRATION("Magic penetration", "Magic pen"),
    ARMOR("Armor", "Armor"),
    MAGIC_RESIST("Magic resistance", "Magic resist"),
    HIGH_HEALTH("High health", "High HP"),
}
