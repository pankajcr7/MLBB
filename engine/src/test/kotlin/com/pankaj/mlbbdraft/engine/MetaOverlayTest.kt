package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.meta.CatalogueHero
import com.pankaj.mlbbdraft.engine.meta.CatalogueItem
import com.pankaj.mlbbdraft.engine.meta.CatalogueOverlay
import com.pankaj.mlbbdraft.engine.meta.HeroNameResolver
import com.pankaj.mlbbdraft.engine.meta.MetaApplier
import com.pankaj.mlbbdraft.engine.meta.MetaApplyReport
import com.pankaj.mlbbdraft.engine.meta.MetaHero
import com.pankaj.mlbbdraft.engine.meta.MetaOverlay
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.MatchupEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaOverlayTest {
    private val base = DatasetLoader.fromResources()

    /**
     * A feed covering enough heroes to clear the sanity floor. Overridden heroes are
     * listed first so they are always in the slice, whatever the alphabet does.
     */
    private fun realisticFeed(
        overrides: Map<String, MetaHero> = emptyMap(),
        patch: String = "2026.08.1",
    ): MetaOverlay {
        val ids = (overrides.keys + base.heroes.map { it.id }).distinct().take(45)
        val heroes = ids.mapNotNull { id ->
            val hero = base.hero(id) ?: return@mapNotNull null
            overrides[id] ?: MetaHero(name = hero.name, winRate = 50.0, pickRate = 1.0, banRate = 0.0)
        }
        return MetaOverlay(patch = patch, updatedAt = "2026-08-06T10:00:00Z", source = "test", heroes = heroes)
    }

    @Test
    fun `resolver matches both our slugs and source display names`() {
        val resolver = HeroNameResolver(base.heroes.map { it.id to it.name })
        assertEquals("yi-sun-shin", resolver.resolve("Yi Sun-shin"))
        assertEquals("yi-sun-shin", resolver.resolve("yi-sun-shin"))
        assertEquals("x-borg", resolver.resolve("X.Borg"))
        assertEquals("luo-yi", resolver.resolve("Luo Yi"))
        assertEquals("yu-zhong", resolver.resolve("YU ZHONG"))
        assertEquals("odette", resolver.resolve("odette"))
        assertNull(resolver.resolve("Definitely Not A Hero"))
    }

    @Test
    fun `rates are accepted as percentages or fractions`() {
        val asPercent = MetaHero("Odette", winRate = 54.0, banRate = 30.0).normalised()
        val asFraction = MetaHero("Odette", winRate = 0.54, banRate = 0.30).normalised()
        assertEquals(asFraction.winRate!!, asPercent.winRate!!, 0.0001)
        assertEquals(asFraction.banRate!!, asPercent.banRate!!, 0.0001)
    }

    @Test
    fun `win rate drives the derived tier in the right direction`() {
        val weak = MetaApplier.deriveTier(MetaHero("x", winRate = 0.45))!!
        val average = MetaApplier.deriveTier(MetaHero("x", winRate = 0.50))!!
        val strong = MetaApplier.deriveTier(MetaHero("x", winRate = 0.55))!!
        assertTrue("$weak should be below $average", weak < average)
        assertTrue("$strong should be above $average", strong > average)
        assertTrue("Average should sit mid-scale, got $average", average in 5.0..6.0)

        // A heavily banned hero is a problem hero, even at an ordinary win rate.
        val banned = MetaApplier.deriveTier(MetaHero("x", winRate = 0.50, banRate = 0.60))!!
        assertTrue("$banned should exceed $average", banned > average)

        assertTrue(MetaApplier.deriveTier(MetaHero("x", winRate = 0.99))!! <= 10.0)
        assertTrue(MetaApplier.deriveTier(MetaHero("x", winRate = 0.01))!! >= 0.0)
    }

    @Test
    fun `an explicit tier from the source wins over derived numbers`() {
        assertEquals(9.0, MetaApplier.deriveTier(MetaHero("x", winRate = 0.40, tier = 9.0))!!, 0.0001)
    }

    @Test
    fun `heroes with no numbers are left alone`() {
        assertNull(MetaApplier.deriveTier(MetaHero("x")))
    }

    @Test
    fun `applying a feed moves tiers but keeps every hero and item`() {
        val feed = realisticFeed(
            overrides = mapOf(
                "odette" to MetaHero("Odette", winRate = 57.0, pickRate = 2.0, banRate = 40.0),
            ),
        )
        val (updated, report) = MetaApplier.apply(base, feed)

        assertTrue("Report should be usable, got ${report.heroesMatched}", report.isUsable)
        assertEquals(base.size, updated.size)
        assertEquals(base.items.size, updated.items.size)
        assertEquals(base.synergies.size, updated.synergies.size)

        val before = base.require("odette").tier[Lane.MID]!!
        val after = updated.require("odette").tier[Lane.MID]!!
        assertTrue("A 57% win rate with heavy bans should raise $before, got $after", after > before)
        assertTrue("Blending should keep it in range, got $after", after in 0.0..10.0)
        assertTrue(updated.patch.contains("2026.08.1"))
    }

    @Test
    fun `tiers are only set for lanes the hero actually plays`() {
        val feed = realisticFeed(
            overrides = mapOf("odette" to MetaHero("Odette", winRate = 56.0, lane = Lane.JUNGLE)),
        )
        val (updated, report) = MetaApplier.apply(base, feed)
        val odette = updated.require("odette")
        assertEquals(setOf(Lane.MID), odette.tier.keys)
        assertTrue(
            "Expected a warning about the bogus lane: ${report.warnings}",
            report.warnings.any { it.contains("JUNGLE") },
        )
    }

    @Test
    fun `derived tiers are blended with the seed rather than replacing it`() {
        // Same win rate for everyone: heroes the seed rates differently must still differ.
        val (updated, _) = MetaApplier.apply(base, realisticFeed())
        val strongSeed = base.heroes.maxBy { it.tier.values.max() }
        val weakSeed = base.heroes.take(40).minBy { it.tier.values.max() }
        val strongAfter = updated.require(strongSeed.id).tier.values.max()
        val weakAfter = updated.require(weakSeed.id).tier.values.max()
        assertTrue(
            "Seed knowledge should survive a flat feed: $strongAfter vs $weakAfter",
            strongAfter > weakAfter,
        )
    }

    @Test
    fun `a feed that matches almost nothing is rejected outright`() {
        val junk = MetaOverlay(
            patch = "garbage",
            updatedAt = "2026-08-06T10:00:00Z",
            heroes = (1..50).map { MetaHero(name = "Unknown Hero $it", winRate = 99.0) },
        )
        val (result, report) = MetaApplier.apply(base, junk)

        assertSame("The original database must be returned untouched", base, result)
        assertTrue(!report.isUsable)
        assertEquals(0, report.tiersChanged)
        assertTrue(report.unknownNames.isNotEmpty())
        assertTrue(
            "Should explain itself: ${report.warnings}",
            report.warnings.any { it.contains("resolved") },
        )
    }

    @Test
    fun `authored counter notes are never overwritten by the feed`() {
        val authored = base.counterEdge("khufra", "fanny")
        assertNotNull(authored)

        val feed = realisticFeed().copy(
            counters = listOf(
                MatchupEdge("khufra", "fanny", -1.0, "feed nonsense"),
                MatchupEdge("Odette", "Miya", 0.5, "new pair from the feed"),
            ),
        )
        val (updated, report) = MetaApplier.apply(base, feed)

        assertEquals(
            "Authored edge must win",
            authored!!.weight,
            updated.counterEdge("khufra", "fanny")!!.weight,
            0.0001,
        )
        assertEquals(1, report.countersAdded)
        // Feed names get resolved to our slugs on the way in.
        assertNotNull(updated.counterEdge("odette", "miya"))
    }

    @Test
    fun `overlay survives a JSON round trip`() {
        val feed = realisticFeed()
        val parsed = MetaApplier.parse(MetaApplier.encode(feed))
        assertEquals(feed.patch, parsed.patch)
        assertEquals(feed.heroes.size, parsed.heroes.size)
    }

    @Test
    fun `unknown fields in the feed are tolerated`() {
        val json = """
            {
              "patch": "2026.08.1",
              "updatedAt": "2026-08-06T10:00:00Z",
              "source": "example",
              "somethingWeDoNotKnowAbout": 42,
              "heroes": [
                { "name": "Odette", "winRate": 53.1, "pickRate": 1.2, "banRate": 4.4, "rank": "mythic" }
              ]
            }
        """.trimIndent()
        val parsed = MetaApplier.parse(json)
        assertEquals(1, parsed.heroes.size)
        assertEquals("Odette", parsed.heroes.first().name)
    }

    @Test
    fun `complete catalogue refresh adds aliases and prices but preserves item semantics`() {
        val sourceEquipment = base.items
            .filter { it.category != ItemCategory.SPELL }
            .mapIndexed { index, item ->
                CatalogueItem(
                    sourceId = "item-${index + 1}",
                    name = when (item.id) {
                        "magic-shoes" -> "Magic Boots"
                        "demon-shoes" -> "Demon Boots"
                        else -> item.name
                    },
                    priceGold = if (item.id == "magic-shoes") 777 else item.cost,
                )
            }
        val overlay = MetaOverlay(
            patch = "catalogue-test",
            updatedAt = "2026-08-13T00:00:00Z",
            source = "test",
            catalogue = CatalogueOverlay(
                upstreamCommit = "abc1234",
                heroes = base.heroes.mapIndexed { index, hero ->
                    CatalogueHero("hero-${index + 1}", hero.name)
                },
                equipment = sourceEquipment,
            ),
        )

        val (updated, report) = MetaApplier.apply(base, overlay)

        assertTrue("Catalogue should clear the complete-snapshot floor: $report", report.isUsable)
        assertEquals(base.heroes.size, report.catalogueHeroesMatched)
        assertEquals(sourceEquipment.size, report.catalogueItemsMatched)
        assertTrue("Magic Boots should be a verified alias", "Magic Boots" in updated.item("magic-shoes")!!.aliases)
        assertEquals(777, updated.item("magic-shoes")!!.cost)
        assertEquals(ItemCategory.MOVEMENT, updated.item("magic-shoes")!!.category)
        assertEquals(ItemCategory.SPELL, updated.item("flicker")!!.category)
        assertEquals("No source hero may become a new playable hero", base.size, updated.size)
    }

    @Test
    fun `partial catalogue snapshot is rejected without touching the bundled data`() {
        val partial = MetaOverlay(
            patch = "partial-catalogue",
            updatedAt = "2026-08-13T00:00:00Z",
            catalogue = CatalogueOverlay(
                upstreamCommit = "abc1234",
                heroes = base.heroes.take(10).mapIndexed { index, hero -> CatalogueHero("h$index", hero.name) },
                equipment = base.items.filter { it.category != ItemCategory.SPELL }.take(10)
                    .mapIndexed { index, item -> CatalogueItem("i$index", item.name, item.cost) },
            ),
        )
        val (updated, report) = MetaApplier.apply(base, partial)

        assertSame(base, updated)
        assertTrue(!report.isUsable)
        assertTrue(report.warnings.any { it.contains("safety floor") })
    }

    @Test
    fun `the sanity floor is documented where it is enforced`() {
        assertEquals(20, MetaApplyReport.MIN_MATCHED)
        assertEquals(100, MetaApplyReport.MIN_CATALOGUE_HEROES)
        assertEquals(45, MetaApplyReport.MIN_CATALOGUE_ITEMS)
    }

    /**
     * The feed actually published at `data/meta.json` must be consumable. This catches a
     * broken publish before the app has to discover it over the network.
     */
    @Test
    fun `the published feed applies cleanly`() {
        val published = java.io.File("../data/meta.json")
        if (!published.isFile) return // Not published yet — nothing to check.

        val overlay = MetaApplier.parse(published.readText())
        val (updated, report) = MetaApplier.apply(base, overlay)
        assertTrue(
            "Published feed matched only ${report.heroesMatched} heroes: ${report.unknownNames}",
            report.isUsable,
        )
        val unknownLiveMetaNames = report.unknownNames.filterNot {
            it.startsWith("hero:") || it.startsWith("equipment:")
        }
        assertEquals(
            "Live tier records must resolve cleanly: ${report.unknownNames}",
            emptyList<String>(),
            unknownLiveMetaNames,
        )
        assertTrue(
            "Published catalogue must be complete enough to apply: $report",
            report.catalogueHeroesMatched >= MetaApplyReport.MIN_CATALOGUE_HEROES &&
                report.catalogueItemsMatched >= MetaApplyReport.MIN_CATALOGUE_ITEMS,
        )
        assertEquals("No hero may be lost", base.size, updated.size)
        assertTrue("Feed needs a patch label", overlay.patch.isNotBlank())
    }

    /**
     * End-to-end guard on the real pipeline: this fixture is verbatim output from
     * `tools/build_meta.py`. If the script's shape and the engine's schema ever drift
     * apart, this fails instead of the app silently ignoring every published feed.
     */
    @Test
    fun `output from the publishing script is consumable as-is`() {
        val body = javaClass.classLoader
            .getResourceAsStream("fixtures/meta-feed.json")
            ?.bufferedReader()
            ?.use { it.readText() }
        assertNotNull("Missing fixtures/meta-feed.json", body)

        val overlay = MetaApplier.parse(body!!)
        assertTrue("Fixture should carry heroes", overlay.heroes.size >= 40)

        val (updated, report) = MetaApplier.apply(base, overlay)
        assertTrue(
            "Script output should resolve cleanly, matched ${report.heroesMatched}: ${report.unknownNames}",
            report.isUsable,
        )
        assertEquals("Every name in the fixture should resolve", emptyList<String>(), report.unknownNames)
        assertTrue("Tiers should actually move", report.tiersChanged > 0)
        assertEquals("No hero may be lost", base.size, updated.size)
    }
}
