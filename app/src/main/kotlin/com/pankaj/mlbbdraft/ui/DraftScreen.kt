package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenSearchDesktop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.DraftSession
import com.pankaj.mlbbdraft.engine.model.DraftMode
import com.pankaj.mlbbdraft.engine.model.DraftStep
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind
import com.pankaj.mlbbdraft.ui.theme.AllyBlue
import com.pankaj.mlbbdraft.ui.theme.EnemyRed
import com.pankaj.mlbbdraft.ui.theme.Good

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftScreen(
    session: DraftSession,
    onStartOverlay: () -> Unit = {},
    onStopOverlay: () -> Unit = {},
    onStartAutoDetect: () -> Unit = {},
    onUploadBuildScreenshot: () -> Unit = {},
) {
    val draft = session.draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "DRAFT COMMAND",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.6.sp,
                        )
                        Text(
                            text = if (session.syncing) "Updating patch data…" else session.metaStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                            contentDescription = "Read the draft from my screen",
                        )
                    }
                    IconButton(
                        onClick = if (session.overlayRunning) onStopOverlay else onStartOverlay,
                    ) {
                        Icon(
                            Icons.Default.PictureInPictureAlt,
                            contentDescription = if (session.overlayRunning) {
                                "Stop the floating overlay"
                            } else {
                                "Open the floating overlay"
                            },
                        )
                    }
                    IconButton(onClick = session::openProfile) {
                        Icon(Icons.Default.Person, contentDescription = "Hero pool and live data")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DraftCommandCard(session)

            DraftBoard(
                draft = draft,
                heroName = { id -> session.hero(id)?.name ?: id },
                onSlot = session::openPicker,
                onClear = session::clearSlot,
            )

            WinBar(session.winProbability)

            LaneRail(session.laneFilter, onLane = session::selectLane)

            AnalysisTabRow(session)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                analysisContent(
                    session = session,
                    onRequestEnemyBuildScan = {
                        if (!session.autoDetecting) onStartAutoDetect()
                    },
                    onUploadBuildScreenshot = onUploadBuildScreenshot,
                )
                item {
                    DraftSetupCard(
                        mode = draft.mode,
                        bansPerSide = draft.bansPerSide,
                        firstPick = draft.firstPick,
                        onMode = session::setMode,
                        onBans = session::setBansPerSide,
                        onSwapFirstPick = session::swapFirstPick,
                        onReset = session::reset,
                    )
                }
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
private fun DraftCommandCard(session: DraftSession) {
    val step = session.draft.currentStep
    val accent = when (step?.side) {
        Side.ALLY -> AllyBlue
        Side.ENEMY -> EnemyRed
        null -> Good
    }
    val title = step?.let(::turnTitle) ?: "DRAFT COMPLETE"
    val detail = step?.let(::turnDetail) ?: "Review builds, composition and threat reports."
    val action = step?.let(::actionLabel)
    val completed = session.draft.steps.size - session.draft.steps.count { draftStep ->
        when (draftStep.kind) {
            StepKind.BAN -> session.draft.bans(draftStep.side).getOrNull(draftStep.slot) == null
            StepKind.PICK -> session.draft.pickSlots(draftStep.side).getOrNull(draftStep.slot) == null
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.12f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "LIVE DRAFT · $completed/${session.draft.steps.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.35.sp,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${session.winProbability.allyPercent}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (session.winProbability.allyPercent >= 50) Good else EnemyRed,
                    fontWeight = FontWeight.Black,
                )
            }

            if (step != null && action != null) {
                Button(
                    onClick = session::openActiveAction,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color(0xFF08111D),
                    ),
                ) {
                    Text(action, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun turnTitle(step: DraftStep): String {
    val team = if (step.side == Side.ALLY) "YOUR TEAM" else "ENEMY"
    return "$team · ${if (step.kind == StepKind.PICK) "PICK" else "BAN"}"
}

private fun turnDetail(step: DraftStep): String = when {
    step.side == Side.ALLY && step.kind == StepKind.PICK ->
        "Choose a counter, fill a team need, then lock it in."
    step.side == Side.ALLY && step.kind == StepKind.BAN ->
        "Protect your plan by removing the highest-impact threat."
    step.side == Side.ENEMY && step.kind == StepKind.PICK ->
        "Log their pick to refresh counters and team warnings."
    else -> "Log their ban to keep every recommendation available."
}

private fun actionLabel(step: DraftStep): String = when {
    step.side == Side.ALLY && step.kind == StepKind.PICK -> "ADD YOUR PICK"
    step.side == Side.ALLY && step.kind == StepKind.BAN -> "ADD YOUR BAN"
    step.side == Side.ENEMY && step.kind == StepKind.PICK -> "LOG ENEMY PICK"
    else -> "LOG ENEMY BAN"
}

@Composable
private fun LaneRail(selected: Lane?, onLane: (Lane?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = "RECOMMENDATION FOCUS",
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
}

@Composable
private fun DraftSetupCard(
    mode: DraftMode,
    bansPerSide: Int,
    firstPick: Side,
    onMode: (DraftMode) -> Unit,
    onBans: (Int) -> Unit,
    onSwapFirstPick: () -> Unit,
    onReset: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("DRAFT SETUP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Change format or reset without crowding the live workflow.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onReset) { Text("Reset") }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DraftMode.entries.forEach { entry ->
                    FilterChip(
                        selected = mode == entry,
                        onClick = { onMode(entry) },
                        label = { Text(entry.label, fontSize = 11.sp) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = onSwapFirstPick,
                    label = { Text(if (firstPick == Side.ALLY) "We pick first" else "They pick first", fontSize = 11.sp) },
                )
                if (mode != DraftMode.CLASSIC) {
                    listOf(3, 4, 5).forEach { count ->
                        FilterChip(
                            selected = bansPerSide == count,
                            onClick = { onBans(count) },
                            label = { Text("$count bans", fontSize = 11.sp) },
                        )
                    }
                }
            }
        }
    }
}
