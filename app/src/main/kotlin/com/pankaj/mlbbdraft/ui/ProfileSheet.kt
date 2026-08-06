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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.engine.model.Hero
import com.pankaj.mlbbdraft.engine.model.PlayerProfile
import com.pankaj.mlbbdraft.ui.theme.Good

/**
 * Hero mastery. Without this the app recommends heroes you cannot play, which is the
 * single most common way draft tools become useless.
 *
 * Rating a hero above zero also marks it as owned — one list to curate, not two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    profile: PlayerProfile,
    heroes: List<Hero>,
    onComfort: (String, Int) -> Unit,
    onToggleRestrict: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var ratedOnly by remember { mutableStateOf(false) }

    val shown = remember(query, ratedOnly, heroes, profile) {
        heroes
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .filter { !ratedOnly || (profile.comfort[it.id] ?: 0) > 0 }
            .sortedWith(
                compareByDescending<Hero> { profile.comfort[it.id] ?: 0 }.thenBy { it.name },
            )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Your heroes", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Tap the dots to rate how well you play a hero. Rated heroes count as owned.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Only suggest heroes I have",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = profile.restrictToOwned, onCheckedChange = { onToggleRestrict() })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Show only rated (${profile.comfort.count { it.value > 0 }})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(checked = ratedOnly, onCheckedChange = { ratedOnly = it })
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search hero") },
                singleLine = true,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(shown, key = { it.id }) { hero ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hero.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = hero.lanes.joinToString("/") { it.shortLabel },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ComfortDots(
                            value = profile.comfort[hero.id] ?: 0,
                            onChange = { onComfort(hero.id, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComfortDots(value: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { level ->
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (level <= value) Good else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    // Tapping the current level clears it, so un-rating is one tap.
                    .clickable { onChange(if (value == level) level - 1 else level) },
                contentAlignment = Alignment.Center,
            ) {
                if (level <= value) {
                    Text(
                        text = "$level",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.background,
                    )
                }
            }
        }
    }
}
