package com.pankaj.mlbbdraft.engine.vision

import kotlin.math.min
import kotlin.math.roundToInt

/** A single normalized red-team equipment slot in the MLBB landscape scoreboard. */
data class EnemyItemGridSlot(
    val row: Int,
    val column: Int,
    val centerX: Float,
    val centerY: Float,
)

/** Integer crop bounds, constrained to the source image. */
data class ItemGridCrop(
    val left: Int,
    val top: Int,
    val size: Int,
)

/**
 * The Equipment scoreboard has five red rows and six item positions per row. These normalized
 * centers are measured from the visible grid, rather than the full red half, so HUD text such as
 * battle spells cannot enter the item matcher.
 */
object EnemyItemGridGeometry {
    const val ROWS = 5
    const val COLUMNS = 6
    private const val SLOT_SIZE_OF_MINOR_AXIS = 0.056f
    // Calibrated on the supplied 2800x1260 real Equipment screen; retain the same relative grid
    // spacing while moving crop centres to the visual centre of each circular icon.
    private val xCenters = floatArrayOf(0.5255f, 0.5559f, 0.5862f, 0.6166f, 0.6469f, 0.6773f)
    private val yCenters = floatArrayOf(0.3525f, 0.4768f, 0.6008f, 0.7246f, 0.8484f)

    fun slots(): List<EnemyItemGridSlot> = buildList(ROWS * COLUMNS) {
        yCenters.forEachIndexed { row, y ->
            xCenters.forEachIndexed { column, x -> add(EnemyItemGridSlot(row, column, x, y)) }
        }
    }

    fun cropFor(slot: EnemyItemGridSlot, width: Int, height: Int): ItemGridCrop? {
        if (width <= 0 || height <= 0) return null
        val size = (min(width, height) * SLOT_SIZE_OF_MINOR_AXIS).roundToInt().coerceAtLeast(1)
        val left = (slot.centerX * width - size / 2f).roundToInt()
        val top = (slot.centerY * height - size / 2f).roundToInt()
        if (left < 0 || top < 0 || left + size > width || top + size > height) return null
        return ItemGridCrop(left, top, size)
    }

    /** Text needs to lie inside the equipment area, never in a lower HUD or player panel. */
    fun containsItemGridText(centerX: Int, centerY: Int, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return centerX in (width * 0.49f).roundToInt()..(width * 0.71f).roundToInt() &&
            centerY in (height * 0.30f).roundToInt()..(height * 0.89f).roundToInt()
    }
}

data class VisualItemCandidate(
    val itemId: String,
    val score: Double,
)

/** A visual match that has already passed score and runner-up margin checks. */
data class ConfirmedVisualItemMatch(
    val row: Int,
    val column: Int,
    val itemId: String,
)

/** Requires the same accepted item in the same grid slot across consecutive live frames. */
class ItemGridStabilityTracker {
    private var previous = emptyMap<Pair<Int, Int>, String>()

    fun observe(matches: List<ConfirmedVisualItemMatch>): List<ConfirmedVisualItemMatch> {
        val stable = matches.filter { previous[it.row to it.column] == it.itemId }
        previous = matches.associate { (it.row to it.column) to it.itemId }
        return stable
    }

    fun reset() {
        previous = emptyMap()
    }
}

/** Conservative visual acceptance gate: low-confidence or tied icon artwork remains unknown. */
object ItemVisualConfidencePolicy {
    // Tuned against the supplied real screen after circular icon masking. The margin remains
    // deliberately high: a plausible but tied icon is left unconfirmed rather than guessed.
    const val MIN_SCORE = 0.76
    const val MIN_MARGIN = 0.08

    fun accepted(best: VisualItemCandidate?, runnerUp: VisualItemCandidate?): Boolean {
        if (best == null) return false
        if (best.score < MIN_SCORE) return false
        val margin = best.score - (runnerUp?.score ?: Double.NEGATIVE_INFINITY)
        return margin >= MIN_MARGIN
    }
}
