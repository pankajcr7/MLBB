package com.pankaj.mlbbdraft

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pankaj.mlbbdraft.data.MetaRepository
import com.pankaj.mlbbdraft.data.ProfileStore
import com.pankaj.mlbbdraft.data.SuggestionSpeaker
import com.pankaj.mlbbdraft.data.SyncOutcome
import com.pankaj.mlbbdraft.engine.DraftEngine
import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind
import com.pankaj.mlbbdraft.engine.vision.EquipmentScreenshotImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Which analysis the user is looking at. */
enum class AnalysisTab(val label: String) {
    PICKS("Picks"),
    BANS("Bans"),
    BUILD("Build"),
    COMP("Comp"),
    THREATS("Threats"),
}

/** The board slot the hero picker is filling. */
data class SlotTarget(val side: Side, val kind: StepKind, val slot: Int)

/**
 * The draft, shared by the main screen and the floating overlay.
 *
 * Application-scoped rather than a ViewModel because the overlay lives in a Service:
 * both surfaces have to read and write *the same* draft, or you would enter picks in the
 * overlay and see nothing when you opened the app. Compose snapshot state means either
 * surface updating the board re-renders the other automatically.
 */
class DraftSession(
    private val baseDb: HeroDatabase,
    private val profileStore: ProfileStore,
    private val metaRepository: MetaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Created lazily by whichever surface starts first; both share it. */
    private var speaker: SuggestionSpeaker? = null

    /** Swapped when a meta sync lands; every derived value below then recomputes. */
    private var engine by mutableStateOf(DraftEngine(baseDb))

    var draft by mutableStateOf(
        DraftState.forMode(DraftMode.RANKED).copy(profile = profileStore.load()),
    )
        private set

    var metaStatus by mutableStateOf("Bundled data")
        private set

    var syncing by mutableStateOf(false)
        private set

    var tab by mutableStateOf(AnalysisTab.PICKS)
        private set

    /** null means "advise every open lane at once". */
    var laneFilter by mutableStateOf<Lane?>(null)
        private set

    var picker by mutableStateOf<SlotTarget?>(null)
        private set

    var showProfile by mutableStateOf(false)
        private set

    /** Which of your own picks the Build tab is showing. Null follows the first pick. */
    var buildHeroId by mutableStateOf<String?>(null)
        private set

    /** True while the floating overlay is showing, so the app can reflect it. */
    var overlayRunning by mutableStateOf(false)

    /** True while the screen is being read for hero names. */
    var autoDetecting by mutableStateOf(false)

    /** Human-readable result of the last screen read, shown in the overlay. */
    var detectionStatus by mutableStateOf("")

    /** True only while the user has asked the active screen reader to inspect an enemy build. */
    var enemyBuildScanRequested by mutableStateOf(false)
        private set

    /** True while a gallery Equipment screenshot is being decoded and read on-device. */
    var screenshotImporting by mutableStateOf(false)
        private set

    /** Items explicitly named by red-side OCR in the current match; never inferred from icon art. */
    var confirmedEnemyItems by mutableStateOf<List<Item>>(emptyList())
        private set

    /** Reader for the bundled dataset, used by the screen-reading service. */
    val heroDatabase: HeroDatabase get() = engine.db

    val patch: String get() = engine.db.patch
    val allHeroes: List<Hero> get() = engine.db.heroes
    val feedUrl: String get() = metaRepository.feedUrl

    val suggestions by derivedStateOf { engine.suggestPicks(draft, laneFilter, limit = 8) }
    val banSuggestions by derivedStateOf { engine.suggestBans(draft, limit = 8) }
    val allyReport by derivedStateOf { engine.compReport(draft, Side.ALLY) }
    val enemyReport by derivedStateOf { engine.compReport(draft, Side.ENEMY) }
    val itemAdvice by derivedStateOf { engine.itemAdvice(draft) }
    val threatReport by derivedStateOf { engine.threatReport(draft) }
    val builds by derivedStateOf { engine.buildsForMyTeam(draft) }
    val winProbability by derivedStateOf { engine.winProbability(draft) }

    /** Verdicts on your locked picks; [pickWarnings] is just the ones with problems. */
    val pickAssessments by derivedStateOf { engine.assessPicks(draft, Side.ALLY) }
    val pickWarnings by derivedStateOf { engine.pickWarnings(draft, Side.ALLY) }

    /** What each draft is trying to do, in one label. */
    val enemyArchetype by derivedStateOf { engine.archetype(draft, Side.ENEMY) }
    val allyArchetype by derivedStateOf { engine.archetype(draft, Side.ALLY) }

    var speakSuggestions by mutableStateOf(profileStore.speakSuggestions)
        private set

    /** Which team the overlay's quick-add drops heroes into. */
    var quickAddSide by mutableStateOf(Side.ENEMY)

    val buildHero: Hero? by derivedStateOf {
        builds.firstOrNull { it.hero.id == buildHeroId }?.hero ?: builds.firstOrNull()?.hero
    }

    /** The exact open slot dictated by draft order, used by the speed-first UI. */
    val activeTarget: SlotTarget?
        get() = draft.currentStep?.let { SlotTarget(it.side, it.kind, it.slot) }

    /** A suggested hero can be locked directly whenever your team has room for another pick. */
    val canLockSuggestedPick: Boolean
        get() = draft.pickSlots(Side.ALLY).any { it == null }

    init {
        applyCache()
        scope.launch { runSync(force = false) }
    }

    fun hero(id: String): Hero? = engine.db.hero(id)

    fun search(query: String): List<Hero> = engine.db.search(query)

    /** Heroes still available, best in this lane first — the overlay's quick-add list. */
    fun available(lane: Lane?, query: String, limit: Int = 12): List<Hero> =
        engine.db.search(query)
            .filter { it.id !in draft.usedHeroIds }
            .filter { lane == null || lane in it.lanes }
            .sortedByDescending { it.tier.values.maxOrNull() ?: 0.0 }
            .take(limit)

    fun selectTab(value: AnalysisTab) {
        tab = value
    }

    fun selectLane(lane: Lane?) {
        laneFilter = lane
        draft = draft.copy(myLane = lane)
    }

    fun setMode(mode: DraftMode) {
        draft = DraftState.forMode(mode, draft.firstPick)
            .copy(myLane = draft.myLane, profile = draft.profile)
    }

    fun swapFirstPick() {
        draft = draft.copy(firstPick = draft.firstPick.other)
    }

    /** Ranked gives 3, 4 or 5 bans per side depending on rank. */
    fun setBansPerSide(count: Int) {
        draft = draft.withBansPerSide(count)
    }

    /** Adds or removes a player-confirmed property of the enemy's current item build. */
    fun toggleEnemyBuildSignal(signal: EnemyBuildSignal) {
        draft = draft.withEnemyBuildSignal(signal, signal !in draft.enemyBuildSignals)
    }

    /**
     * Requests a one-shot inspection of the next visible red-side Equipment screen. The capture
     * service owns the actual OCR; this flag allows Build to reuse an existing screen-share
     * session instead of creating a second MediaProjection token.
     */
    fun requestEnemyBuildScan() {
        enemyBuildScanRequested = true
        tab = AnalysisTab.BUILD
        detectionStatus = "Build scan requested — open the red enemy Equipment screen."
    }

    /** Applies only item names recognised explicitly by OCR; unlabeled icons remain unguessed. */
    fun applyEnemyBuildScan(itemNames: List<String>, signals: Set<EnemyBuildSignal>) {
        enemyBuildScanRequested = false
        recordConfirmedEnemyItems(itemNames)
        if (signals.isNotEmpty()) {
            draft = draft.copy(enemyBuildSignals = draft.enemyBuildSignals + signals)
        }
        detectionStatus = if (itemNames.isNotEmpty()) {
            "Equipment scan successful — ${itemNames.size} enemy items confirmed: ${itemNames.joinToString()}. Counter items updated."
        } else {
            "Enemy Equipment is visible, but no item names were readable. Confirm traits below."
        }
    }

    fun expireEnemyBuildScan() {
        if (!enemyBuildScanRequested) return
        enemyBuildScanRequested = false
        detectionStatus = "Build scan timed out — keep the red Equipment screen open, then scan again."
    }

    /** Opens the image-selection flow; the Activity later calls [applyScreenshotImport]. */
    fun beginScreenshotImport() {
        if (screenshotImporting) return
        screenshotImporting = true
        tab = AnalysisTab.BUILD
        detectionStatus = "Reading selected Equipment screenshot…"
    }

    fun cancelScreenshotImport() {
        if (!screenshotImporting) return
        screenshotImporting = false
        detectionStatus = "No build screenshot selected."
    }

    /**
     * Applies only OCR-confirmed item signals from a selected Equipment scoreboard. Hero labels
     * from the red roster are added conservatively; icon artwork is never treated as an item name.
     */
    fun applyScreenshotImport(
        imported: EquipmentScreenshotImport?,
        failureReason: String? = null,
    ) {
        screenshotImporting = false
        if (failureReason != null) {
            detectionStatus = failureReason
            return
        }
        val evidence = imported ?: run {
            detectionStatus = "The screenshot could not be read. Try another MLBB Equipment screen."
            return
        }
        if (!evidence.isEquipmentScreen) {
            detectionStatus = "That image is not an MLBB Equipment screen. Choose the red-team scoreboard."
            return
        }

        evidence.enemyHeroIds.forEach { quickAdd(Side.ENEMY, it) }
        recordConfirmedEnemyItems(evidence.itemNames)
        if (evidence.signals.isNotEmpty()) {
            draft = draft.copy(enemyBuildSignals = draft.enemyBuildSignals + evidence.signals)
        }

        val heroNames = evidence.enemyHeroIds.mapNotNull { hero(it)?.name }
        detectionStatus = when {
            evidence.itemNames.isNotEmpty() -> buildString {
                append("Screenshot scan successful — ${evidence.itemNames.size} enemy items confirmed: ")
                append(evidence.itemNames.joinToString())
                if (heroNames.isNotEmpty()) append(" · enemy heroes: ${heroNames.joinToString()}")
                append(". Counter items updated.")
            }

            heroNames.isNotEmpty() ->
                "Imported enemy heroes: ${heroNames.joinToString()}. ${evidence.visibleEnemyItemSlots} red-side item icons are visible, but none passed confidence. Confirm traits below."

            else ->
                "Enemy Equipment found: ${evidence.visibleEnemyItemSlots} red-side item icons are visible, but none passed confidence. Confirm traits below."
        }
    }

    private fun recordConfirmedEnemyItems(itemNames: List<String>) {
        val resolved = itemNames.mapNotNull { name ->
            heroDatabase.items.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        if (resolved.isNotEmpty()) {
            confirmedEnemyItems = (confirmedEnemyItems + resolved).distinctBy { it.id }
        }
    }

    fun reset() {
        draft = draft.cleared()
        enemyBuildScanRequested = false
        screenshotImporting = false
        confirmedEnemyItems = emptyList()
        speaker?.forget()
    }

    fun toggleSpeakSuggestions() {
        speakSuggestions = !speakSuggestions
        profileStore.speakSuggestions = speakSuggestions
        if (!speakSuggestions) speaker?.stop()
    }

    /**
     * Reads the current top pick aloud, if speech is on. Called when the board changes
     * rather than on every recomposition; [SuggestionSpeaker] de-duplicates the rest.
     */
    fun announceTopPick() {
        if (!speakSuggestions) return
        val speaker = speaker ?: return
        if (draft.remainingPicks(Side.ALLY) == 0) return

        val top = suggestions.firstOrNull() ?: return
        val countered = top.reasons.firstOrNull()?.takeIf { it.length < 90 }
        val line = buildString {
            append("Pick ${top.hero.name}")
            countered?.let { append(". $it") }
        }
        speaker.announce(key = "pick:${top.hero.id}:${draft.usedHeroIds.size}", text = line)
    }

    fun attachSpeaker(value: SuggestionSpeaker) {
        speaker = value
    }

    fun openPicker(target: SlotTarget) {
        picker = target
    }

    /** Opens the next required draft action, avoiding a board scan under the draft timer. */
    fun openActiveAction() {
        activeTarget?.let(::openPicker)
    }

    fun closePicker() {
        picker = null
    }

    fun assign(heroId: String?) {
        val target = picker ?: return
        fill(target, heroId)
        picker = null
    }

    fun clearSlot(target: SlotTarget) {
        fill(target, null)
    }

    /**
     * Drops a hero into the first empty pick slot on [side] — the overlay's one-tap add and
     * the entry point for auto-detection. Already-drafted heroes are ignored so a repeated
     * screen read cannot duplicate a pick.
     */
    fun quickAdd(side: Side, heroId: String) {
        if (heroId in draft.usedHeroIds) return
        val slot = draft.pickSlots(side).indexOfFirst { it == null }
        if (slot < 0) return
        fill(SlotTarget(side, StepKind.PICK, slot), heroId)
    }

    /**
     * Locks a recommendation into the active allied pick when it is our turn. If the board
     * is being reconstructed manually, it falls back to the first available allied slot.
     */
    fun lockSuggestedPick(heroId: String) {
        if (heroId in draft.usedHeroIds) return
        val active = activeTarget
        val target = when {
            active?.kind == StepKind.PICK && active.side == Side.ALLY -> active
            else -> draft.pickSlots(Side.ALLY)
                .indexOfFirst { it == null }
                .takeIf { it >= 0 }
                ?.let { SlotTarget(Side.ALLY, StepKind.PICK, it) }
        } ?: return
        fill(target, heroId)
    }

    /** Locks a ban recommendation only when the draft is waiting for a ban. */
    fun lockSuggestedBan(heroId: String) {
        if (heroId in draft.usedHeroIds) return
        val target = activeTarget?.takeIf { it.kind == StepKind.BAN } ?: return
        fill(target, heroId)
    }

    /**
     * Commits heroes the screen reader has confirmed. Detection only ever *adds* — it never
     * clears a slot, so a frame where a name was covered cannot undo a pick you saw.
     */
    fun applyDetected(detected: Map<String, Side>) {
        detected.forEach { (heroId, side) -> quickAdd(side, heroId) }
    }

    fun undoLast(side: Side) {
        val slot = draft.pickSlots(side).indexOfLast { it != null }
        if (slot < 0) return
        fill(SlotTarget(side, StepKind.PICK, slot), null)
    }

    fun selectBuildHero(heroId: String) {
        buildHeroId = heroId
    }

    fun openProfile() {
        showProfile = true
    }

    fun closeProfile() {
        showProfile = false
        profileStore.save(draft.profile)
    }

    fun setComfort(heroId: String, comfort: Int) {
        val updated = draft.profile.comfort.toMutableMap()
        if (comfort <= 0) updated.remove(heroId) else updated[heroId] = comfort.coerceAtMost(5)
        val profile = draft.profile.copy(comfort = updated, owned = updated.keys.toSet())
        draft = draft.copy(profile = profile)
        profileStore.save(profile)
    }

    fun toggleRestrictToOwned() {
        val profile = draft.profile.copy(restrictToOwned = !draft.profile.restrictToOwned)
        draft = draft.copy(profile = profile)
        profileStore.save(profile)
    }

    // --- live meta ---

    fun syncNow() {
        if (syncing) return
        scope.launch { runSync(force = true) }
    }

    fun setFeedUrl(url: String) {
        metaRepository.feedUrl = url
    }

    private fun fill(target: SlotTarget, heroId: String?) {
        draft = when (target.kind) {
            StepKind.BAN -> draft.withBan(target.side, target.slot, heroId)
            StepKind.PICK -> draft.withPick(
                target.side,
                target.slot,
                heroId?.let { Pick(it, laneFor(it, target.side)) },
            )
        }
        // Single choke point for every board change, so speech fires exactly once per edit.
        announceTopPick()
    }

    private fun applyCache() {
        val (db, report) = metaRepository.applyCached(baseDb)
        engine = DraftEngine(db)
        metaStatus = when {
            report == null -> "Bundled data only"
            metaRepository.cachedOverlay() == null ->
                "Bundled verified catalogue · ${report.catalogueHeroesMatched} heroes, ${report.catalogueItemsMatched} equipment"
            report.isUsable -> "Live: ${report.patch} · ${relativeAge()}"
            else -> "Bundled data (cached feed unusable)"
        }
    }

    private suspend fun runSync(force: Boolean) {
        syncing = true
        try {
            when (val outcome = metaRepository.sync(baseDb, force = force)) {
                is SyncOutcome.Updated -> {
                    applyCache()
                    metaStatus = "Live: ${outcome.report.patch} · just now"
                }

                SyncOutcome.AlreadyCurrent, SyncOutcome.NotStale -> applyCache()

                SyncOutcome.NotPublished -> metaStatus = "Bundled data · no live feed published yet"

                is SyncOutcome.Failed -> metaStatus = if (metaRepository.cachedOverlay() != null) {
                    "Live: cached · ${relativeAge()} (offline)"
                } else {
                    "Bundled data · sync failed (${outcome.reason})"
                }
            }
        } finally {
            syncing = false
        }
    }

    private fun relativeAge(): String {
        val at = metaRepository.lastSyncedAtMillis
        if (at <= 0L) return "never synced"
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 48 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (60 * 24)}d ago"
        }
    }

    private fun laneFor(heroId: String, side: Side): Lane? {
        val hero = engine.db.hero(heroId) ?: return null
        val open = draft.lanesOpen(side)
        return hero.lanes.firstOrNull { it in open } ?: hero.lanes.firstOrNull()
    }
}
