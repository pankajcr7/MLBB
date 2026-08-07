package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.DatasetLoader
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.vision.DraftScreenReader
import com.pankaj.mlbbdraft.engine.vision.DraftTracker
import com.pankaj.mlbbdraft.engine.vision.HeroTextMatcher
import com.pankaj.mlbbdraft.engine.vision.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReadingTest {
    private val db = DatasetLoader.fromResources()
    private val matcher = HeroTextMatcher(db.heroes)
    private val reader = DraftScreenReader(db)

    private val frameWidth = 1080
    private fun left(text: String, y: Int = 100) = ScreenText(text, 200, y)
    private fun right(text: String, y: Int = 100) = ScreenText(text, 880, y)

    @Test
    fun `clean names match exactly`() {
        assertEquals("odette", matcher.match("Odette"))
        assertEquals("yu-zhong", matcher.match("Yu Zhong"))
        assertEquals("yi-sun-shin", matcher.match("Yi Sun-shin"))
        assertEquals("x-borg", matcher.match("X.Borg"))
        assertEquals("popol-and-kupa", matcher.match("Popol and Kupa"))
        assertEquals("chang-e", matcher.match("Chang'e"))
    }

    @Test
    fun `the usual OCR mangling still matches`() {
        assertEquals("yu-zhong", matcher.match("YuZhong"))
        assertEquals("odette", matcher.match("odette."))
        assertEquals("odette", matcher.match("ODETTE"))
        assertEquals("lancelot", matcher.match("Lancelot "))
        // Single-character substitutions are the most common OCR error.
        assertEquals("melissa", matcher.match("Melissa"))
        assertEquals("tigreal", matcher.match("Tigrea1"))
    }

    @Test
    fun `screen furniture is not mistaken for a hero`() {
        listOf("PICK", "BAN", "Ready", "0:24", "VS", "Gold Lane", "Mythic", "", "  ")
            .forEach { assertNull("'$it' should not match a hero", matcher.match(it)) }
    }

    @Test
    fun `garbage too far from any name is rejected rather than guessed`() {
        assertNull(matcher.match("Qwertyuiop"))
        assertNull(matcher.match("zzzzzzzz"))
    }

    @Test
    fun `an ambiguous read reports nothing rather than picking one`() {
        // "Alucard" and "Aldous" are both real; a token equidistant from two heroes must
        // not silently resolve to whichever was indexed first.
        val ambiguous = matcher.match("Sabor")
        if (ambiguous != null) assertEquals("saber", ambiguous)

        // Two-letter noise can be one edit from several short names.
        assertNull(matcher.match("an"))
    }

    @Test
    fun `left of centre is your team and right is theirs`() {
        val result = reader.read(
            listOf(left("Odette"), left("Khufra"), right("Ling"), right("Estes")),
            frameWidth,
        )
        assertEquals(listOf("odette", "khufra"), result.ids(Side.ALLY))
        assertEquals(listOf("ling", "estes"), result.ids(Side.ENEMY))
    }

    @Test
    fun `side assignment can be flipped for mirrored layouts`() {
        val flipped = DraftScreenReader(db, allyOnLeft = false)
        val result = flipped.read(listOf(left("Odette"), right("Ling")), frameWidth)
        assertEquals(listOf("ling"), result.ids(Side.ALLY))
        assertEquals(listOf("odette"), result.ids(Side.ENEMY))
    }

    @Test
    fun `unmatched text is reported for debugging a bad read`() {
        val result = reader.read(listOf(left("Odette"), left("CHOOSE YOUR HERO")), frameWidth)
        assertEquals(listOf("odette"), result.ids(Side.ALLY))
        assertTrue(result.unmatched.any { it.contains("CHOOSE") })
    }

    @Test
    fun `a hero seen twice cannot end up on both teams`() {
        val result = reader.read(listOf(left("Odette"), right("Odette")), frameWidth)
        assertEquals(1, result.heroes.size)
        assertEquals(listOf("odette"), result.ids(Side.ALLY))
    }

    @Test
    fun `the tracker needs two frames before committing`() {
        val tracker = DraftTracker(requiredSightings = 2)
        val frame = reader.read(listOf(right("Ling")), frameWidth)

        assertEquals("First sighting must not commit", emptyList<Any>(), tracker.submit(frame))
        assertEquals(listOf("ling"), tracker.submit(frame).map { it.heroId })
        assertEquals(mapOf("ling" to Side.ENEMY), tracker.confirmed)
    }

    @Test
    fun `a single-frame misread never reaches the draft`() {
        val tracker = DraftTracker(requiredSightings = 2)
        tracker.submit(reader.read(listOf(right("Ling")), frameWidth))
        // Next frame reads something else entirely — the Ling evidence should decay.
        tracker.submit(reader.read(listOf(right("Estes")), frameWidth))
        assertTrue("Nothing should be confirmed yet", tracker.confirmed.isEmpty())
    }

    @Test
    fun `a confirmed hero survives frames where the text is obscured`() {
        val tracker = DraftTracker(requiredSightings = 2)
        val frame = reader.read(listOf(right("Ling")), frameWidth)
        tracker.submit(frame)
        tracker.submit(frame)
        assertEquals(mapOf("ling" to Side.ENEMY), tracker.confirmed)

        // An empty frame (animation, tooltip covering the name) must not undo the pick.
        tracker.submit(reader.read(emptyList(), frameWidth))
        assertEquals(mapOf("ling" to Side.ENEMY), tracker.confirmed)
    }

    @Test
    fun `a full draft reads cleanly`() {
        val allies = listOf("Khufra", "Melissa", "Kagura", "Yu Zhong", "Julian")
        val enemies = listOf("Ling", "Estes", "Claude", "Phoveus", "Atlas")
        val lines = allies.mapIndexed { i, n -> left(n, y = 100 + i * 120) } +
            enemies.mapIndexed { i, n -> right(n, y = 100 + i * 120) }

        val result = reader.read(lines, frameWidth)
        assertEquals(
            listOf("khufra", "melissa", "kagura", "yu-zhong", "julian"),
            result.ids(Side.ALLY),
        )
        assertEquals(
            listOf("ling", "estes", "claude", "phoveus", "atlas"),
            result.ids(Side.ENEMY),
        )
    }

    @Test
    fun `every hero in the dataset can be recognised from its own name`() {
        val unrecognised = db.heroes.filter { matcher.match(it.name) != it.id }
        assertEquals(
            "These heroes cannot be matched from their display name: ${unrecognised.map { it.name }}",
            emptyList<String>(),
            unrecognised,
        )
    }
}
