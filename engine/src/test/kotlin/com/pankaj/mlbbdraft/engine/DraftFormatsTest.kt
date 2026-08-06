package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.model.DraftFormats
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftFormatsTest {
    @Test
    fun `ranked draft is three bans each and a 1-2-2-2-2-1 pick snake`() {
        val steps = DraftFormats.steps(DraftMode.RANKED, Side.ALLY)
        val bans = steps.filter { it.kind == StepKind.BAN }
        val picks = steps.filter { it.kind == StepKind.PICK }

        assertEquals(6, bans.size)
        assertEquals(10, picks.size)
        assertEquals(3, bans.count { it.side == Side.ALLY })
        assertEquals(5, picks.count { it.side == Side.ALLY })

        val order = picks.map { it.side }
        assertEquals(
            listOf(
                Side.ALLY, Side.ENEMY, Side.ENEMY, Side.ALLY, Side.ALLY,
                Side.ENEMY, Side.ENEMY, Side.ALLY, Side.ALLY, Side.ENEMY,
            ),
            order,
        )
    }

    @Test
    fun `tournament draft has two ban phases and five picks each`() {
        val steps = DraftFormats.steps(DraftMode.TOURNAMENT, Side.ALLY)
        assertEquals(10, steps.count { it.kind == StepKind.BAN })
        assertEquals(10, steps.count { it.kind == StepKind.PICK })
        assertEquals(5, steps.count { it.kind == StepKind.BAN && it.side == Side.ENEMY })
        // Ban slots must stay inside the list the state allocates for the mode.
        val maxBanSlot = steps.filter { it.kind == StepKind.BAN }.maxOf { it.slot }
        assertEquals(DraftFormats.banCount(DraftMode.TOURNAMENT) - 1, maxBanSlot)
    }

    @Test
    fun `current step walks the draft in order`() {
        var state = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ALLY)
        assertEquals(StepKind.BAN, state.currentStep?.kind)
        assertEquals(Side.ALLY, state.currentStep?.side)

        repeat(3) { i -> state = state.withBan(Side.ALLY, i, "ling") }
        repeat(3) { i -> state = state.withBan(Side.ENEMY, i, "fanny") }
        assertEquals(StepKind.PICK, state.currentStep?.kind)
        assertEquals(Side.ALLY, state.currentStep?.side)
    }

    @Test
    fun `draft completes when every slot is filled`() {
        var state = DraftState.forMode(DraftMode.RANKED)
        repeat(3) { i ->
            state = state.withBan(Side.ALLY, i, "a$i").withBan(Side.ENEMY, i, "b$i")
        }
        repeat(5) { i ->
            state = state
                .withPick(Side.ALLY, i, Pick("ally$i", Lane.entries[i]))
                .withPick(Side.ENEMY, i, Pick("enemy$i", Lane.entries[i]))
        }
        assertNull(state.currentStep)
    }

    @Test
    fun `counter-pick exposure shrinks as the draft progresses`() {
        val firstPick = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ALLY)
        assertEquals(5, firstPick.enemyPicksAfterOurs)

        var lastPick = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ALLY)
        repeat(3) { i ->
            lastPick = lastPick.withBan(Side.ALLY, i, "a$i").withBan(Side.ENEMY, i, "b$i")
        }
        repeat(4) { i -> lastPick = lastPick.withPick(Side.ALLY, i, Pick("ally$i")) }
        repeat(5) { i -> lastPick = lastPick.withPick(Side.ENEMY, i, Pick("enemy$i")) }
        assertEquals(0, lastPick.enemyPicksAfterOurs)
    }

    @Test
    fun `classic mode has no bans`() {
        val state = DraftState.forMode(DraftMode.CLASSIC)
        assertEquals(0, state.allyBans.size)
        assertEquals(StepKind.PICK, state.currentStep?.kind)
    }
}
