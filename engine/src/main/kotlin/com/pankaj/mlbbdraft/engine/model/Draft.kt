package com.pankaj.mlbbdraft.engine.model

data class Pick(
    val heroId: String,
    val lane: Lane? = null,
)

/**
 * What the user actually owns and can play. Without this, suggestions are advice
 * for somebody else's account.
 */
data class PlayerProfile(
    val owned: Set<String> = emptySet(),
    /** heroId -> comfort 0..5. 5 = signature hero. */
    val comfort: Map<String, Int> = emptyMap(),
    /** When true, never suggest a hero outside [owned]. */
    val restrictToOwned: Boolean = false,
) {
    val isConfigured: Boolean get() = owned.isNotEmpty() || comfort.isNotEmpty()

    fun comfortOf(heroId: String): Int = comfort[heroId] ?: if (heroId in owned) 2 else 0

    fun canPlay(heroId: String): Boolean = !restrictToOwned || heroId in owned
}

enum class StepKind { BAN, PICK }

/** One action in the draft, in the order it happens. */
data class DraftStep(
    val ordinal: Int,
    val side: Side,
    val kind: StepKind,
    /** Index into that side's ban or pick list. */
    val slot: Int,
)

data class DraftState(
    val mode: DraftMode = DraftMode.RANKED,
    /** Which side bans and picks first. */
    val firstPick: Side = Side.ALLY,
    val allyBans: List<String?> = List(DraftFormats.banCount(DraftMode.RANKED)) { null },
    val enemyBans: List<String?> = List(DraftFormats.banCount(DraftMode.RANKED)) { null },
    val allyPicks: List<Pick?> = List(TEAM_SIZE) { null },
    val enemyPicks: List<Pick?> = List(TEAM_SIZE) { null },
    /** The lane the user is drafting for. Null = advise all lanes. */
    val myLane: Lane? = null,
    val profile: PlayerProfile = PlayerProfile(),
) {
    fun bans(side: Side): List<String?> = if (side == Side.ALLY) allyBans else enemyBans

    fun pickSlots(side: Side): List<Pick?> = if (side == Side.ALLY) allyPicks else enemyPicks

    fun picks(side: Side): List<Pick> = pickSlots(side).filterNotNull()

    fun heroIds(side: Side): List<String> = picks(side).map { it.heroId }

    fun remainingPicks(side: Side): Int = pickSlots(side).count { it == null }

    fun lanesTaken(side: Side): Set<Lane> = picks(side).mapNotNull { it.lane }.toSet()

    fun lanesOpen(side: Side): List<Lane> = Lane.entries.filter { it !in lanesTaken(side) }

    /** Every hero already banned or picked by either side — all unavailable. */
    val usedHeroIds: Set<String>
        get() = buildSet {
            addAll(allyBans.filterNotNull())
            addAll(enemyBans.filterNotNull())
            addAll(allyPicks.filterNotNull().map { it.heroId })
            addAll(enemyPicks.filterNotNull().map { it.heroId })
        }

    val steps: List<DraftStep> get() = DraftFormats.steps(mode, firstPick)

    /** The next unfilled action in draft order, or null when the draft is complete. */
    val currentStep: DraftStep?
        get() = steps.firstOrNull { isEmpty(it) }

    /**
     * How many picks the enemy still makes *after* our next pick. Drives counter-pick
     * risk: as first pick you are exposed to five answers, as last pick to none.
     *
     * Counts empty slots rather than positions in the draft order, because a
     * manually-entered board gets filled out of order — the user types in whatever
     * they can see on screen.
     */
    val enemyPicksAfterOurs: Int
        get() {
            val remaining = steps.dropWhile { it != currentStep }
            val ourNext = remaining.firstOrNull {
                it.kind == StepKind.PICK && it.side == Side.ALLY && isEmpty(it)
            } ?: return 0
            return remaining
                .dropWhile { it != ourNext }
                .drop(1)
                .count { it.kind == StepKind.PICK && it.side == Side.ENEMY && isEmpty(it) }
        }

    private fun isEmpty(step: DraftStep): Boolean = when (step.kind) {
        StepKind.BAN -> bans(step.side).getOrNull(step.slot) == null
        StepKind.PICK -> pickSlots(step.side).getOrNull(step.slot) == null
    }

    fun withBan(side: Side, slot: Int, heroId: String?): DraftState {
        val updated = bans(side).toMutableList().also { it[slot] = heroId }
        return if (side == Side.ALLY) copy(allyBans = updated) else copy(enemyBans = updated)
    }

    fun withPick(side: Side, slot: Int, pick: Pick?): DraftState {
        val updated = pickSlots(side).toMutableList().also { it[slot] = pick }
        return if (side == Side.ALLY) copy(allyPicks = updated) else copy(enemyPicks = updated)
    }

    /** Clears every ban and pick but keeps mode, side, lane and profile. */
    fun cleared(): DraftState = forMode(mode, firstPick).copy(myLane = myLane, profile = profile)

    companion object {
        const val TEAM_SIZE = 5

        fun forMode(mode: DraftMode, firstPick: Side = Side.ALLY): DraftState {
            val bans = DraftFormats.banCount(mode)
            return DraftState(
                mode = mode,
                firstPick = firstPick,
                allyBans = List(bans) { null },
                enemyBans = List(bans) { null },
            )
        }
    }
}

object DraftFormats {
    fun banCount(mode: DraftMode): Int = when (mode) {
        DraftMode.RANKED -> 3
        DraftMode.TOURNAMENT -> 5
        DraftMode.CLASSIC -> 0
    }

    fun steps(mode: DraftMode, firstPick: Side): List<DraftStep> {
        val builder = StepBuilder(firstPick)
        when (mode) {
            DraftMode.RANKED -> {
                builder.bans(1, 1, 1, 1, 1, 1)
                builder.picks(1, 2, 2, 2, 2, 1)
            }

            DraftMode.TOURNAMENT -> {
                builder.bans(1, 1, 1, 1, 1, 1)
                builder.picks(1, 2, 2)
                builder.bans(1, 1, 1, 1)
                builder.picks(1, 2, 2)
            }

            DraftMode.CLASSIC -> {
                builder.picks(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
            }
        }
        return builder.build()
    }

    /**
     * Turns an alternating "how many in a row" pattern into concrete steps.
     * `picks(1, 2, 2, 2, 2, 1)` starting with ALLY means ally takes 1, enemy takes 2,
     * ally takes 2, and so on — the standard snake order.
     */
    private class StepBuilder(private val firstSide: Side) {
        private val steps = mutableListOf<DraftStep>()
        private val banSlots = mutableMapOf<Side, Int>()
        private val pickSlots = mutableMapOf<Side, Int>()
        private var turn = 0

        fun bans(vararg counts: Int) = add(StepKind.BAN, counts)

        fun picks(vararg counts: Int) = add(StepKind.PICK, counts)

        private fun add(kind: StepKind, counts: IntArray) {
            for (count in counts) {
                val side = if (turn % 2 == 0) firstSide else firstSide.other
                repeat(count) {
                    val slots = if (kind == StepKind.BAN) banSlots else pickSlots
                    val slot = slots.getOrDefault(side, 0)
                    slots[side] = slot + 1
                    steps += DraftStep(steps.size, side, kind, slot)
                }
                turn++
            }
        }

        fun build(): List<DraftStep> = steps.toList()
    }
}
