package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.vision.ConfirmedVisualItemMatch
import com.pankaj.mlbbdraft.engine.vision.EnemyBuildTextMatcher
import com.pankaj.mlbbdraft.engine.vision.EnemyItemGridGeometry
import com.pankaj.mlbbdraft.engine.vision.ItemGridStabilityTracker
import com.pankaj.mlbbdraft.engine.vision.ItemVisualConfidencePolicy
import com.pankaj.mlbbdraft.engine.vision.ScreenText
import com.pankaj.mlbbdraft.engine.vision.VisualItemCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnemyItemGridPolicyTest {
    @Test
    fun `equipment grid has thirty in-bounds red item slots`() {
        val slots = EnemyItemGridGeometry.slots()
        assertEquals(30, slots.size)
        assertTrue(slots.all { EnemyItemGridGeometry.cropFor(it, 2800, 1260) != null })
    }

    @Test
    fun `lower HUD text such as Flicker is outside the red item grid`() {
        assertFalse(EnemyItemGridGeometry.containsItemGridText(1_650, 1_165, 2_800, 1_260))
        assertTrue(EnemyItemGridGeometry.containsItemGridText(1_635, 440, 2_800, 1_260))
    }

    @Test
    fun `visual candidate requires both confidence and separation`() {
        assertTrue(
            ItemVisualConfidencePolicy.accepted(
                VisualItemCandidate("dominance-ice", 0.92),
                VisualItemCandidate("antique-cuirass", 0.80),
            ),
        )
        assertFalse(
            ItemVisualConfidencePolicy.accepted(
                VisualItemCandidate("dominance-ice", 0.75),
                VisualItemCandidate("antique-cuirass", 0.60),
            ),
        )
        assertFalse(
            ItemVisualConfidencePolicy.accepted(
                VisualItemCandidate("dominance-ice", 0.92),
                VisualItemCandidate("antique-cuirass", 0.88),
            ),
        )
    }

    @Test
    fun `live visual item requires two matching frames`() {
        val tracker = ItemGridStabilityTracker()
        val match = ConfirmedVisualItemMatch(row = 0, column = 0, itemId = "dominance-ice")
        assertTrue(tracker.observe(listOf(match)).isEmpty())
        assertEquals(listOf(match), tracker.observe(listOf(match)))
        assertTrue(tracker.observe(listOf(match.copy(itemId = "antique-cuirass"))).isEmpty())
    }

    @Test
    fun `battle spells cannot be matched as equipment even when OCR reads their label`() {
        val database = DatasetLoader.fromResources()
        assertTrue(database.items.any { it.name == "Flicker" && it.category == ItemCategory.SPELL })
        val scan = EnemyBuildTextMatcher(database.items).scan(listOf(ScreenText("Flicker", 1_650, 1_165)))
        assertTrue(scan.itemNames.isEmpty())
        assertTrue(scan.signals.isEmpty())
    }
}
