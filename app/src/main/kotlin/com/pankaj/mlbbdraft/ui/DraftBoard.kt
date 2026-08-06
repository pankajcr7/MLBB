package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.SlotTarget
import com.pankaj.mlbbdraft.engine.model.DraftState
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind
import com.pankaj.mlbbdraft.ui.theme.AllyBlue
import com.pankaj.mlbbdraft.ui.theme.EnemyRed

/**
 * The board. Tapping a slot opens the picker; long-pressing clears it.
 *
 * Deliberately text-only — no hero portraits are bundled, so the app ships no
 * Moonton artwork.
 */
@Composable
fun DraftBoard(
    draft: DraftState,
    heroName: (String) -> String,
    onSlot: (SlotTarget) -> Unit,
    onClear: (SlotTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = draft.currentStep

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SideBlock(
            side = Side.ENEMY,
            draft = draft,
            heroName = heroName,
            onSlot = onSlot,
            onClear = onClear,
            highlight = current,
        )
        SideBlock(
            side = Side.ALLY,
            draft = draft,
            heroName = heroName,
            onSlot = onSlot,
            onClear = onClear,
            highlight = current,
        )
    }
}

@Composable
private fun SideBlock(
    side: Side,
    draft: DraftState,
    heroName: (String) -> String,
    onSlot: (SlotTarget) -> Unit,
    onClear: (SlotTarget) -> Unit,
    highlight: com.pankaj.mlbbdraft.engine.model.DraftStep?,
) {
    val accent = if (side == Side.ALLY) AllyBlue else EnemyRed
    val title = if (side == Side.ALLY) "YOUR TEAM" else "ENEMY"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            if (side == draft.firstPick) {
                Text(
                    text = "first pick",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.pickSlots(side).forEachIndexed { index, pick ->
                val target = SlotTarget(side, StepKind.PICK, index)
                PickSlot(
                    label = pick?.let { heroName(it.heroId) },
                    lane = pick?.lane?.shortLabel,
                    accent = accent,
                    isNext = highlight?.kind == StepKind.PICK &&
                        highlight.side == side &&
                        highlight.slot == index,
                    modifier = Modifier.weight(1f),
                    onClick = { if (pick == null) onSlot(target) else onClear(target) },
                )
            }
        }

        if (draft.bans(side).isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "BAN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.bans(side).forEachIndexed { index, banned ->
                    val target = SlotTarget(side, StepKind.BAN, index)
                    BanSlot(
                        label = banned?.let { heroName(it) },
                        isNext = highlight?.kind == StepKind.BAN &&
                            highlight.side == side &&
                            highlight.slot == index,
                        onClick = { if (banned == null) onSlot(target) else onClear(target) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickSlot(
    label: String?,
    lane: String?,
    accent: Color,
    isNext: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = when {
        isNext -> MaterialTheme.colorScheme.primary
        label != null -> accent
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (label != null) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
            )
            .border(if (isNext) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (lane != null) {
                    Text(
                        text = lane,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BanSlot(
    label: String?,
    isNext: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (isNext) 2.dp else 1.dp,
                if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label ?: "ban",
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
            color = if (label == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
