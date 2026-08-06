package com.pankaj.mlbbdraft

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.pankaj.mlbbdraft.data.ProfileStore
import com.pankaj.mlbbdraft.engine.DraftEngine
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Pick
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind

/** Which analysis the user is looking at. */
enum class AnalysisTab(val label: String) {
    PICKS("Picks"),
    BANS("Bans"),
    COMP("Comp"),
    ITEMS("Items"),
    THREATS("Threats"),
}

/** The board slot the hero picker is filling. */
data class SlotTarget(val side: Side, val kind: StepKind, val slot: Int)

class DraftViewModel(
    private val engine: DraftEngine,
    private val profileStore: ProfileStore,
) : ViewModel() {

    var draft by mutableStateOf(
        DraftState.forMode(DraftMode.RANKED).copy(profile = profileStore.load()),
    )
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

    val patch: String get() = engine.db.patch
    val allHeroes: List<Hero> get() = engine.db.heroes

    // Derived state: recomputed lazily on read, so editing the board is cheap and the
    // analysis is always consistent with what is on screen.
    val suggestions by derivedStateOf { engine.suggestPicks(draft, laneFilter, limit = 8) }
    val banSuggestions by derivedStateOf { engine.suggestBans(draft, limit = 8) }
    val allyReport by derivedStateOf { engine.compReport(draft, Side.ALLY) }
    val enemyReport by derivedStateOf { engine.compReport(draft, Side.ENEMY) }
    val itemAdvice by derivedStateOf { engine.itemAdvice(draft) }
    val threatReport by derivedStateOf { engine.threatReport(draft) }

    fun hero(id: String): Hero? = engine.db.hero(id)

    fun search(query: String): List<Hero> = engine.db.search(query)

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

    fun reset() {
        draft = draft.cleared()
    }

    fun openPicker(target: SlotTarget) {
        picker = target
    }

    fun closePicker() {
        picker = null
    }

    /** Fills the slot the picker was opened for. Passing null clears it. */
    fun assign(heroId: String?) {
        val target = picker ?: return
        draft = when (target.kind) {
            StepKind.BAN -> draft.withBan(target.side, target.slot, heroId)
            StepKind.PICK -> draft.withPick(
                target.side,
                target.slot,
                heroId?.let { Pick(it, laneFor(it, target.side)) },
            )
        }
        picker = null
    }

    fun clearSlot(target: SlotTarget) {
        draft = when (target.kind) {
            StepKind.BAN -> draft.withBan(target.side, target.slot, null)
            StepKind.PICK -> draft.withPick(target.side, target.slot, null)
        }
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
        val profile = draft.profile.copy(
            comfort = updated,
            owned = updated.keys.toSet(),
        )
        draft = draft.copy(profile = profile)
        profileStore.save(profile)
    }

    fun toggleRestrictToOwned() {
        val profile = draft.profile.copy(restrictToOwned = !draft.profile.restrictToOwned)
        draft = draft.copy(profile = profile)
        profileStore.save(profile)
    }

    /** Prefers a lane the side has not filled yet, so the board self-organises. */
    private fun laneFor(heroId: String, side: Side): Lane? {
        val hero = engine.db.hero(heroId) ?: return null
        val open = draft.lanesOpen(side)
        return hero.lanes.firstOrNull { it in open } ?: hero.lanes.firstOrNull()
    }
}
