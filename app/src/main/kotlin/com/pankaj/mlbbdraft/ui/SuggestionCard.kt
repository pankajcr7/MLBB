package com.pankaj.mlbbdraft.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.engine.model.Suggestion
import com.pankaj.mlbbdraft.ui.theme.Bad
import com.pankaj.mlbbdraft.ui.theme.Good
import com.pankaj.mlbbdraft.ui.theme.Warn
import kotlin.math.abs

/**
 * One recommendation. Reasons are the point of the card — the score only exists to
 * order the list, so it is deliberately small.
 */
@Composable
fun SuggestionCard(
    rank: Int,
    suggestion: Suggestion,
    quickActionLabel: String? = null,
    onQuickAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$rank",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = suggestion.hero.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = buildString {
                            append(suggestion.hero.roles.joinToString("/") { it.label })
                            suggestion.lane?.let { append(" · ${it.label}") }
                            append(" · difficulty ${suggestion.hero.difficulty}/5")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScoreBadge(suggestion.displayScore)
            }

            suggestion.reasons.forEach { reason ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (reason.startsWith("Risk") || reason.startsWith("Counter-pick")) "!" else "•",
                        color = if (reason.startsWith("Risk") || reason.startsWith("Counter-pick")) Warn else Good,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (quickActionLabel != null && onQuickAction != null) {
                Button(onClick = onQuickAction, modifier = Modifier.fillMaxWidth()) {
                    Text(quickActionLabel, fontWeight = FontWeight.Bold)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "Score breakdown",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestion.parts
                        .sortedByDescending { abs(it.weighted) }
                        .forEach { part ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = part.component.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(0.42f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SignedBar(part.raw, modifier = Modifier.weight(0.42f))
                                Text(
                                    text = "%+.2f".format(part.weighted),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(0.16f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    suggestion.hero.notes?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int) {
    val color = when {
        score >= 62 -> Good
        score >= 50 -> Warn
        else -> Bad
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = "$score", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** A bar that grows left or right from a centre line, for values in -1..1. */
@Composable
private fun SignedBar(value: Double, modifier: Modifier = Modifier) {
    val clamped = value.coerceIn(-1.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (clamped < 0f) {
                    Bar(fraction = -clamped, color = Bad)
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (clamped > 0f) {
                    Bar(fraction = clamped, color = Good)
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction.coerceIn(0f, 1f))
            .height(8.dp)
            .background(color.copy(alpha = 0.8f)),
    )
}
