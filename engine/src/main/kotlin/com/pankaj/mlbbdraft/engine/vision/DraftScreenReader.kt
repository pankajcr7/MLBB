package com.pankaj.mlbbdraft.engine.vision

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.Side

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
) {
    fun ids(side: Side): List<String> = heroes.filter { it.side == side }.map { it.heroId }

    val isEmpty: Boolean get() = heroes.isEmpty()
}

/**
 * Turns recognised on-screen text into "these heroes are on the left, those on the right".
 *
 * Reading names rather than portrait pixels is the whole trick: it needs no knowledge of
 * where MLBB draws its pick slots, so it survives a phone, aspect ratio or UI update that
 * would break fixed crop regions.
 *
 * Sides come from horizontal position — the draft screen puts your team on one side and
 * the enemy on the other — which is the one layout fact that holds everywhere.
 */
class DraftScreenReader(
    db: HeroDatabase,
    /** Set false if your team appears on the right instead of the left. */
    private val allyOnLeft: Boolean = true,
) {
    private val matcher = HeroTextMatcher(db.heroes)

    fun read(lines: List<ScreenText>, frameWidth: Int): DetectionResult {
        if (frameWidth <= 0) return DetectionResult(emptyList(), emptyList())

        val midpoint = frameWidth / 2
        val found = LinkedHashMap<String, DetectedHero>()
        val unmatched = mutableListOf<String>()

        lines.forEach { line ->
            val id = matcher.match(line.text)
            if (id == null) {
                if (line.text.isNotBlank()) unmatched += line.text
                return@forEach
            }
            val leftSide = line.centerX < midpoint
            val side = if (leftSide == allyOnLeft) Side.ALLY else Side.ENEMY

            // The same hero cannot be on both teams; keep the first sighting so a stray
            // second read (a tooltip, a kill feed) cannot flip them across sides.
            found.getOrPut(id) { DetectedHero(id, side, line.centerX, line.centerY) }
        }

        return DetectionResult(
            heroes = found.values.sortedWith(compareBy({ it.side }, { it.centerY }, { it.centerX })),
            unmatched = unmatched.distinct().take(12),
        )
    }
}

/**
 * Smooths noisy frame-by-frame reads into a stable draft.
 *
 * Two rules that matter more than the detection itself:
 *
 *  * **Confirm before committing.** A hero must appear in the same side on
 *    [requiredSightings] consecutive frames. OCR misreads are usually single-frame.
 *  * **Never un-commit.** Draft picks only ever get added, so a frame where the text was
 *    obscured cannot wipe a hero you already saw. This alone removes most of the flicker.
 */
class DraftTracker(
    private val requiredSightings: Int = 2,
) {
    private val pending = HashMap<String, Int>()
    private val committed = LinkedHashMap<String, Side>()

    val confirmed: Map<String, Side> get() = LinkedHashMap(committed)

    /** @return heroes newly confirmed by this frame, in detection order. */
    fun submit(result: DetectionResult): List<DetectedHero> {
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

        // A hero that stops appearing before being confirmed was probably a misread, so
        // let their evidence decay rather than counting sightings from separate moments.
        pending.keys.filterNot { it in seen }.forEach { pending.remove(it) }

        return newlyConfirmed
    }

    fun reset() {
        pending.clear()
        committed.clear()
    }
}
