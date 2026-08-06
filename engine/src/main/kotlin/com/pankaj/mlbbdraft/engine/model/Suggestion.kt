package com.pankaj.mlbbdraft.engine.model

/** One scoring axis's contribution to a suggestion. */
data class ScorePart(
    val component: Component,
    /** Normalised to roughly -1..1 so components stay comparable. */
    val raw: Double,
    val weight: Double,
) {
    val weighted: Double get() = raw * weight
}

data class Suggestion(
    val hero: Hero,
    val lane: Lane?,
    val score: Double,
    val parts: List<ScorePart>,
    /** Human-readable, ordered strongest first. Safe to show verbatim. */
    val reasons: List<String>,
) {
    fun part(component: Component): ScorePart? = parts.firstOrNull { it.component == component }

    /** 0..100, for display only. */
    val displayScore: Int get() = ((score + 1.0) / 2.0 * 100).coerceIn(0.0, 100.0).toInt()
}
