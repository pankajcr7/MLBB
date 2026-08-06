package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.ItemTag
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Side
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildAdvisorTest {
    private val db = DatasetLoader.fromResources()
    private val engine = DraftEngine(db)

    private fun state(vararg enemies: Pair<String, Lane>): DraftState {
        var s = DraftState.forMode(DraftMode.RANKED)
        enemies.forEachIndexed { index, (id, lane) ->
            s = s.withPick(Side.ENEMY, index, Pick(id, lane))
        }
        return s
    }

    @Test
    fun `item catalog loads with icons and summaries`() {
        assertTrue("Expected a full item catalog, got ${db.items.size}", db.items.size >= 55)
        assertEquals(emptyList<String>(), db.validate())
        assertNotNull(db.item("blade-of-despair"))
        assertNotNull(db.item("necklace-of-durance"))
    }

    /**
     * The repo ships one icon per item. A missing file would render as a blank tile in
     * the app, which is the kind of bug nobody notices until a user screenshots it.
     */
    @Test
    fun `every item has a bundled icon`() {
        val assets = File("../app/src/main/assets/items")
        assertTrue("Item asset directory not found at ${assets.absolutePath}", assets.isDirectory)
        val missing = db.items.map { it.id }.filter { !File(assets, "$it.webp").isFile }
        assertEquals("Items with no icon: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `Odette into a healer comp is told to buy magic anti-heal`() {
        val odette = db.require("odette")
        val build = engine.buildFor(
            odette,
            state("estes" to Lane.ROAM, "uranus" to Lane.EXP, "claude" to Lane.GOLD),
            Lane.MID,
        )

        val situational = build.situational.map { it.item.id }
        assertTrue(
            "Expected magic anti-heal for a mage, got $situational",
            "necklace-of-durance" in situational,
        )
        assertTrue(
            "Anti-heal should make the final six",
            build.order.any { it.item.has(ItemTag.ANTI_HEAL) },
        )
        assertTrue(
            "Reason should name the healer: ${build.situational.map { it.reason }}",
            build.situational.any { it.reason.contains("Estes") },
        )
        assertEquals("Mage emblem", build.emblem.emblem)
    }

    @Test
    fun `Odette never gets recommended physical items`() {
        val build = engine.buildFor(
            db.require("odette"),
            state("hylos" to Lane.ROAM, "uranus" to Lane.EXP),
            Lane.MID,
        )
        val illegal = build.order.filter { !it.item.buildableBy(db.require("odette")) }
        assertEquals("Un-buildable items in build: ${illegal.map { it.item.id }}", emptyList<String>(), illegal)
        assertTrue(
            "Magic penetration is the answer to double frontline: ${build.order.map { it.item.id }}",
            build.order.any { it.item.has(ItemTag.MAGIC_PEN) },
        )
    }

    @Test
    fun `marksman into double tank gets percent-HP damage`() {
        val build = engine.buildFor(
            db.require("melissa"),
            state("hylos" to Lane.ROAM, "uranus" to Lane.EXP),
            Lane.GOLD,
        )
        assertTrue(
            "Expected percent-HP damage: ${build.order.map { it.item.id }}",
            build.order.any { it.item.has(ItemTag.PERCENT_HP_DAMAGE) },
        )
    }

    @Test
    fun `heavy enemy crowd control forces Tough Boots`() {
        val build = engine.buildFor(
            db.require("melissa"),
            state("franco" to Lane.ROAM, "atlas" to Lane.EXP, "eudora" to Lane.MID),
            Lane.GOLD,
        )
        assertEquals("tough-boots", build.boots?.item?.id)
        assertTrue(
            "Boots reason should name the threat: ${build.boots?.reason}",
            build.boots?.reason?.contains("Franco") == true,
        )
    }

    @Test
    fun `melee diver into a marksman is offered physical immunity`() {
        val build = engine.buildFor(
            db.require("chou"),
            state("melissa" to Lane.GOLD, "beatrix" to Lane.MID),
            Lane.ROAM,
        )
        assertTrue(
            "Expected Wind of Nature: ${build.order.map { it.item.id }}",
            build.order.any { it.item.id == "wind-of-nature" },
        )
    }

    @Test
    fun `jungler is told to take Retribution`() {
        val build = engine.buildFor(db.require("lancelot"), state(), Lane.JUNGLE)
        assertTrue(
            "Expected Retribution for a jungler: ${build.spells.map { it.item.id }}",
            build.spells.any { it.item.id == "retribution" },
        )
    }

    @Test
    fun `a low-mobility carry facing suppression is told to take Purify`() {
        val build = engine.buildFor(
            db.require("ixia"),
            state("kaja" to Lane.ROAM, "franco" to Lane.EXP),
            Lane.GOLD,
        )
        assertTrue(
            "Expected Purify: ${build.spells.map { it.item.id }}",
            build.spells.any { it.item.id == "purify" },
        )
    }

    @Test
    fun `build is always six slots or fewer and free of duplicates`() {
        db.heroes.forEach { hero ->
            val build = engine.buildFor(
                hero,
                state("estes" to Lane.ROAM, "hylos" to Lane.EXP, "ling" to Lane.JUNGLE, "melissa" to Lane.GOLD),
                hero.lanes.first(),
            )
            val ids = build.order.map { it.item.id }
            assertTrue("${hero.id} got ${ids.size} items", ids.size <= 6)
            assertEquals("${hero.id} has duplicate items: $ids", ids.distinct(), ids)
            assertTrue("${hero.id} got no items at all", ids.isNotEmpty())
            build.order.forEach {
                assertTrue("${hero.id} cannot build ${it.item.id}", it.item.buildableBy(hero))
            }
        }
    }

    @Test
    fun `builds are produced for every hero on your side of the board`() {
        val state = state("estes" to Lane.ROAM)
            .withPick(Side.ALLY, 0, Pick("odette", Lane.MID))
            .withPick(Side.ALLY, 1, Pick("melissa", Lane.GOLD))
        val builds = engine.buildsForMyTeam(state)
        assertEquals(listOf("odette", "melissa"), builds.map { it.hero.id })
    }
}
