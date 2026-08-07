package com.pankaj.mlbbdraft.engine.vision

import com.pankaj.mlbbdraft.engine.model.Hero

/**
 * Matches a line of text recognised on screen to a hero.
 *
 * OCR output is dirty: `Yu Zhong` comes back as `YuZhong`, `Zh0ng` or `Yu Zhong.`, and
 * the draft screen is full of text that is not a hero name at all. So matching is
 * normalise-then-tolerate-typos, with a hard floor on how much error is allowed —
 * a false positive silently corrupts the draft, which is worse than missing a hero.
 */
class HeroTextMatcher(heroes: List<Hero>) {

    private data class Entry(val id: String, val key: String)

    private val entries: List<Entry> = heroes.map { Entry(it.id, normalise(it.name)) }
    private val exact: Map<String, String> = entries.associate { it.key to it.id }

    /** Shorter than this and OCR noise starts matching real heroes. */
    private val minLength = 3

    /**
     * @return hero id, or null when nothing matches closely enough.
     */
    fun match(text: String): String? {
        val key = normalise(text)
        if (key.length < minLength) return null

        exact[key]?.let { return it }

        // Some hero names contain another as a substring once punctuation is stripped,
        // so an exact hit always wins before fuzzy matching is considered.
        val budget = when {
            key.length >= 8 -> 2
            key.length >= 5 -> 1
            else -> 0
        }
        if (budget == 0) return null

        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var tied = false

        entries.forEach { entry ->
            // Length gate first: it is far cheaper than the edit distance itself.
            if (kotlin.math.abs(entry.key.length - key.length) > budget) return@forEach
            val distance = editDistance(key, entry.key, budget)
            if (distance <= budget) {
                when {
                    distance < bestDistance -> {
                        bestDistance = distance
                        best = entry.id
                        tied = false
                    }

                    distance == bestDistance && entry.id != best -> tied = true
                }
            }
        }

        // An ambiguous match is a guess. Two heroes one edit apart from the same garbled
        // text means we do not know which, so report nothing.
        return if (tied) null else best
    }

    private fun normalise(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /** Levenshtein, abandoned early once the row minimum exceeds [budget]. */
    private fun editDistance(a: String, b: String, budget: Int): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > budget) return Int.MAX_VALUE
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
