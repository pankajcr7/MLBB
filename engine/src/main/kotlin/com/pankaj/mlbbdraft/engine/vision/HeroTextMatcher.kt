package com.pankaj.mlbbdraft.engine.vision

import com.pankaj.mlbbdraft.engine.model.Hero

/**
 * Matches recognised on-screen text to a hero.
 *
 * OCR output is dirty: `Yu Zhong` may come back as `YuZhong`, `Zh0ng`, or `Yu Zhong.`.
 * Real draft captures also display skin titles such as `S.A.B.E.R. Regulator`, where a hero
 * name is a complete token inside a longer label. A valid name token is safe to use; a visual
 * guess from a portrait is not, so this matcher deliberately returns null when a line contains
 * multiple different hero names or no sufficiently specific text.
 */
class HeroTextMatcher(heroes: List<Hero>) {

    private data class Entry(val id: String, val key: String)

    /** Shorter than this and OCR noise starts matching real heroes. */
    private val minLength = 3

    private val entries: List<Entry> = heroes.flatMap { hero ->
        (setOf(hero.name) + hero.aliases)
            .map(::normalise)
            .filter { it.length >= minLength }
            .map { Entry(hero.id, it) }
    }
    // A colliding alias is ambiguous and deliberately omitted rather than guessed.
    private val exact: Map<String, String> = entries
        .groupBy { it.key }
        .mapNotNull { (key, matches) ->
            matches.map { it.id }.distinct().singleOrNull()?.let { key to it }
        }
        .toMap()

    /**
     * @return a hero id, or null when nothing matches closely enough.
     */
    fun match(text: String): String? {
        val key = normalise(text)
        if (key.length < minLength) return null

        exact[key]?.let { return it }
        exactHeroToken(text)?.let { return it }
        return fuzzyWholeLineMatch(key)
    }

    /**
     * A skin title often begins with a hero name but its complete label cannot equal the name.
     * Split only on whitespace so punctuated hero names such as `Chang'e` and `S.A.B.E.R.` stay
     * intact through normalisation. Multiple different names are an ambiguous draft hint and are
     * intentionally rejected.
     */
    private fun exactHeroToken(text: String): String? {
        val matches = text
            .split(Regex("\\s+"))
            .asSequence()
            .map(::normalise)
            .filter { it.length >= minLength }
            .mapNotNull(exact::get)
            .distinct()
            .toList()

        return matches.singleOrNull()
    }

    private fun fuzzyWholeLineMatch(key: String): String? {
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
