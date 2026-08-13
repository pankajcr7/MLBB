package com.pankaj.mlbbdraft.engine.vision

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.Side
import java.util.Locale

/** One line of text found on screen, with where it sits in the frame. */
data class ScreenText(
    val text: String,
    val centerX: Int,
    val centerY: Int,
)

data class DetectedHero(
    val heroId: String,
    val side: Side,
    val centerX: Int,
    val centerY: Int,
)

data class DetectionResult(
    val heroes: List<DetectedHero>,
    /** Text that looked like nothing we know — useful when debugging a bad read. */
    val unmatched: List<String>,
    /** Stable draft UI text such as “Pick” or “Ban”, used to recognise a fresh match. */
    val hasDraftSignal: Boolean = false,
    /** Stable in-match scoreboard labels; never treated as a fresh draft signal. */
    val hasEquipmentScreen: Boolean = false,
    /** Hero labels in the central searchable catalog, excluded from committed draft picks. */
    val ignoredCatalogHeroLabels: Int = 0,
) {
    fun ids(side: Side): List<String> = heroes.filter { it.side == side }.map { it.heroId }

    val isEmpty: Boolean get() = heroes.isEmpty()
}

/**
 * Turns recognised on-screen text into "these heroes are on the left, those on the right".
 *
 * A locked hero name is expected in one of MLBB's outer red or blue player panels. The central
 * picker grid is explicitly excluded because it only lists available heroes. Restricting live
 * detections to the two roster columns also prevents kill-feed, tooltip, and search labels from
 * fabricating draft picks.
 */
class DraftScreenReader(
    db: HeroDatabase,
    /** Set false if your team appears on the right instead of the left. */
    private val allyOnLeft: Boolean = true,
) {
    private val matcher = HeroTextMatcher(db.heroes)

    fun read(
        lines: List<ScreenText>,
        frameWidth: Int,
        frameHeight: Int = 0,
    ): DetectionResult {
        if (frameWidth <= 0) return DetectionResult(emptyList(), emptyList())

        val midpoint = frameWidth / 2
        val found = LinkedHashMap<String, DetectedHero>()
        val unmatched = mutableListOf<String>()
        var hasDraftSignal = false
        var equipmentMarkers = 0
        var ignoredCatalogHeroLabels = 0

        lines.forEach { line ->
            if (isDraftSignal(line.text)) hasDraftSignal = true
            if (isEquipmentMarker(line.text)) equipmentMarkers += 1

            val id = matcher.match(line.text)
            if (id == null) {
                if (line.text.isNotBlank()) unmatched += line.text
                return@forEach
            }
            if (isHeroCatalogLabel(line, frameWidth, frameHeight)) {
                ignoredCatalogHeroLabels += 1
                return@forEach
            }
            if (!isCommittedPlayerPanelLabel(line, frameWidth, frameHeight)) {
                return@forEach
            }

            val leftSide = line.centerX < midpoint
            val side = if (leftSide == allyOnLeft) Side.ALLY else Side.ENEMY

            // The same hero cannot be on both teams; preserve the first stable sighting so a
            // tooltip or kill-feed line cannot move it across teams.
            found.getOrPut(id) { DetectedHero(id, side, line.centerX, line.centerY) }
        }

        return DetectionResult(
            heroes = found.values.sortedWith(compareBy({ it.side }, { it.centerY }, { it.centerX })),
            unmatched = unmatched.distinct().take(12),
            hasDraftSignal = hasDraftSignal,
            // One word can appear in a tooltip; require two independent pieces of scoreboard UI.
            hasEquipmentScreen = equipmentMarkers >= REQUIRED_EQUIPMENT_MARKERS,
            ignoredCatalogHeroLabels = ignoredCatalogHeroLabels,
        )
    }

    /**
     * The central picker grid lists available heroes, not locked selections. Selected players
     * remain in the outer roster columns, so admitting these labels would fabricate a full draft.
     * Height-free callers retain the prior text-only behaviour for backwards compatibility.
     */
    private fun isHeroCatalogLabel(line: ScreenText, frameWidth: Int, frameHeight: Int): Boolean {
        if (frameHeight <= 0) return false
        val inCentralColumns = line.centerX in
            (frameWidth * CATALOG_LEFT).toInt()..(frameWidth * CATALOG_RIGHT).toInt()
        val inPickerRows = line.centerY in
            (frameHeight * CATALOG_TOP).toInt()..(frameHeight * CATALOG_BOTTOM).toInt()
        return inCentralColumns && inPickerRows
    }

    /**
     * Hero names become reliable only when MLBB renders them in one of the five player cards on
     * the outer blue or red side. Height-free callers keep the pre-existing text-only behaviour
     * for unit tests and non-live integrations.
     */
    private fun isCommittedPlayerPanelLabel(line: ScreenText, frameWidth: Int, frameHeight: Int): Boolean {
        if (frameHeight <= 0) return true
        val inOuterPanel =
            line.centerX <= (frameWidth * PLAYER_PANEL_LEFT_MAX).toInt() ||
                line.centerX >= (frameWidth * PLAYER_PANEL_RIGHT_MIN).toInt()
        val inPlayerRows = line.centerY in
            (frameHeight * PLAYER_PANEL_TOP).toInt()..(frameHeight * PLAYER_PANEL_BOTTOM).toInt()
        return inOuterPanel && inPlayerRows
    }

    private fun isDraftSignal(value: String): Boolean {
        val text = normaliseUiText(value)
        return DRAFT_MARKERS.any(text::contains)
    }

    private fun isEquipmentMarker(value: String): Boolean {
        val text = normaliseUiText(value)
        return EQUIPMENT_MARKERS.any(text::contains)
    }

    private fun normaliseUiText(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")

    private companion object {
        const val REQUIRED_EQUIPMENT_MARKERS = 2
        const val CATALOG_LEFT = 0.22
        const val CATALOG_RIGHT = 0.78
        const val CATALOG_TOP = 0.18
        const val CATALOG_BOTTOM = 0.82
        const val PLAYER_PANEL_LEFT_MAX = 0.24
        const val PLAYER_PANEL_RIGHT_MIN = 0.76
        const val PLAYER_PANEL_TOP = 0.14
        const val PLAYER_PANEL_BOTTOM = 0.94
        val DRAFT_MARKERS = listOf("pick", "ban", "choose", "select", "draft")
        val EQUIPMENT_MARKERS = listOf("equipment", "attributes", "sortbygold")
    }
}

enum class DraftTrackingState {
    READING,
    BETWEEN_MATCHES,
}

data class DraftTrackingUpdate(
    val newlyConfirmed: List<DetectedHero>,
    val newMatchStarted: Boolean,
    val state: DraftTrackingState,
)

/**
 * Smooths noisy OCR into a stable draft and recognises the boundary between two matches.
 *
 * The tracker only clears a completed draft after it first observes several non-draft scans,
 * then sees the next draft UI on consecutive frames. This keeps transient animations, overlays,
 * and loading screens from wiping the board, while ensuring a new match receives a fresh board
 * and fresh build recommendations.
 */
class DraftTracker(
    private val requiredSightings: Int = 2,
    private val framesAwayBeforeNewMatch: Int = 4,
    private val draftSignalsBeforeReset: Int = 2,
) {
    private val pending = HashMap<String, Int>()
    private val committed = LinkedHashMap<String, Side>()
    private var missingDraftFrames = 0
    private var freshDraftSignals = 0
    private var state = DraftTrackingState.READING

    val confirmed: Map<String, Side> get() = LinkedHashMap(committed)
    val trackingState: DraftTrackingState get() = state

    /**
     * Returns a full lifecycle update. Callers should clear their draft session before applying
     * [DraftTrackingUpdate.newlyConfirmed] when [DraftTrackingUpdate.newMatchStarted] is true.
     */
    fun observe(result: DetectionResult): DraftTrackingUpdate {
        var newMatchStarted = false

        if (state == DraftTrackingState.BETWEEN_MATCHES) {
            if (!result.hasDraftSignal) {
                freshDraftSignals = 0
                return DraftTrackingUpdate(emptyList(), false, state)
            }

            freshDraftSignals += 1
            if (freshDraftSignals < draftSignalsBeforeReset) {
                return DraftTrackingUpdate(emptyList(), false, state)
            }

            clearEvidence()
            state = DraftTrackingState.READING
            newMatchStarted = true
        }

        val newlyConfirmed = submitCurrentDraft(result)

        if (result.heroes.isNotEmpty() || result.hasDraftSignal || result.hasEquipmentScreen) {
            missingDraftFrames = 0
        } else if (committed.isNotEmpty()) {
            missingDraftFrames += 1
            if (missingDraftFrames >= framesAwayBeforeNewMatch) {
                state = DraftTrackingState.BETWEEN_MATCHES
                freshDraftSignals = 0
            }
        }

        return DraftTrackingUpdate(newlyConfirmed, newMatchStarted, state)
    }

    /** Backwards-compatible shorthand for callers that only need newly confirmed heroes. */
    fun submit(result: DetectionResult): List<DetectedHero> = observe(result).newlyConfirmed

    private fun submitCurrentDraft(result: DetectionResult): List<DetectedHero> {
        val newlyConfirmed = mutableListOf<DetectedHero>()
        val seen = result.heroes.associateBy { it.heroId }

        seen.forEach { (id, detected) ->
            if (committed.containsKey(id)) return@forEach
            val count = (pending[id] ?: 0) + 1
            pending[id] = count
            if (count >= requiredSightings) {
                committed[id] = detected.side
                pending.remove(id)
                newlyConfirmed += detected
            }
        }

        // Evidence from separated moments should not add up to a false confirmation.
        pending.keys.filterNot { it in seen }.forEach { pending.remove(it) }
        return newlyConfirmed
    }

    fun reset() {
        clearEvidence()
        missingDraftFrames = 0
        freshDraftSignals = 0
        state = DraftTrackingState.READING
    }

    private fun clearEvidence() {
        pending.clear()
        committed.clear()
        missingDraftFrames = 0
    }
}
