package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.vision.EquipmentScreenshotImportParser
import com.pankaj.mlbbdraft.engine.vision.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentScreenshotImportParserTest {
    private val parser = EquipmentScreenshotImportParser(DatasetLoader.fromResources())

    @Test
    fun `equipment screenshot imports explicit red-side names only`() {
        val imported = parser.parse(
            allLines = listOf(
                ScreenText("Equipment", 1400, 80),
                ScreenText("Sea Halberd", 420, 520), // blue-side text must not influence enemy advice
                ScreenText("Saber", 2360, 440),
                ScreenText("Dominance Ice", 2320, 520),
            ),
            redSideLines = listOf(
                ScreenText("Saber", 960, 440),
                ScreenText("Dominance Ice", 900, 520),
            ),
            expectedEnemyItemSlots = 30,
        )

        assertTrue(imported.isEquipmentScreen)
        assertEquals(listOf("Dominance Ice"), imported.itemNames)
        assertTrue(EnemyBuildSignal.ARMOR in imported.signals)
        assertTrue(EnemyBuildSignal.ATTACK_SPEED in imported.signals)
        assertEquals(listOf("saber"), imported.enemyHeroIds)
        assertEquals(30, imported.visibleEnemyItemSlots)
    }

    @Test
    fun `icon-only equipment screenshot preserves manual confirmation fallback`() {
        val imported = parser.parse(
            allLines = listOf(
                ScreenText("Equipment", 1400, 80),
                ScreenText("Attributes", 1600, 80),
            ),
            redSideLines = listOf(
                ScreenText("Player 1", 960, 440),
                ScreenText("6,524", 1080, 440),
            ),
            expectedEnemyItemSlots = 30,
        )

        assertTrue(imported.isEquipmentScreen)
        assertFalse(imported.hasConfirmedEvidence)
        assertTrue(imported.itemNames.isEmpty())
        assertTrue(imported.signals.isEmpty())
        assertEquals(30, imported.visibleEnemyItemSlots)
    }

    @Test
    fun `non equipment image cannot change build state`() {
        val imported = parser.parse(
            allLines = listOf(ScreenText("Draft Pick", 1400, 80)),
            redSideLines = listOf(ScreenText("Dominance Ice", 960, 440)),
            expectedEnemyItemSlots = 30,
        )

        assertFalse(imported.isEquipmentScreen)
        assertTrue(imported.itemNames.isEmpty())
        assertTrue(imported.signals.isEmpty())
        assertEquals(0, imported.visibleEnemyItemSlots)
    }
}
