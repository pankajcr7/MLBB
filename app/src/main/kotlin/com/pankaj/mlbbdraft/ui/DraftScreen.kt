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
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenSearchDesktop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Sync
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
import com.pankaj.mlbbdraft.DraftSession
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Side

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(
    session: DraftSession,
    onStartOverlay: () -> Unit = {},
    onStopOverlay: () -> Unit = {},
    onStartAutoDetect: () -> Unit = {},
) {
    val draft = session.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MLBB Draft Helper", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (session.syncing) "Syncing meta…" else session.metaStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = session::toggleSpeakSuggestions) {
                        Icon(
                            imageVector = if (session.speakSuggestions) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = if (session.speakSuggestions) {
                                "Stop reading the top pick aloud"
                            } else {
                                "Read the top pick aloud"
                            },
                        )
                    }
                    IconButton(onClick = onStartAutoDetect, enabled = !session.autoDetecting) {
                        Icon(
                            Icons.Default.ScreenSearchDesktop,
                            contentDescription = "Read the draft off my screen automatically",
                        )
                    }
                    IconButton(
                        onClick = if (session.overlayRunning) onStopOverlay else onStartOverlay,
                    ) {
                        Icon(
                            imageVector = if (session.overlayRunning) {
                                Icons.Default.CloseFullscreen
                            } else {
                                Icons.Default.PictureInPictureAlt
                            },
                            contentDescription = if (session.overlayRunning) {
                                "Stop the floating overlay"
                            } else {
                                "Float over your game"
                            },
                        )
                    }
                    IconButton(onClick = session::syncNow, enabled = !session.syncing) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync live meta data")
                    }
                    IconButton(onClick = session::swapFirstPick) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Swap first pick")
                    }
                    IconButton(onClick = session::openProfile) {
                        Icon(Icons.Default.Person, contentDescription = "Your heroes")
                    }
                    IconButton(onClick = session::reset) {
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
            ModeRow(
                mode = draft.mode,
                bansPerSide = draft.bansPerSide,
                onMode = session::setMode,
                onBans = session::setBansPerSide,
            )

            DraftBoard(
                draft = draft,
                heroName = { id -> session.hero(id)?.name ?: id },
                onSlot = session::openPicker,
                onClear = session::clearSlot,
            )

            WinBar(session.winProbability)

            LaneRow(session.laneFilter, onLane = session::selectLane)

            AnalysisTabRow(session)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                analysisContent(session)
            }
        }
    }

    session.picker?.let { target ->
        HeroPickerSheet(
            target = target,
            heroes = session.allHeroes,
            unavailable = draft.usedHeroIds,
            onPick = session::assign,
            onClear = { session.assign(null) },
            onDismiss = session::closePicker,
        )
    }

    if (session.showProfile) {
        ProfileSheet(
            profile = draft.profile,
            heroes = session.allHeroes,
            metaStatus = session.metaStatus,
            feedUrl = session.feedUrl,
            syncing = session.syncing,
            onFeedUrl = session::setFeedUrl,
            onSyncNow = session::syncNow,
            onComfort = session::setComfort,
            onToggleRestrict = session::toggleRestrictToOwned,
            onDismiss = session::closeProfile,
        )
    }
}

@Composable
private fun ModeRow(
    mode: DraftMode,
    bansPerSide: Int,
    onMode: (DraftMode) -> Unit,
    onBans: (Int) -> Unit,
) {
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
        // Ranked gives 3, 4 or 5 bans per side depending on rank.
        if (mode != DraftMode.CLASSIC) {
            listOf(3, 4, 5).forEach { count ->
                FilterChip(
                    selected = bansPerSide == count,
                    onClick = { onBans(count) },
                    label = { Text("${count} bans", fontSize = 11.sp) },
                )
            }
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

