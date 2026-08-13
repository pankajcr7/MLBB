package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.vision.EnemyBuildTextMatcher
import com.pankaj.mlbbdraft.engine.vision.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnemyBuildTextMatcherTest {
    private val matcher = EnemyBuildTextMatcher(DatasetLoader.fromResources().items)

    @Test
    fun `readable enemy equipment names produce counter-build signals`() {
        val scan = matcher.scan(
            listOf(
                ScreenText("Dominance Ice", 2100, 220),
                ScreenText("Athena's Shield", 2100, 340),
                ScreenText("Sea Halberd", 2100, 460),
            ),
        )

        assertEquals(
            listOf("Dominance Ice", "Athena's Shield", "Sea Halberd"),
            scan.itemNames,
        )
        assertTrue(EnemyBuildSignal.ARMOR in scan.signals)
        assertTrue(EnemyBuildSignal.ATTACK_SPEED in scan.signals)
        assertTrue(EnemyBuildSignal.SHIELDS in scan.signals)
        assertTrue(EnemyBuildSignal.MAGIC_RESIST in scan.signals)
    }

    @Test
    fun `icon-only or generic scoreboard labels cannot invent enemy equipment`() {
        val scan = matcher.scan(
            listOf(
                ScreenText("Equipment", 120, 120),
                ScreenText("Attributes", 360, 120),
                ScreenText("Sort by Gold", 2400, 120),
                ScreenText("Player 1", 2320, 300),
            ),
        )

        assertTrue(scan.itemNames.isEmpty())
        assertTrue(scan.signals.isEmpty())
    }
}
