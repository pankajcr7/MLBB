package com.pankaj.mlbbdraft.engine.scoring

/**
 * Relative importance of each scoring axis.
 *
 * These are the knobs to tune once you have match results to learn from. Keep them
 * here rather than scattered through the scorers so a per-user tuned copy is a
 * single object to store.
 */
data class Weights(
    val counter: Double = 1.0,
    val synergy: Double = 0.55,
    val compNeed: Double = 0.85,
    val meta: Double = 0.7,
    val mastery: Double = 0.6,
    /** Applied to a negative raw value: how much a likely counter-pick should scare us. */
    val exposure: Double = 0.7,
    val laneFit: Double = 0.5,
    /**
     * How much trait/attribute heuristics count relative to hand-authored matchup
     * edges. Lower this as the authored dataset grows.
     */
    val heuristicBlend: Double = 0.45,
) {
    /** Used to normalise the weighted sum back into roughly -1..1. */
    internal val normaliser: Double
        get() = counter + synergy + compNeed + meta + mastery + exposure + laneFit

    companion object {
        val DEFAULT = Weights()
    }
}
