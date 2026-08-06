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
    fun `ranked bans scale with rank`() {
        // 3, 4 or 5 per side depending on rank; 5 is the default.
        assertEquals(5, DraftFormats.defaultBanCount(DraftMode.RANKED))
        listOf(3, 4, 5).forEach { count ->
            val bans = DraftFormats.steps(DraftMode.RANKED, Side.ALLY, count)
                .filter { it.kind == StepKind.BAN }
            assertEquals("$count bans per side", count * 2, bans.size)
            assertEquals(count, bans.count { it.side == Side.ALLY })
            assertEquals(count, bans.count { it.side == Side.ENEMY })
            // Alternating, so neither side bans twice in a row.
            assertEquals(
                "Bans must alternate",
                List(count * 2) { if (it % 2 == 0) Side.ALLY else Side.ENEMY },
                bans.map { it.side },
            )
        }
    }

    @Test
    fun `changing the ban count keeps the bans that still fit`() {
        var state = DraftState.forMode(DraftMode.RANKED)
        assertEquals(5, state.bansPerSide)
        repeat(5) { i -> state = state.withBan(Side.ALLY, i, "ban$i") }

        val narrowed = state.withBansPerSide(3)
        assertEquals(3, narrowed.bansPerSide)
        assertEquals(listOf("ban0", "ban1", "ban2"), narrowed.allyBans)

        val widened = narrowed.withBansPerSide(5)
        assertEquals(listOf("ban0", "ban1", "ban2", null, null), widened.allyBans)
    }

    @Test
    fun `ranked draft is a 1-2-2-2-2-1 pick snake`() {
        val steps = DraftFormats.steps(DraftMode.RANKED, Side.ALLY)
        val bans = steps.filter { it.kind == StepKind.BAN }
        val picks = steps.filter { it.kind == StepKind.PICK }

        assertEquals(10, bans.size)
        assertEquals(10, picks.size)
        assertEquals(5, bans.count { it.side == Side.ALLY })
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
        assertEquals(DraftFormats.defaultBanCount(DraftMode.TOURNAMENT) - 1, maxBanSlot)
    }

    @Test
    fun `current step walks the draft in order`() {
        var state = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ALLY)
        assertEquals(StepKind.BAN, state.currentStep?.kind)
        assertEquals(Side.ALLY, state.currentStep?.side)

        repeat(state.bansPerSide) { i -> state = state.withBan(Side.ALLY, i, "ally-ban$i") }
        repeat(state.bansPerSide) { i -> state = state.withBan(Side.ENEMY, i, "enemy-ban$i") }
        assertEquals(StepKind.PICK, state.currentStep?.kind)
        assertEquals(Side.ALLY, state.currentStep?.side)
    }

    @Test
    fun `draft completes when every slot is filled`() {
        var state = DraftState.forMode(DraftMode.RANKED)
        repeat(state.bansPerSide) { i ->
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
        repeat(lastPick.bansPerSide) { i ->
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
