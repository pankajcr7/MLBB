package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.Lane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dataset is hand-authored, so a typo in a hero id would otherwise silently
 * produce a hero with no matchups. These tests make that a build failure instead.
 */
class DatasetIntegrityTest {
    private val db: HeroDatabase = DatasetLoader.fromResources()

    @Test
    fun `dataset loads and passes validation`() {
        assertEquals("Dataset validation errors: ${db.validate()}", emptyList<String>(), db.validate())
    }

    @Test
    fun `dataset has a usable number of heroes`() {
        assertTrue("Expected most of the roster, got ${db.size}", db.size >= 124)
    }

    /**
     * The roster gap, tracked deliberately rather than discovered later.
     *
     * These heroes are absent because I could not describe their kits accurately enough
     * to author attributes for them, and invented attributes are worse than a missing
     * hero — the engine would confidently recommend them for the wrong reasons.
     *
     * When you add one, delete it from this list. If a name here already exists in the
     * dataset the test fails, so the list cannot go stale silently.
     */
    @Test
    fun `the known roster gap is accurate`() {
        val knownMissing = listOf(
            "Kalea", "Lukas", "Marcel", "Obsidia", "Sora", "Suyou", "Zetian", "Zhuxin",
        )
        val stale = knownMissing.filter { name ->
            db.heroes.any { it.name.equals(name, ignoreCase = true) }
        }
        assertEquals("Already in the dataset — remove from knownMissing: $stale", emptyList<String>(), stale)
    }

    @Test
    fun `every lane has enough candidates to advise on`() {
        Lane.entries.forEach { lane ->
            val count = db.inLane(lane).size
            assertTrue("Lane $lane only has $count heroes", count >= 8)
        }
    }

    @Test
    fun `every hero is playable in at least one lane with a meta tier`() {
        val untiered = db.heroes.filter { it.tier.isEmpty() }.map { it.id }
        assertEquals("Heroes missing a tier: $untiered", emptyList<String>(), untiered)
    }

    @Test
    fun `counter and synergy edges all resolve to real heroes`() {
        val unknown = (
            db.counters.flatMap { listOf(it.hero, it.vs) } +
                db.synergies.flatMap { listOf(it.a, it.b) }
            ).filter { db.hero(it) == null }.distinct()
        assertEquals("Edges referencing unknown heroes: $unknown", emptyList<String>(), unknown)
    }

    @Test
    fun `matchup dataset is substantial enough to be useful`() {
        assertTrue("Only ${db.counters.size} counter edges", db.counters.size >= 100)
        assertTrue("Only ${db.synergies.size} synergy edges", db.synergies.size >= 40)
    }
}
