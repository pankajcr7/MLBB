package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.PlayerProfile
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.scoring.MatchupScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftEngineTest {
    private val db = DatasetLoader.fromResources()
    private val engine = DraftEngine(db)

    private fun enemyDashComp(): DraftState = DraftState.forMode(DraftMode.RANKED, Side.ENEMY)
        .withPick(Side.ENEMY, 0, Pick("ling", Lane.JUNGLE))
        .withPick(Side.ENEMY, 1, Pick("harith", Lane.MID))
        .withPick(Side.ENEMY, 2, Pick("wanwan", Lane.GOLD))
        .withPick(Side.ALLY, 0, Pick("tigreal", Lane.ROAM))
        .withPick(Side.ALLY, 1, Pick("beatrix", Lane.GOLD))

    @Test
    fun `suggests the dash-punishing counter against a dash-heavy enemy comp`() {
        val suggestions = engine.suggestPicks(enemyDashComp(), Lane.EXP, limit = 3)
        val ids = suggestions.map { it.hero.id }
        assertTrue("Expected phoveus in top 3 for EXP, got $ids", "phoveus" in ids)
    }

    @Test
    fun `suggests a dash-blocking roamer against a mobility jungler`() {
        val state = DraftState.forMode(DraftMode.RANKED, Side.ENEMY)
            .withPick(Side.ENEMY, 0, Pick("fanny", Lane.JUNGLE))
        val ids = engine.suggestPicks(state, Lane.ROAM, limit = 4).map { it.hero.id }
        assertTrue("Expected khufra in top 4 for ROAM, got $ids", "khufra" in ids)
    }

    @Test
    fun `suggestions explain themselves`() {
        val top = engine.suggestPicks(enemyDashComp(), Lane.EXP, limit = 1).single()
        assertTrue("Top suggestion had no reasons", top.reasons.isNotEmpty())
        assertTrue(
            "Reasons should name the enemy hero being countered: ${top.reasons}",
            top.reasons.any { it.contains("Ling") || it.contains("Harith") || it.contains("Wanwan") },
        )
    }

    @Test
    fun `never suggests a hero that is already banned or picked`() {
        val state = enemyDashComp()
            .withBan(Side.ALLY, 0, "phoveus")
            .withBan(Side.ENEMY, 0, "yu-zhong")
        val ids = engine.suggestPicks(state, Lane.EXP, limit = 10).map { it.hero.id }
        assertFalse("Banned hero was suggested", "phoveus" in ids)
        assertFalse("Banned hero was suggested", "yu-zhong" in ids)
        assertTrue("Picked hero was suggested", ids.none { it in state.usedHeroIds })
    }

    @Test
    fun `respects a restricted hero pool`() {
        val profile = PlayerProfile(
            owned = setOf("terizla", "uranus"),
            comfort = mapOf("terizla" to 5, "uranus" to 3),
            restrictToOwned = true,
        )
        val state = enemyDashComp().copy(profile = profile)
        val ids = engine.suggestPicks(state, Lane.EXP, limit = 10).map { it.hero.id }
        assertEquals(setOf("terizla", "uranus"), ids.toSet())
        assertEquals("terizla", ids.first())
    }

    @Test
    fun `counter-pick exposure only penalises picks the enemy can still answer`() {
        val scorer = MatchupScorer(db)
        val ling = db.require("ling")

        val asFirstPick = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ALLY)
        assertTrue(
            "First pick should carry counter-pick risk",
            scorer.exposure(ling, asFirstPick).raw < 0.0,
        )

        var asLastPick = DraftState.forMode(DraftMode.RANKED, firstPick = Side.ALLY)
        repeat(asLastPick.bansPerSide) { i ->
            asLastPick = asLastPick.withBan(Side.ALLY, i, "a$i").withBan(Side.ENEMY, i, "b$i")
        }
        repeat(4) { i -> asLastPick = asLastPick.withPick(Side.ALLY, i, Pick("ally$i")) }
        repeat(5) { i -> asLastPick = asLastPick.withPick(Side.ENEMY, i, Pick("enemy$i")) }
        assertEquals(0.0, scorer.exposure(ling, asLastPick).raw, 0.0001)
    }

    @Test
    fun `ban advice targets heroes that beat what you play`() {
        val profile = PlayerProfile(
            owned = setOf("cecilion"),
            comfort = mapOf("cecilion" to 5),
        )
        val state = DraftState.forMode(DraftMode.RANKED).copy(profile = profile)
        val ids = engine.suggestBans(state, limit = 8).map { it.hero.id }
        assertTrue(
            "Expected a hook or dive threat to Cecilion in the ban list, got $ids",
            ids.any { it in setOf("franco", "kaja", "ling", "gusion", "aamon", "lancelot") },
        )
    }

    @Test
    fun `comp report warns about one-sided damage and a missing frontline`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ALLY, 0, Pick("beatrix", Lane.GOLD))
            .withPick(Side.ALLY, 1, Pick("lancelot", Lane.JUNGLE))
            .withPick(Side.ALLY, 2, Pick("lesley", Lane.MID))
            .withPick(Side.ALLY, 3, Pick("dyrroth", Lane.EXP))
        val report = engine.compReport(state, Side.ALLY)

        assertTrue("Expected a physical-damage warning: ${report.warnings}", report.warnings.any { it.contains("physical") })
        assertTrue("Expected a frontline warning: ${report.warnings}", report.warnings.any { it.contains("frontline") })
        assertTrue(report.damage.physical > 0.9)
    }

    @Test
    fun `comp report recognises a balanced team`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ALLY, 0, Pick("melissa", Lane.GOLD))
            .withPick(Side.ALLY, 1, Pick("kagura", Lane.MID))
            .withPick(Side.ALLY, 2, Pick("yu-zhong", Lane.EXP))
            .withPick(Side.ALLY, 3, Pick("khufra", Lane.ROAM))
            .withPick(Side.ALLY, 4, Pick("julian", Lane.JUNGLE))
        val report = engine.compReport(state, Side.ALLY)

        assertTrue("Expected a balanced damage split, got ${report.damage}", report.damage.isBalanced)
        assertTrue("Expected at least one frontliner", report.frontlineCount >= 1)
        assertTrue("Expected some recognised strengths", report.strengths.isNotEmpty())
    }

    @Test
    fun `item advice demands anti-heal against a sustain comp`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ENEMY, 0, Pick("estes", Lane.ROAM))
            .withPick(Side.ENEMY, 1, Pick("uranus", Lane.EXP))
            .withPick(Side.ENEMY, 2, Pick("claude", Lane.GOLD))
        val advice = engine.itemAdvice(state)
        val items = advice.map { it.item }

        assertTrue("Expected anti-heal advice, got $items", "Sea Halberd" in items)
        assertTrue("Expected magic-side anti-heal, got $items", "Necklace of Durance" in items)
        assertTrue(
            "Anti-heal should be top priority here",
            advice.first().priority >= 5,
        )
    }

    @Test
    fun `confirmed scanned build changes the recommended hero counter build`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ENEMY, 0, Pick("estes", Lane.ROAM))
            .withPick(Side.ENEMY, 1, Pick("hylos", Lane.EXP))
            .copy(
                enemyBuildSignals = setOf(
                    EnemyBuildSignal.HEALING,
                    EnemyBuildSignal.ARMOR,
                ),
            )

        val build = engine.buildFor(db.require("melissa"), state, Lane.GOLD)
        val situational = build.situational

        assertTrue("Expected Sea Halberd from confirmed healing, got $situational", situational.any { it.item.id == "sea-halberd" })
        assertTrue("Expected Malefic Roar from confirmed armor, got $situational", situational.any { it.item.id == "malefic-roar" })
        assertTrue(
            "Scanned-build answers must show why they were prioritised: $situational",
            situational.filter { it.item.id in setOf("sea-halberd", "malefic-roar") }
                .all { it.reason.startsWith("Confirmed enemy build:") },
        )
    }

    @Test
    fun `item advice reacts to a tanky enemy comp`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ENEMY, 0, Pick("hylos", Lane.ROAM))
            .withPick(Side.ENEMY, 1, Pick("uranus", Lane.EXP))
        val items = engine.itemAdvice(state).map { it.item }
        assertTrue("Expected armour penetration advice, got $items", "Malefic Roar" in items)
    }

    @Test
    fun `threat report names the biggest threats and reads the tempo`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ENEMY, 0, Pick("franco", Lane.ROAM))
            .withPick(Side.ENEMY, 1, Pick("aamon", Lane.JUNGLE))
            .withPick(Side.ENEMY, 2, Pick("estes", Lane.MID))
            .withPick(Side.ALLY, 0, Pick("cecilion", Lane.MID))
            .withPick(Side.ALLY, 1, Pick("ixia", Lane.GOLD))
        val report = engine.threatReport(state)

        assertTrue(report.threats.isNotEmpty())
        assertTrue("Expected tempo commentary", report.tempo.isNotBlank())
        assertTrue(
            "Expected an anti-heal tip against Estes: ${report.tips}",
            report.tips.any { it.contains("Anti-heal") },
        )
        assertTrue(
            "Expected a hook warning against Franco: ${report.tips}",
            report.tips.any { it.contains("fog") },
        )
    }

    @Test
    fun `by-lane suggestions cover every open lane`() {
        val state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ALLY, 0, Pick("tigreal", Lane.ROAM))
        val byLane = engine.suggestByLane(state, limit = 2)

        assertEquals(setOf(Lane.EXP, Lane.MID, Lane.GOLD, Lane.JUNGLE), byLane.keys)
        byLane.forEach { (lane, suggestions) ->
            assertTrue("No suggestions for $lane", suggestions.isNotEmpty())
            suggestions.forEach { suggestion ->
                assertTrue(
                    "${suggestion.hero.id} is not a $lane hero",
                    lane in suggestion.hero.lanes,
                )
            }
        }
    }

    @Test
    fun `scores stay inside the display range`() {
        engine.suggestPicks(enemyDashComp(), Lane.EXP, limit = 20).forEach {
            assertTrue("Score out of range: ${it.score}", it.score in -1.0..1.0)
            assertTrue(it.displayScore in 0..100)
        }
    }
}
