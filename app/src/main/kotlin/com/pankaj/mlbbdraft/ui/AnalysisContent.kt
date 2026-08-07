package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.AnalysisTab
import com.pankaj.mlbbdraft.DraftSession
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Side

/**
 * The analysis surface, shared by the main screen and the floating overlay.
 *
 * Shared rather than reimplemented on purpose: the overlay is where the app is actually
 * used, so it must show *everything* — builds with real item icons, comp health, threats,
 * win probability — not a cut-down version that drifts behind the full app.
 *
 * Exposed as a `LazyListScope` extension so each surface owns its own scrolling container.
 */
fun LazyListScope.analysisContent(session: DraftSession) {
    when (session.tab) {
        AnalysisTab.PICKS -> {
            // Problems with picks already locked in come before advice on the next pick —
            // you can still ban, itemise or cover for them.
            if (session.pickWarnings.isNotEmpty()) {
                item { PickWarningsPanel(session.pickWarnings) }
            }
            item { LaneHint(session.laneFilter) }
            itemsIndexed(session.suggestions) { index, suggestion ->
                SuggestionCard(rank = index + 1, suggestion = suggestion)
            }
        }

        AnalysisTab.BANS -> {
            item {
                Text(
                    text = "Ban what beats the heroes you actually play — rate your heroes " +
                        "so this list knows what to protect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(session.banSuggestions) { index, suggestion ->
                SuggestionCard(rank = index + 1, suggestion = suggestion)
            }
        }

        AnalysisTab.BUILD -> {
            item {
                BuildPanel(
                    builds = session.builds,
                    selected = session.buildHero,
                    onSelect = session::selectBuildHero,
                )
            }
            // Before you have picked anything, team-wide advice is still useful.
            if (session.builds.isEmpty()) {
                item { ItemsPanel(session.itemAdvice) }
            }
        }

        AnalysisTab.COMP -> {
            item { WinProbabilityCard(session.winProbability) }
            item { ArchetypePanel(session.enemyArchetype, Side.ENEMY) }
            item { ArchetypePanel(session.allyArchetype, Side.ALLY) }
            item { CompPanel(session.allyReport) }
            item { CompPanel(session.enemyReport) }
        }

        AnalysisTab.THREATS -> {
            item { ArchetypePanel(session.enemyArchetype, Side.ENEMY) }
            item { ThreatsPanel(session.threatReport) }
        }
    }
}

@Composable
fun AnalysisTabRow(session: DraftSession, modifier: Modifier = Modifier) {
    TabRow(selectedTabIndex = session.tab.ordinal, modifier = modifier) {
        AnalysisTab.entries.forEach { tab ->
            Tab(
                selected = session.tab == tab,
                onClick = { session.selectTab(tab) },
                text = { Text(tab.label, fontSize = 12.sp) },
            )
        }
    }
}

@Composable
internal fun LaneHint(lane: Lane?) {
    Text(
        text = lane?.let { "Best picks for ${it.label}" }
            ?: "Best picks across every open lane — choose a lane above to narrow it down.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
