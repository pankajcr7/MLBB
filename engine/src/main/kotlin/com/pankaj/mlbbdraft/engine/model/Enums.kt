package com.pankaj.mlbbdraft.engine.model

import kotlinx.serialization.Serializable

/** The five MLBB lanes. */
@Serializable
enum class Lane {
    EXP,
    MID,
    GOLD,
    ROAM,
    JUNGLE,
    ;

    val label: String
        get() = when (this) {
            EXP -> "EXP Lane"
            MID -> "Mid Lane"
            GOLD -> "Gold Lane"
            ROAM -> "Roam"
            JUNGLE -> "Jungle"
        }

    val shortLabel: String
        get() = when (this) {
            EXP -> "EXP"
            MID -> "MID"
            GOLD -> "GOLD"
            ROAM -> "ROAM"
            JUNGLE -> "JUNG"
        }
}

@Serializable
enum class Role {
    MARKSMAN,
    MAGE,
    ASSASSIN,
    FIGHTER,
    TANK,
    SUPPORT,
    ;

    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

@Serializable
enum class DamageType { PHYSICAL, MAGIC, TRUE, MIXED }

enum class Side {
    ALLY,
    ENEMY,
    ;

    val other: Side get() = if (this == ALLY) ENEMY else ALLY
}

enum class DraftMode {
    /** In-game ranked draft: 3 bans per side, picks 1-2-2-2-2-1. */
    RANKED,

    /** MPL / M-series style: 3 bans, picks 1-2-2, 2 more bans, picks 1-2-2. */
    TOURNAMENT,

    /** Classic / blind: no bans, both sides pick without seeing each other. */
    CLASSIC,
    ;

    val label: String
        get() = when (this) {
            RANKED -> "Ranked draft"
            TOURNAMENT -> "Tournament"
            CLASSIC -> "Classic (blind)"
        }
}

/**
 * Kit-level facts about a hero that drive heuristic counter reasoning.
 *
 * Traits exist so the engine can say something useful about a matchup that has no
 * hand-authored counter edge yet — which will be most matchups until the dataset
 * fills out. Add traits sparingly: a trait should describe something that changes
 * how a hero interacts with an *opponent*, not just that they are strong.
 */
@Serializable
enum class Trait {
    // --- mobility profile ---
    DASH_HEAVY,
    BLINK,
    HIGH_MOBILITY,
    IMMOBILE,

    // --- crowd control shape ---
    HOOK,
    SUPPRESSION,
    AREA_STUN,
    KNOCK_UP,
    LONG_RANGE_CC,

    // --- answers to other kits ---
    PUNISH_DASH,
    ANTI_BLINK,
    ANTI_BASIC_ATTACK,
    CC_IMMUNITY,
    PURIFY_KIT,
    ANTI_CC_TEAM,
    ANTI_HEAL_KIT,

    // --- sustain / defence ---
    HEAVY_HEAL,
    SHIELD_HEAVY,
    REVIVE,

    // --- damage shape ---
    PERCENT_HP_DAMAGE,
    TRUE_DAMAGE,
    ARMOR_SHRED,
    MAGIC_SHRED,
    BURST,
    POKE,
    EXECUTE,

    // --- macro / tempo ---
    SCALING_CARRY,
    EARLY_BULLY,
    LATE_MONSTER,
    SPLIT_PUSH,
    ANTI_TOWER,
    GLOBAL_PRESENCE,
    INVISIBILITY,
    SUMMONER,
    ZONE_CONTROL,
    BACKLINE_ACCESS,
    ;

    val label: String
        get() = name.split('_').joinToString(" ") { w -> w.lowercase() }
            .replaceFirstChar { it.uppercase() }
}

/** The scoring axes. Kept explicit so every suggestion can be explained. */
enum class Component {
    LANE_FIT,
    COUNTER,
    SYNERGY,
    COMP_NEED,
    META,
    MASTERY,
    EXPOSURE,
    ;

    val label: String
        get() = when (this) {
            LANE_FIT -> "Lane fit"
            COUNTER -> "Counters enemy"
            SYNERGY -> "Team synergy"
            COMP_NEED -> "Comp need"
            META -> "Meta strength"
            MASTERY -> "Your mastery"
            EXPOSURE -> "Counter-pick risk"
        }
}
