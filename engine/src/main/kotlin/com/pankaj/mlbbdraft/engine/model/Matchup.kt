package com.pankaj.mlbbdraft.engine.model

import kotlinx.serialization.Serializable

/**
 * "[hero] counters [vs], by [weight]."
 *
 * weight is -1..1. Positive means [hero] is favoured. A negative weight is legal and
 * useful when the popular belief is backwards and you want to record that explicitly.
 *
 * Only one direction needs to be authored: the engine reads both
 * `hero -> vs` and `vs -> hero` and takes the difference, so authoring
 * "khufra counters fanny +0.8" already makes Fanny a bad pick into Khufra.
 */
@Serializable
data class MatchupEdge(
    val hero: String,
    val vs: String,
    val weight: Double,
    /** Shown verbatim to the user as a reason. Write it as advice, not trivia. */
    val note: String? = null,
) {
    init {
        require(weight in -1.0..1.0) { "MatchupEdge weight must be -1..1, got $this" }
    }
}

/** Symmetric: order of [a] and [b] does not matter. */
@Serializable
data class SynergyEdge(
    val a: String,
    val b: String,
    val weight: Double,
    val note: String? = null,
) {
    init {
        require(weight in -1.0..1.0) { "SynergyEdge weight must be -1..1, got $this" }
    }
}

/**
 * The dataset is split across several small files instead of one large one so that
 * adding heroes stays a low-friction edit. [DatasetManifest] is the only file the
 * loader knows by name.
 */
@Serializable
data class DatasetManifest(
    val patch: String,
    val heroFiles: List<String>,
    val matchupFiles: List<String> = emptyList(),
    val itemFiles: List<String> = emptyList(),
)

@Serializable
data class HeroFile(
    val heroes: List<Hero>,
)

@Serializable
data class MatchupFile(
    val counters: List<MatchupEdge> = emptyList(),
    val synergies: List<SynergyEdge> = emptyList(),
)
