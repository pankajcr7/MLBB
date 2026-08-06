package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.SlotTarget
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.Lane
import com.pankaj.mlbbdraft.engine.model.Role
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.model.StepKind
import com.pankaj.mlbbdraft.ui.theme.Good

/**
 * Hero picker.
 *
 * Search-first, because typing three letters is the fastest input available during a
 * draft timer — and it is the same interaction the Phase 1 overlay will fall back to
 * when automatic detection is unsure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroPickerSheet(
    target: SlotTarget,
    heroes: List<Hero>,
    unavailable: Set<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var roleFilter by remember { mutableStateOf<Role?>(null) }
    var laneFilter by remember { mutableStateOf<Lane?>(null) }

    val filtered = remember(query, roleFilter, laneFilter, heroes, unavailable) {
        heroes
            .asSequence()
            .filter { it.id !in unavailable }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { roleFilter == null || roleFilter in it.roles }
            .filter { laneFilter == null || laneFilter in it.lanes }
            .sortedByDescending { hero -> hero.tier.values.maxOrNull() ?: 0.0 }
            .toList()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = headline(target),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onClear) { Text("Clear slot") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search hero") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Lane.entries.forEach { lane ->
                    FilterChip(
                        selected = laneFilter == lane,
                        onClick = { laneFilter = if (laneFilter == lane) null else lane },
                        label = { Text(lane.shortLabel, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Role.entries.take(3).forEach { role -> RoleChip(role, roleFilter) { roleFilter = it } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Role.entries.drop(3).forEach { role -> RoleChip(role, roleFilter) { roleFilter = it } }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (filtered.isEmpty()) {
                Text(
                    text = "No hero matches those filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.id }) { hero ->
                    HeroRow(hero) { onPick(hero.id) }
                }
            }
        }
    }
}

@Composable
private fun RoleChip(role: Role, selected: Role?, onSelect: (Role?) -> Unit) {
    FilterChip(
        selected = selected == role,
        onClick = { onSelect(if (selected == role) null else role) },
        label = { Text(role.label, fontSize = 11.sp) },
    )
}

@Composable
private fun HeroRow(hero: Hero, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hero.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hero.roles.joinToString("/") { it.label } + " · " +
                    hero.lanes.joinToString("/") { it.shortLabel },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val tier = hero.tier.values.maxOrNull()
        if (tier != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Good.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("%.1f".format(tier), fontSize = 11.sp, color = Good)
            }
        }
    }
}

private fun headline(target: SlotTarget): String {
    val side = if (target.side == Side.ALLY) "your team" else "enemy"
    val what = if (target.kind == StepKind.BAN) "ban" else "pick ${target.slot + 1}"
    return "Set $side $what"
}
