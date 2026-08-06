package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.report.BuildItem
import com.pankaj.mlbbdraft.engine.report.BuildSlotKind
import com.pankaj.mlbbdraft.engine.report.HeroBuild
import com.pankaj.mlbbdraft.ui.theme.Bad
import com.pankaj.mlbbdraft.ui.theme.Good
import com.pankaj.mlbbdraft.ui.theme.Warn

/**
 * The build tab. Pick a hero on your side of the board and this becomes a concrete
 * counter-build against what the enemy actually drafted.
 */
@Composable
fun BuildPanel(
    builds: List<HeroBuild>,
    selected: Hero?,
    onSelect: (String) -> Unit,
) {
    if (builds.isEmpty()) {
        PanelCard(title = "BUILD") {
            Text(
                text = "Add a hero to YOUR TEAM on the board above, and this becomes that " +
                    "hero's build against the enemy draft.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val build = builds.firstOrNull { it.hero.id == selected?.id } ?: builds.first()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (builds.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                builds.forEach { candidate ->
                    FilterChip(
                        selected = candidate.hero.id == build.hero.id,
                        onClick = { onSelect(candidate.hero.id) },
                        label = { Text(candidate.hero.name, fontSize = 11.sp) },
                    )
                }
            }
        }

        PanelCard(title = "${build.hero.name.uppercase()} — BUILD ORDER") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                build.order.forEachIndexed { index, slot ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ItemIcon(slot.item.id, slot.item.name, size = 48.dp)
                        Text(
                            text = "${index + 1}",
                            fontSize = 10.sp,
                            color = kindColor(slot.kind),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = "Approximate total: ${build.totalCost} gold",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot("Boots", kindColor(BuildSlotKind.BOOTS))
                LegendDot("Core", kindColor(BuildSlotKind.CORE))
                LegendDot("Counter pick", kindColor(BuildSlotKind.SITUATIONAL))
            }
        }

        if (build.situational.isNotEmpty()) {
            PanelCard(title = "BECAUSE OF THEIR DRAFT", accent = Warn) {
                build.situational.forEach { ItemRow(it) }
            }
        }

        PanelCard(title = "CORE") {
            build.boots?.let { ItemRow(it) }
            build.core.forEach { ItemRow(it) }
        }

        if (build.spells.isNotEmpty()) {
            PanelCard(title = "BATTLE SPELL") {
                build.spells.forEach { ItemRow(it) }
            }
        }

        PanelCard(title = "EMBLEM") {
            Text(
                text = "${build.emblem.emblem} — prioritise ${build.emblem.priority}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = build.emblem.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (build.notes.isNotEmpty()) {
            PanelCard(title = "HOW TO PLAY IT") {
                build.notes.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", color = Good, style = MaterialTheme.typography.bodySmall)
                        Text(note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(slot: BuildItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ItemIcon(slot.item.id, slot.item.name, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = slot.item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (slot.item.cost > 0) {
                    Text(
                        text = "  ${slot.item.cost}g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = slot.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun LegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun kindColor(kind: BuildSlotKind) = when (kind) {
    BuildSlotKind.BOOTS -> Good
    BuildSlotKind.CORE -> Good
    BuildSlotKind.SITUATIONAL -> Warn
    BuildSlotKind.SPELL -> Bad
}
