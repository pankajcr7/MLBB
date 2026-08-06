package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.AnalysisTab
import com.pankaj.mlbbdraft.DraftViewModel
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Side

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(viewModel: DraftViewModel) {
    val draft = viewModel.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MLBB Draft Helper", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = viewModel.patch,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::swapFirstPick) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Swap first pick")
                    }
                    IconButton(onClick = viewModel::openProfile) {
                        Icon(Icons.Default.Person, contentDescription = "Your heroes")
                    }
                    IconButton(onClick = viewModel::reset) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset draft")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeRow(draft.mode, onMode = viewModel::setMode)

            DraftBoard(
                draft = draft,
                heroName = { id -> viewModel.hero(id)?.name ?: id },
                onSlot = viewModel::openPicker,
                onClear = viewModel::clearSlot,
            )

            LaneRow(viewModel.laneFilter, onLane = viewModel::selectLane)

            TabRow(selectedTabIndex = viewModel.tab.ordinal) {
                AnalysisTab.entries.forEach { tab ->
                    Tab(
                        selected = viewModel.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label, fontSize = 12.sp) },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                when (viewModel.tab) {
                    AnalysisTab.PICKS -> {
                        item { LaneHint(viewModel.laneFilter) }
                        itemsIndexed(viewModel.suggestions) { index, suggestion ->
                            SuggestionCard(rank = index + 1, suggestion = suggestion)
                        }
                    }

                    AnalysisTab.BANS -> {
                        item {
                            Text(
                                text = "Ban what beats the heroes you actually play — " +
                                    "rate your heroes so this list knows what to protect.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        itemsIndexed(viewModel.banSuggestions) { index, suggestion ->
                            SuggestionCard(rank = index + 1, suggestion = suggestion)
                        }
                    }

                    AnalysisTab.COMP -> {
                        item { CompPanel(viewModel.allyReport) }
                        item { CompPanel(viewModel.enemyReport) }
                    }

                    AnalysisTab.ITEMS -> item { ItemsPanel(viewModel.itemAdvice) }

                    AnalysisTab.THREATS -> item { ThreatsPanel(viewModel.threatReport) }
                }
            }
        }
    }

    viewModel.picker?.let { target ->
        HeroPickerSheet(
            target = target,
            heroes = viewModel.allHeroes,
            unavailable = draft.usedHeroIds,
            onPick = viewModel::assign,
            onClear = { viewModel.assign(null) },
            onDismiss = viewModel::closePicker,
        )
    }

    if (viewModel.showProfile) {
        ProfileSheet(
            profile = draft.profile,
            heroes = viewModel.allHeroes,
            onComfort = viewModel::setComfort,
            onToggleRestrict = viewModel::toggleRestrictToOwned,
            onDismiss = viewModel::closeProfile,
        )
    }
}

@Composable
private fun ModeRow(mode: DraftMode, onMode: (DraftMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DraftMode.entries.forEach { entry ->
            FilterChip(
                selected = mode == entry,
                onClick = { onMode(entry) },
                label = { Text(entry.label, fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun LaneRow(selected: Lane?, onLane: (Lane?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onLane(null) },
            label = { Text("All lanes", fontSize = 11.sp) },
        )
        Lane.entries.forEach { lane ->
            FilterChip(
                selected = selected == lane,
                onClick = { onLane(if (selected == lane) null else lane) },
                label = { Text(lane.shortLabel, fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun LaneHint(lane: Lane?) {
    Text(
        text = lane?.let { "Best picks for ${it.label}" }
            ?: "Best picks across every open lane — choose a lane above to narrow it down.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
