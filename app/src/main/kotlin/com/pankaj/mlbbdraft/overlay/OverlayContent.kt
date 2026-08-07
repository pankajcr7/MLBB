package com.pankaj.mlbbdraft.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.DraftSession
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.ui.AnalysisTabRow
import com.pankaj.mlbbdraft.ui.analysisContent
import com.pankaj.mlbbdraft.ui.theme.AllyBlue
import com.pankaj.mlbbdraft.ui.theme.Bad
import com.pankaj.mlbbdraft.ui.theme.EnemyRed
import com.pankaj.mlbbdraft.ui.theme.Good
import com.pankaj.mlbbdraft.ui.theme.Warn

/**
 * The floating UI. Two jobs only: get the enemy picks in fast, and show what to pick.
 *
 * Everything else lives in the full app. Under a draft timer, a panel with five tabs is
 * worse than a panel with one answer.
 */
@Composable
fun OverlayContent(
    session: DraftSession,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onStopAutoDetect: () -> Unit = {},
    dragModifier: (Modifier) -> Modifier,
) {
    if (!expanded) {
        Bubble(
            win = session.winProbability.allyPercent,
            reading = session.autoDetecting,
            onToggle = onToggle,
        )
        return
    }

    // Expanded takes the whole screen: entering ten heroes through a 300dp window under a
    // draft timer does not work, and minimising gives the game back completely anyway.
    //
    // A LazyColumn rather than a scrolling Column because it hosts the shared analysis
    // panels — the overlay shows the same builds, comp report and threats as the full app,
    // since this is where the app is actually used.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item { Header(session, onToggle = onToggle, onClose = onClose, onOpenApp = onOpenApp) }
        item { AutoDetectBar(session, onStopAutoDetect) }
        item { EnemyPlan(session) }
        item { TeamRow(session, Side.ENEMY) }
        item { TeamRow(session, Side.ALLY) }
        item { SideToggle(session) }
        item { QuickAdd(session) }
        item { LaneRow(session) }
        item { AnalysisTabRow(session) }
        analysisContent(session)
    }
}

/**
 * The point of the whole feature: your team locked something that loses to their draft,
 * and you can still do something about it.
 */
@Composable
private fun Warnings(session: DraftSession) {
    val warnings = session.pickWarnings
    if (warnings.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Bad.copy(alpha = 0.14f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        warnings.forEach { warning ->
            Text(
                text = "${warning.verdict.label}: ${warning.hero.name}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (warning.verdict.name == "BAD") Bad else Warn,
            )
            warning.problems.forEach {
                Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            warning.advice?.let {
                Text(
                    text = "→ $it",
                    fontSize = 11.sp,
                    color = Good,
                )
            }
        }
    }
}

@Composable
private fun SideToggle(session: DraftSession) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Adding to:", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(Side.ENEMY, Side.ALLY).forEach { side ->
            val selected = session.quickAddSide == side
            val tint = if (side == Side.ALLY) AllyBlue else EnemyRed
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (selected) tint else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { session.quickAddSide = side }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (side == Side.ALLY) "YOUR TEAM" else "ENEMY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.background
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** One line on what the enemy draft wants, and the answer to it. */
@Composable
private fun EnemyPlan(session: DraftSession) {
    val verdict = session.enemyArchetype
    if (!verdict.isDistinct) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(EnemyRed.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "THEY ARE A ${verdict.label.uppercase()}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = EnemyRed,
        )
        Text(
            text = verdict.counterplay,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Status of the screen reader, so it is obvious whether detection is actually working. */
@Composable
private fun AutoDetectBar(session: DraftSession, onStop: () -> Unit) {
    if (!session.autoDetecting && session.detectionStatus.isBlank()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Good.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (session.autoDetecting) "AUTO-READING SCREEN" else "AUTO-READ OFF",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Good,
        )
        Text(
            text = "  ${session.detectionStatus}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (session.autoDetecting) TinyButton("stop", onClick = onStop)
    }
}

/** Collapsed state: small, draggable, shows the one number worth glancing at. */
@Composable
private fun Bubble(win: Int, reading: Boolean, onToggle: () -> Unit) {
    val tint = when {
        reading -> Good
        win >= 55 -> Good
        win > 45 -> AllyBlue
        else -> Bad
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .border(2.dp, tint, RoundedCornerShape(26.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$win%", color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (reading) "reading" else "draft",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(
    session: DraftSession,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val win = session.winProbability
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "You ${win.allyPercent}%",
            color = if (win.allyPercent >= 50) Good else Bad,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TinyButton("APP", onOpenApp)
        TinyButton("—", onToggle)
        TinyButton("✕", onClose, tint = EnemyRed)
    }
}

@Composable
private fun TinyButton(label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color? = null) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LaneRow(session: DraftSession) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Lane.entries.forEach { lane ->
            val selected = session.laneFilter == lane
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (selected) AllyBlue else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { session.selectLane(if (selected) null else lane) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = lane.shortLabel,
                    fontSize = 10.sp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.background
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Picks so far for one side, with one-tap undo — mistyping under time pressure is normal. */
@Composable
private fun TeamRow(session: DraftSession, side: Side) {
    val picks = session.draft.picks(side)
    val tint = if (side == Side.ALLY) AllyBlue else EnemyRed
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (side == Side.ALLY) "YOURS" else "ENEMY",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.width(46.dp),
        )
        Text(
            text = picks.joinToString(", ") { session.hero(it.heroId)?.name ?: it.heroId }
                .ifEmpty { "none yet" },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (picks.isNotEmpty()) {
            TinyButton("undo", onClick = { session.undoLast(side) })
        }
    }
}

@Composable
private fun QuickAdd(session: DraftSession) {
    var query by remember { mutableStateOf("") }
    val side = session.quickAddSide
    val matches = session.available(lane = null, query = query, limit = 14)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                ),
                cursorBrush = SolidColor(AllyBlue),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    text = if (side == Side.ALLY) "type your team's hero…" else "type an enemy hero…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            matches.forEach { hero ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            (if (side == Side.ALLY) AllyBlue else EnemyRed).copy(alpha = 0.22f),
                        )
                        .clickable {
                            session.quickAdd(side, hero.id)
                            query = ""
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    Text(hero.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun Suggestions(session: DraftSession) {
    val picks = session.suggestions.take(5)
    if (picks.isEmpty()) {
        Text(
            "No suggestions — every hero is taken or filtered out.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        picks.forEachIndexed { index, suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (index == 0) Good.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { session.quickAdd(Side.ALLY, suggestion.hero.id) }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${suggestion.hero.name}",
                        fontSize = 12.sp,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    suggestion.reasons.firstOrNull()?.let {
                        Text(
                            text = it,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = "${suggestion.displayScore}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (index == 0) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "Tap a suggestion to lock it into your team.",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(12.dp),
        )
    }
}
