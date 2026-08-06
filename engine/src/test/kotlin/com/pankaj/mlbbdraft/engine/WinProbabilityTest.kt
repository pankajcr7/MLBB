package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.report.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinProbabilityTest {
    private val db = DatasetLoader.fromResources()
    private val engine = DraftEngine(db)

    private val fullDraft = DraftState.forMode(DraftMode.RANKED)
        .withPick(Side.ALLY, 0, Pick("khufra", Lane.ROAM))
        .withPick(Side.ALLY, 1, Pick("melissa", Lane.GOLD))
        .withPick(Side.ALLY, 2, Pick("kagura", Lane.MID))
        .withPick(Side.ALLY, 3, Pick("yu-zhong", Lane.EXP))
        .withPick(Side.ALLY, 4, Pick("julian", Lane.JUNGLE))

    @Test
    fun `an empty board is a coin flip`() {
        val result = engine.winProbability(DraftState.forMode(DraftMode.RANKED))
        assertEquals(50, result.allyPercent)
        assertEquals(Confidence.LOW, result.confidence)
    }

    @Test
    fun `mirrored drafts land near even`() {
        var state = DraftState.forMode(DraftMode.RANKED)
        val comp = listOf(
            "tigreal" to Lane.ROAM,
            "melissa" to Lane.GOLD,
            "pharsa" to Lane.MID,
            "yu-zhong" to Lane.EXP,
            "lancelot" to Lane.JUNGLE,
        )
        // Same shape on both sides: same roles, same tiers, so neither side is favoured.
        comp.forEachIndexed { i, (id, lane) -> state = state.withPick(Side.ALLY, i, Pick(id, lane)) }
        listOf(
            "atlas" to Lane.ROAM,
            "beatrix" to Lane.GOLD,
            "xavier" to Lane.MID,
            "arlott" to Lane.EXP,
            "nolan" to Lane.JUNGLE,
        ).forEachIndexed { i, (id, lane) -> state = state.withPick(Side.ENEMY, i, Pick(id, lane)) }

        val result = engine.winProbability(state)
        assertTrue(
            "Two reasonable drafts should be close to even, got ${result.allyPercent}",
            result.allyPercent in 40..60,
        )
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `a draft full of hard counters is favoured`() {
        // Ally comp is specifically built to beat this enemy: Khufra and Phoveus into
        // dash-heavy heroes, Baxia into the healer.
        var state = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ALLY, 0, Pick("khufra", Lane.ROAM))
            .withPick(Side.ALLY, 1, Pick("phoveus", Lane.EXP))
            .withPick(Side.ALLY, 2, Pick("baxia", Lane.JUNGLE))
            .withPick(Side.ALLY, 3, Pick("karrie", Lane.GOLD))
            .withPick(Side.ALLY, 4, Pick("kagura", Lane.MID))
        listOf("ling" to Lane.JUNGLE, "estes" to Lane.ROAM, "harith" to Lane.MID, "uranus" to Lane.EXP, "wanwan" to Lane.GOLD)
            .forEachIndexed { i, (id, lane) -> state = state.withPick(Side.ENEMY, i, Pick(id, lane)) }

        val result = engine.winProbability(state)
        assertTrue(
            "Counter-heavy draft should be favoured, got ${result.allyPercent} (${result.headline})",
            result.allyPercent > 55,
        )
        assertTrue("Expected explanatory factors", result.topFactors.isNotEmpty())
        assertTrue(
            "Matchups should be the leading factor: ${result.topFactors.map { it.label }}",
            result.topFactors.first().label == "Matchups",
        )
    }

    @Test
    fun `the estimate stays inside honest bounds`() {
        var worst = DraftState.forMode(DraftMode.RANKED)
        // Deliberately awful: five immobile, all-magic, no frontline, into their counters.
        listOf("ixia" to Lane.GOLD, "cecilion" to Lane.MID, "estes" to Lane.ROAM, "xavier" to Lane.EXP, "zhask" to Lane.JUNGLE)
            .forEachIndexed { i, (id, lane) -> worst = worst.withPick(Side.ALLY, i, Pick(id, lane)) }
        listOf("ling" to Lane.JUNGLE, "franco" to Lane.ROAM, "kaja" to Lane.EXP, "aamon" to Lane.MID, "melissa" to Lane.GOLD)
            .forEachIndexed { i, (id, lane) -> worst = worst.withPick(Side.ENEMY, i, Pick(id, lane)) }

        val result = engine.winProbability(worst)
        assertTrue("Should be clearly behind, got ${result.allyPercent}", result.allyPercent < 45)
        assertTrue("Must never claim certainty, got ${result.allyPercent}", result.allyPercent in 22..78)
        assertTrue(result.caveat.isNotBlank())
    }

    @Test
    fun `confidence grows as picks come in`() {
        val partial = DraftState.forMode(DraftMode.RANKED)
            .withPick(Side.ALLY, 0, Pick("khufra", Lane.ROAM))
            .withPick(Side.ENEMY, 0, Pick("ling", Lane.JUNGLE))
        assertEquals(Confidence.LOW, engine.winProbability(partial).confidence)

        var half = partial
        listOf("melissa" to Lane.GOLD, "kagura" to Lane.MID, "yu-zhong" to Lane.EXP)
            .forEachIndexed { i, (id, lane) -> half = half.withPick(Side.ALLY, i + 1, Pick(id, lane)) }
        assertEquals(Confidence.MEDIUM, engine.winProbability(half).confidence)
        assertTrue(
            "Partial drafts should say so: ${engine.winProbability(half).headline}",
            engine.winProbability(half).headline.contains("confidence"),
        )
    }

    @Test
    fun `mastery counts when a profile is configured`() {
        val unrated = engine.winProbability(fullDraft)
        val rated = engine.winProbability(
            fullDraft.copy(
                profile = fullDraft.profile.copy(
                    owned = setOf("khufra", "melissa", "kagura", "yu-zhong", "julian"),
                    comfort = mapOf(
                        "khufra" to 5, "melissa" to 5, "kagura" to 5, "yu-zhong" to 5, "julian" to 5,
                    ),
                ),
            ),
        )
        assertTrue(
            "Playing five signature heroes should not lower the estimate",
            rated.allyPercent >= unrated.allyPercent,
        )
        assertTrue(
            "Mastery should appear as a factor: ${rated.factors.map { it.label }}",
            rated.factors.any { it.label == "Your mastery" },
        )
    }
}
