package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.report.ArchetypeAnalyzer
import com.pankaj.mlbbdraft.engine.report.CompArchetype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompArchetypeTest {
    private val db = DatasetLoader.fromResources()

    private fun classify(vararg ids: String) = ArchetypeAnalyzer.classify(ids.map { db.require(it) })

    @Test
    fun `a team of mobile assassins is a dive comp`() {
        val verdict = classify("ling", "fanny", "lancelot", "chou", "harith")
        assertEquals(CompArchetype.DIVE, verdict.archetype)
        assertTrue("Should name the divers", verdict.evidence.size >= 3)
        assertTrue(
            "Counterplay should mention anti-dash answers: ${verdict.counterplay}",
            verdict.counterplay.contains("Khufra") || verdict.counterplay.contains("anti-dash"),
        )
    }

    @Test
    fun `long-range chip damage is a poke comp`() {
        val verdict = classify("pharsa", "xavier", "beatrix", "lesley", "moskov")
        assertEquals(CompArchetype.POKE, verdict.archetype)
        assertTrue(
            "Counterplay should warn about walking at them: ${verdict.counterplay}",
            verdict.counterplay.contains("fog") || verdict.counterplay.contains("lane"),
        )
    }

    @Test
    fun `hook and suppression make a pick-off comp, not a dive comp`() {
        val verdict = classify("franco", "kaja", "eudora", "beatrix", "tigreal")
        assertEquals(CompArchetype.PICK_OFF, verdict.archetype)
        assertTrue(
            "Counterplay should say do not move alone: ${verdict.counterplay}",
            verdict.counterplay.contains("alone"),
        )
    }

    @Test
    fun `heavy healing is a sustain comp and demands anti-heal`() {
        val verdict = classify("estes", "uranus", "alucard", "esmeralda", "thamuz")
        assertEquals(CompArchetype.SUSTAIN, verdict.archetype)
        assertTrue(
            "Counterplay must demand anti-heal: ${verdict.counterplay}",
            verdict.counterplay.contains("Anti-heal"),
        )
    }

    @Test
    fun `late-game carries make a scaling comp`() {
        val verdict = classify("claude", "cecilion", "estes", "ixia", "karrie")
        assertTrue(
            "Expected SCALING or SUSTAIN for this comp, got ${verdict.archetype}",
            verdict.archetype in setOf(CompArchetype.SCALING, CompArchetype.SUSTAIN),
        )
    }

    @Test
    fun `too few picks has no identity yet`() {
        val verdict = classify("ling", "fanny")
        assertEquals(CompArchetype.BALANCED, verdict.archetype)
        assertTrue(!verdict.isDistinct)
        assertTrue(verdict.summary.contains("Too few"))
        assertEquals("", verdict.counterplay)
    }

    @Test
    fun `an empty board is balanced`() {
        val verdict = ArchetypeAnalyzer.classify(emptyList())
        assertEquals(CompArchetype.BALANCED, verdict.archetype)
        assertEquals(0.0, verdict.confidence, 0.0001)
    }

    @Test
    fun `every named archetype comes with evidence and counterplay`() {
        // Sweep real comps and assert any confident verdict is actually actionable.
        val comps = listOf(
            listOf("ling", "fanny", "lancelot", "chou", "harith"),
            listOf("pharsa", "xavier", "beatrix", "lesley", "moskov"),
            listOf("franco", "kaja", "eudora", "beatrix", "tigreal"),
            listOf("estes", "uranus", "alucard", "esmeralda", "thamuz"),
            listOf("atlas", "vale", "odette", "khufra", "yve"),
            listOf("hayabusa", "masha", "sun", "zilong", "aulus"),
        )
        comps.forEach { ids ->
            val verdict = ArchetypeAnalyzer.classify(ids.map { db.require(it) })
            if (verdict.isDistinct) {
                assertTrue("$ids -> ${verdict.archetype} had no evidence", verdict.evidence.isNotEmpty())
                assertTrue("$ids -> ${verdict.archetype} had no counterplay", verdict.counterplay.isNotBlank())
                assertTrue("$ids -> ${verdict.archetype} had no summary", verdict.summary.isNotBlank())
                assertTrue(verdict.confidence >= ArchetypeAnalyzer.THRESHOLD)
            }
        }
    }

    @Test
    fun `both sides of a real draft get read`() {
        val engine = DraftEngine(db)
        var state = com.pankaj.mlbbdraft.engine.model.DraftState.forMode(
            com.pankaj.mlbbdraft.engine.model.DraftMode.RANKED,
        )
        listOf("ling", "fanny", "lancelot").forEachIndexed { i, id ->
            state = state.withPick(
                com.pankaj.mlbbdraft.engine.model.Side.ENEMY,
                i,
                com.pankaj.mlbbdraft.engine.model.Pick(id),
            )
        }
        listOf("estes", "uranus", "esmeralda").forEachIndexed { i, id ->
            state = state.withPick(
                com.pankaj.mlbbdraft.engine.model.Side.ALLY,
                i,
                com.pankaj.mlbbdraft.engine.model.Pick(id),
            )
        }
        assertEquals(
            CompArchetype.DIVE,
            engine.archetype(state, com.pankaj.mlbbdraft.engine.model.Side.ENEMY).archetype,
        )
        assertEquals(
            CompArchetype.SUSTAIN,
            engine.archetype(state, com.pankaj.mlbbdraft.engine.model.Side.ALLY).archetype,
        )
    }
}
