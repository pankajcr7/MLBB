package com.pankaj.mlbbdraft.engine

import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.Component
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.PlayerProfile
import com.pankaj.mlbbdraft.engine.model.ScorePart
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.Suggestion
import com.pankaj.mlbbdraft.engine.report.ArchetypeAnalyzer
import com.pankaj.mlbbdraft.engine.report.ArchetypeVerdict
import com.pankaj.mlbbdraft.engine.report.BuildAdvisor
import com.pankaj.mlbbdraft.engine.report.CompReport
import com.pankaj.mlbbdraft.engine.report.CompReportBuilder
import com.pankaj.mlbbdraft.engine.report.HeroBuild
import com.pankaj.mlbbdraft.engine.report.ItemAdvice
import com.pankaj.mlbbdraft.engine.report.ItemAdvisor
import com.pankaj.mlbbdraft.engine.report.PickAssessment
import com.pankaj.mlbbdraft.engine.report.PickAssessor
import com.pankaj.mlbbdraft.engine.report.ThreatAnalyzer
import com.pankaj.mlbbdraft.engine.report.ThreatReport
import com.pankaj.mlbbdraft.engine.report.WinProbability
import com.pankaj.mlbbdraft.engine.report.WinProbabilityModel
import com.pankaj.mlbbdraft.engine.scoring.CompNeedAnalyzer
import com.pankaj.mlbbdraft.engine.scoring.MatchupScorer
import com.pankaj.mlbbdraft.engine.scoring.ReasonBuilder
import com.pankaj.mlbbdraft.engine.scoring.ScoreContext
import com.pankaj.mlbbdraft.engine.scoring.Weights

/**
 * The draft brain. Pure, synchronous and fast enough to re-run on every draft change.
 *
 * Everything here is deterministic and offline by design: a draft timer does not wait
 * for a network call, and advice you cannot reproduce is advice you cannot trust.
 */
class DraftEngine(
    val db: HeroDatabase,
    val weights: Weights = Weights.DEFAULT,
) {
    private val scorer = MatchupScorer(db, weights)
    private val buildAdvisor = BuildAdvisor(db)
    private val winModel = WinProbabilityModel(scorer)

    /** Full explainable score for one hero. Use this for a "why?" detail screen. */
    fun evaluate(hero: Hero, state: DraftState, lane: Lane? = state.myLane): Suggestion {
        val enemies = db.heroes(state.heroIds(Side.ENEMY))
        val allies = db.heroes(state.heroIds(Side.ALLY))
        val needs = CompNeedAnalyzer.needs(allies)

        val counter = scorer.counter(hero, enemies)
        val synergy = scorer.synergy(hero, allies)
        val exposure = scorer.exposure(hero, state)

        val parts = listOf(
            ScorePart(Component.COUNTER, counter.raw, weights.counter),
            ScorePart(Component.SYNERGY, synergy.raw, weights.synergy),
            ScorePart(Component.COMP_NEED, CompNeedAnalyzer.score(hero, needs), weights.compNeed),
            ScorePart(Component.META, metaRaw(hero, lane), weights.meta),
            ScorePart(Component.MASTERY, masteryRaw(hero, state.profile), weights.mastery),
            ScorePart(Component.EXPOSURE, exposure.raw, weights.exposure),
            ScorePart(Component.LANE_FIT, laneFitRaw(hero, lane), weights.laneFit),
        )

        val reasons = ReasonBuilder.build(
            ScoreContext(
                hero = hero,
                lane = lane,
                parts = parts,
                counter = counter,
                synergy = synergy,
                exposure = exposure,
                needs = needs,
                state = state,
            ),
        )

        return Suggestion(
            hero = hero,
            lane = lane,
            score = parts.sumOf { it.weighted } / weights.normaliser,
            parts = parts,
            reasons = reasons,
        )
    }

    /**
     * Best picks for [lane]. With a null lane, each hero is judged in the best open
     * lane they can fill, which is what you want when advising a whole draft.
     */
    fun suggestPicks(
        state: DraftState,
        lane: Lane? = state.myLane,
        limit: Int = 6,
    ): List<Suggestion> = candidates(state, lane)
        .map { hero -> evaluate(hero, state, lane ?: bestOpenLane(hero, state)) }
        .sortedByDescending { it.score }
        .take(limit)

    /** Best picks for every lane the team has not filled yet. */
    fun suggestByLane(state: DraftState, limit: Int = 3): Map<Lane, List<Suggestion>> =
        state.lanesOpen(Side.ALLY).associateWith { openLane ->
            suggestPicks(state, openLane, limit)
        }

    /**
     * Who to ban. A ban is worth spending on a hero that is both strong in the patch
     * and specifically bad for what you intend to play — so this reads your comfort
     * list as well as the picks already on the board.
     */
    fun suggestBans(state: DraftState, limit: Int = 5): List<Suggestion> {
        val allies = db.heroes(state.heroIds(Side.ALLY))
        val comfortPicks = state.profile.comfort
            .filterValues { it >= 3 }
            .keys
            .mapNotNull { db.hero(it) }
        val toProtect = (allies + comfortPicks).distinctBy { it.id }

        return db.heroes
            .filter { it.id !in state.usedHeroIds }
            .map { hero -> banSuggestion(hero, state, toProtect) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    fun compReport(state: DraftState, side: Side): CompReport =
        CompReportBuilder.build(side, db.heroes(state.heroIds(side)))

    /** What a side's draft is trying to do, and how to answer it. */
    fun archetype(state: DraftState, side: Side): ArchetypeVerdict =
        ArchetypeAnalyzer.classify(db.heroes(state.heroIds(side)))

    fun itemAdvice(state: DraftState): List<ItemAdvice> = ItemAdvisor.advise(
        enemies = db.heroes(state.heroIds(Side.ENEMY)),
        allies = db.heroes(state.heroIds(Side.ALLY)),
        confirmedBuildSignals = state.enemyBuildSignals,
    )

    /** A full counter-build for one hero you are playing, against the enemy draft. */
    fun buildFor(hero: Hero, state: DraftState, lane: Lane? = laneOf(hero, state)): HeroBuild =
        buildAdvisor.build(
            hero = hero,
            lane = lane,
            enemies = db.heroes(state.heroIds(Side.ENEMY)),
            allies = db.heroes(state.heroIds(Side.ALLY)),
        )

    /** Builds for every hero currently on your side of the board. */
    fun buildsForMyTeam(state: DraftState): List<HeroBuild> = state.picks(Side.ALLY)
        .mapNotNull { pick -> db.hero(pick.heroId)?.let { it to pick.lane } }
        .map { (hero, lane) -> buildFor(hero, state, lane) }

    /**
     * Verdicts on picks already locked in on [side], worst first.
     *
     * Answers "did my team just pick something bad into this enemy draft?" — which the
     * suggestion list cannot, because it only ranks heroes still available.
     */
    fun assessPicks(state: DraftState, side: Side = Side.ALLY): List<PickAssessment> {
        val enemies = db.heroes(state.heroIds(side.other))
        val allies = db.heroes(state.heroIds(side))
        if (enemies.isEmpty()) return emptyList()

        return state.picks(side).mapNotNull { pick ->
            val hero = db.hero(pick.heroId) ?: return@mapNotNull null
            val contributions = enemies.map { scorer.counterPair(hero, it) }
            val counteredBy = contributions
                .filter { it.value <= -0.25 }
                .sortedBy { it.value }
                .map { contribution ->
                    contribution.other to contribution.notes
                        .firstOrNull { it.startsWith("Risk") }
                        ?.removePrefix("Risk — ")
                }

            PickAssessor.assess(
                hero = hero,
                lane = pick.lane,
                matchup = scorer.counter(hero, enemies).raw,
                counteredBy = counteredBy,
                enemies = enemies,
                allies = allies,
            )
        }.sortedBy { it.verdict.ordinal }.sortedByDescending { it.verdict.needsAttention }
    }

    /** Just the picks worth warning about. */
    fun pickWarnings(state: DraftState, side: Side = Side.ALLY): List<PickAssessment> =
        assessPicks(state, side).filter { it.isProblem }

    /**
     * Who the draft favours. Explicitly a draft estimate — see [WinProbability].
     */
    fun winProbability(state: DraftState): WinProbability = winModel.evaluate(
        allies = db.heroes(state.heroIds(Side.ALLY)),
        enemies = db.heroes(state.heroIds(Side.ENEMY)),
        profile = state.profile,
    )

    private fun laneOf(hero: Hero, state: DraftState): Lane? =
        state.picks(Side.ALLY).firstOrNull { it.heroId == hero.id }?.lane
            ?: hero.lanes.firstOrNull()

    fun threatReport(state: DraftState): ThreatReport = ThreatAnalyzer.analyze(
        enemies = db.heroes(state.heroIds(Side.ENEMY)),
        allies = db.heroes(state.heroIds(Side.ALLY)),
    )

    // --- internals ---

    private fun candidates(state: DraftState, lane: Lane?): List<Hero> = db.heroes.filter { hero ->
        hero.id !in state.usedHeroIds &&
            state.profile.canPlay(hero.id) &&
            (lane == null || lane in hero.lanes)
    }

    private fun bestOpenLane(hero: Hero, state: DraftState): Lane? {
        val open = state.lanesOpen(Side.ALLY)
        return hero.lanes
            .filter { it in open }
            .maxByOrNull { hero.tier[it] ?: 0.0 }
            ?: hero.lanes.maxByOrNull { hero.tier[it] ?: 0.0 }
    }

    private fun metaRaw(hero: Hero, lane: Lane?): Double =
        ((hero.tierIn(lane) ?: 5.0) / 10.0 - 0.5) * 2.0

    /**
     * With no profile set up, fall back to a mild preference for lower-difficulty
     * heroes — a hero you cannot execute is not a good suggestion.
     */
    private fun masteryRaw(hero: Hero, profile: PlayerProfile): Double = if (profile.isConfigured) {
        (profile.comfortOf(hero.id) / 5.0 - 0.5) * 2.0
    } else {
        ((3 - hero.difficulty) / 2.0) * 0.4
    }

    private fun laneFitRaw(hero: Hero, lane: Lane?): Double = when {
        lane == null -> 0.0
        lane in hero.lanes -> 1.0
        else -> -1.0
    }

    private fun banSuggestion(hero: Hero, state: DraftState, toProtect: List<Hero>): Suggestion {
        val metaRaw = metaRaw(hero, null)
        val threat = if (toProtect.isEmpty()) {
            null
        } else {
            val contributions = toProtect.map { scorer.counterPair(hero, it) }
            val values = contributions.map { it.value }
            contributions to (values.average() * 0.6 + values.max() * 0.4).coerceIn(-1.0, 1.0)
        }
        val oppressionRaw =
            ((hero.attrs.pickPotential + hero.attrs.burst) / 20.0 - 0.5) * 2.0

        val parts = listOf(
            ScorePart(Component.META, metaRaw, 1.0),
            ScorePart(Component.COUNTER, threat?.second ?: 0.0, 1.0),
            ScorePart(Component.COMP_NEED, oppressionRaw, 0.4),
        )

        val reasons = buildList {
            val bestTier = hero.tier.values.maxOrNull() ?: 0.0
            if (bestTier >= 8.0) add("Top-tier in this patch (${"%.1f".format(bestTier)}/10)")
            threat?.first
                ?.filter { it.value >= 0.3 }
                ?.sortedByDescending { it.value }
                ?.take(2)
                ?.forEach { contribution ->
                    val target = contribution.other.name
                    val why = contribution.notes.firstOrNull { !it.startsWith("Risk") }
                    add(if (why != null) "Beats your $target — $why" else "Strong against your $target")
                }
            if (hero.attrs.pickPotential >= 9) add("Can delete a carry on their own")
            if (hero.difficulty >= 5) add("High skill ceiling — only worth banning against a player who has it")
        }

        return Suggestion(
            hero = hero,
            lane = null,
            score = parts.sumOf { it.weighted } / 2.4,
            parts = parts,
            reasons = reasons.take(4),
        )
    }
}
