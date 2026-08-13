package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.AnalysisTab
import com.pankaj.mlbbdraft.DraftSession
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind

/**
 * The analysis surface, shared by the main screen and the floating overlay. The most useful
 * advice comes first, and the best recommendation can be acted on without reopening a picker.
 */
fun LazyListScope.analysisContent(
    session: DraftSession,
    onRequestEnemyBuildScan: (() -> Unit)? = null,
    onUploadBuildScreenshot: (() -> Unit)? = null,
) {
    when (session.tab) {
        AnalysisTab.PICKS -> {
            if (session.pickWarnings.isNotEmpty()) {
                item { PickWarningsPanel(session.pickWarnings) }
            }
            item { LaneHint(session.laneFilter) }
            itemsIndexed(session.suggestions) { index, suggestion ->
                SuggestionCard(
                    rank = index + 1,
                    suggestion = suggestion,
                    quickActionLabel = if (index == 0 && session.canLockSuggestedPick) {
                        "LOCK AS MY NEXT PICK"
                    } else {
                        null
                    },
                    onQuickAction = if (index == 0 && session.canLockSuggestedPick) {
                        { session.lockSuggestedPick(suggestion.hero.id) }
                    } else {
                        null
                    },
                )
            }
        }

        AnalysisTab.BANS -> {
            item {
                Text(
                    text = "Protect your plan by removing the heroes that punish your most-played picks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val canLockBan = session.activeTarget?.kind == StepKind.BAN
            itemsIndexed(session.banSuggestions) { index, suggestion ->
                SuggestionCard(
                    rank = index + 1,
                    suggestion = suggestion,
                    quickActionLabel = if (index == 0 && canLockBan) "LOCK ACTIVE BAN" else null,
                    onQuickAction = if (index == 0 && canLockBan) {
                        { session.lockSuggestedBan(suggestion.hero.id) }
                    } else {
                        null
                    },
                )
            }
        }

        AnalysisTab.BUILD -> {
            item {
                EnemyBuildSignalsPanel(
                    signals = session.draft.enemyBuildSignals,
                    confirmedEnemyItems = session.confirmedEnemyItems,
                    scanning = session.enemyBuildScanRequested,
                    importing = session.screenshotImporting,
                    status = session.detectionStatus,
                    onScan = {
                        session.requestEnemyBuildScan()
                        onRequestEnemyBuildScan?.invoke()
                    },
                    onUpload = {
                        if (!session.screenshotImporting) onUploadBuildScreenshot?.invoke()
                    },
                    onToggle = session::toggleEnemyBuildSignal,
                )
            }
            item {
                ItemsPanel(
                    advice = session.itemAdvice,
                    catalogItems = session.heroDatabase.items,
                )
            }
            item {
                BuildPanel(
                    builds = session.builds,
                    selected = session.buildHero,
                    onSelect = session::selectBuildHero,
                )
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = "ANALYSIS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnalysisTab.entries.forEach { tab ->
                FilterChip(
                    selected = session.tab == tab,
                    onClick = { session.selectTab(tab) },
                    label = { Text(tab.label, fontSize = 11.sp) },
                )
            }
        }
    }
}

@Composable
internal fun LaneHint(lane: Lane?) {
    Text(
        text = lane?.let { "Best picks for ${it.label}" }
            ?: "Top picks across every open lane — choose a lane above to narrow the list.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
