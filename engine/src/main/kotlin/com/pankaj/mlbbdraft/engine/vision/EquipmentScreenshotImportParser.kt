package com.pankaj.mlbbdraft.engine.vision

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal

/**
 * Result of interpreting a user-selected MLBB Equipment screenshot.
 *
 * Exact item identities are returned only after a local visual grid match passes the required
 * score and runner-up margin. Any remaining OCR text is restricted to the same red item-grid
 * bounds, so HUD labels and battle spells cannot become equipment evidence.
 */
data class EquipmentScreenshotImport(
    val isEquipmentScreen: Boolean,
    val itemNames: List<String> = emptyList(),
    val signals: Set<EnemyBuildSignal> = emptySet(),
    val enemyHeroIds: List<String> = emptyList(),
    val visibleEnemyItemSlots: Int = 0,
) {
    val hasConfirmedEvidence: Boolean
        get() = itemNames.isNotEmpty() || enemyHeroIds.isNotEmpty()
}

/**
 * Pure evidence interpreter for an MLBB Equipment screen. Android owns bitmap decoding and OCR;
 * keeping this class platform-independent makes its safety rules deterministic and unit-testable.
 */
class EquipmentScreenshotImportParser(db: HeroDatabase) {
    private val itemMatcher = EnemyBuildTextMatcher(db.items)
    private val heroMatcher = HeroTextMatcher(db.heroes)

    /**
     * [allLines] is OCR for the whole screenshot, while [redSideLines] is OCR from the red enemy
     * half. Items and heroes are matched only in the latter so blue/allied text cannot influence
     * enemy recommendations.
     */
    fun parse(
        allLines: List<ScreenText>,
        redSideLines: List<ScreenText>,
        expectedEnemyItemSlots: Int,
        visualItemNames: List<String> = emptyList(),
        frameWidth: Int = 0,
        frameHeight: Int = 0,
    ): EquipmentScreenshotImport {
        val isEquipmentScreen = allLines.any { isEquipmentMarker(it.text) }
        if (!isEquipmentScreen) return EquipmentScreenshotImport(isEquipmentScreen = false)

        val gridTextLines = redSideLines.filter { line ->
            frameWidth <= 0 || frameHeight <= 0 ||
                EnemyItemGridGeometry.containsItemGridText(line.centerX, line.centerY, frameWidth, frameHeight)
        }
        val textScan = itemMatcher.scan(gridTextLines)
        val visualScan = itemMatcher.scanItemNames(visualItemNames)
        val itemNames = (visualScan.itemNames + textScan.itemNames).distinct()
        val signals = visualScan.signals + textScan.signals
        val heroIds = linkedSetOf<String>()
        redSideLines.forEach { line ->
            heroMatcher.match(line.text)?.let(heroIds::add)
        }

        return EquipmentScreenshotImport(
            isEquipmentScreen = true,
            itemNames = itemNames,
            signals = signals,
            enemyHeroIds = heroIds.toList(),
            visibleEnemyItemSlots = expectedEnemyItemSlots.coerceAtLeast(0),
        )
    }

    private fun isEquipmentMarker(value: String): Boolean {
        val normalised = value.lowercase().replace(Regex("[^a-z]"), "")
        return normalised.contains("equipment")
    }
}
