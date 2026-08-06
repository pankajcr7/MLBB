package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.report.PickVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickAssessmentTest {
    private val db = DatasetLoader.fromResources()
    private val engine = DraftEngine(db)

    private fun draft(
        allies: List<Pair<String, Lane>> = emptyList(),
        enemies: List<Pair<String, Lane>> = emptyList(),
    ): DraftState {
        var s = DraftState.forMode(DraftMode.RANKED)
        allies.forEachIndexed { i, (id, lane) -> s = s.withPick(Side.ALLY, i, Pick(id, lane)) }
        enemies.forEachIndexed { i, (id, lane) -> s = s.withPick(Side.ENEMY, i, Pick(id, lane)) }
        return s
    }

    @Test
    fun `nothing is judged before the enemy has picked`() {
        val state = draft(allies = listOf("ling" to Lane.JUNGLE))
        assertEquals(emptyList<Any>(), engine.assessPicks(state))
    }

    @Test
    fun `a hard-countered ally pick is flagged with the hero responsible`() {
        // Ling into Phoveus and Khufra is the textbook punished pick.
        val state = draft(
            allies = listOf("ling" to Lane.JUNGLE),
            enemies = listOf("phoveus" to Lane.EXP, "khufra" to Lane.ROAM),
        )
        val warnings = engine.pickWarnings(state)

        assertTrue("Expected Ling to be flagged, got $warnings", warnings.any { it.hero.id == "ling" })
        val ling = warnings.first { it.hero.id == "ling" }
        assertEquals(PickVerdict.BAD, ling.verdict)
        assertTrue(
            "Problems should name the counter: ${ling.problems}",
            ling.problems.any { it.contains("Phoveus") || it.contains("Khufra") },
        )
        assertTrue("A flagged pick needs actionable advice", ling.advice != null)
        assertTrue("Matchup should be negative, got ${ling.matchup}", ling.matchup < 0)
    }

    @Test
    fun `a good pick is not flagged`() {
        val state = draft(
            allies = listOf("phoveus" to Lane.EXP),
            enemies = listOf("ling" to Lane.JUNGLE, "harith" to Lane.MID),
        )
        assertEquals(emptyList<Any>(), engine.pickWarnings(state))

        val phoveus = engine.assessPicks(state).single()
        assertEquals(PickVerdict.STRONG, phoveus.verdict)
        assertTrue(phoveus.problems.isEmpty())
    }

    @Test
    fun `flat damage into a double frontline is called out`() {
        val state = draft(
            allies = listOf("layla" to Lane.GOLD),
            enemies = listOf("hylos" to Lane.ROAM, "uranus" to Lane.EXP),
        )
        val layla = engine.assessPicks(state).single { it.hero.id == "layla" }
        assertTrue(
            "Expected a penetration warning: ${layla.problems}",
            layla.problems.any { it.contains("frontliners") },
        )
        assertTrue(layla.isProblem)
    }

    @Test
    fun `an immobile carry against pick-off threats is warned`() {
        val state = draft(
            allies = listOf("ixia" to Lane.GOLD),
            enemies = listOf("franco" to Lane.ROAM, "kaja" to Lane.EXP),
        )
        val ixia = engine.assessPicks(state).single()
        assertTrue(
            "Expected an escape warning: ${ixia.problems}",
            ixia.problems.any { it.contains("escape") || it.contains("Franco") || it.contains("Kaja") },
        )
        assertNotNull(ixia.advice)
    }

    @Test
    fun `off-role picks are noticed`() {
        val state = draft(
            allies = listOf("estes" to Lane.JUNGLE),
            enemies = listOf("ling" to Lane.JUNGLE, "melissa" to Lane.GOLD),
        )
        val estes = engine.assessPicks(state).single()
        assertTrue(
            "Expected an off-role note: ${estes.problems}",
            estes.problems.any { it.contains("not normally played") },
        )
    }

    @Test
    fun `worst picks are listed first`() {
        val state = draft(
            allies = listOf(
                "phoveus" to Lane.EXP,
                "ling" to Lane.JUNGLE,
                "khufra" to Lane.ROAM,
            ),
            enemies = listOf("phoveus" to Lane.MID, "khufra" to Lane.GOLD).let {
                // Enemy Phoveus/Khufra would be duplicates; use their real counters instead.
                listOf("minsitthar" to Lane.EXP, "saber" to Lane.JUNGLE)
            },
        )
        val all = engine.assessPicks(state)
        assertTrue("Expected every ally pick assessed", all.size == 3)
        val problems = all.takeWhile { it.isProblem }
        // Problems, if any, must come before the clean picks.
        assertTrue(
            "Problem picks must sort first: ${all.map { it.hero.id to it.verdict }}",
            all.drop(problems.size).none { it.isProblem },
        )
    }
}
