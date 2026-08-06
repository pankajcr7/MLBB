package com.pankaj.mlbbdraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.engine.report.WinProbability
import com.pankaj.mlbbdraft.ui.theme.AllyBlue
import com.pankaj.mlbbdraft.ui.theme.Bad
import com.pankaj.mlbbdraft.ui.theme.EnemyRed
import com.pankaj.mlbbdraft.ui.theme.Good

/** Always-visible one-line readout under the board. */
@Composable
fun WinBar(win: WinProbability, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "You ${win.allyPercent}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AllyBlue,
            )
            Text(
                text = "draft advantage · ${win.confidence.label}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${win.enemyPercent}% Enemy",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = EnemyRed,
            )
        }
        SplitBar(win.allyPercent)
    }
}

@Composable
fun WinProbabilityCard(win: WinProbability) {
    PanelCard(title = "WHO IS THE DRAFT FAVOURING?") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "${win.allyPercent}%",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    win.allyPercent >= 55 -> Good
                    win.allyPercent > 45 -> MaterialTheme.colorScheme.onSurface
                    else -> Bad
                },
            )
            Text(
                text = win.headline,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        SplitBar(win.allyPercent)

        if (win.topFactors.isEmpty()) {
            Text(
                text = "Nothing decisive in the draft yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        win.topFactors.forEach { factor ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = factor.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(110.dp),
                    )
                    FactorBar(factor.delta, modifier = Modifier.weight(1f))
                }
                Text(
                    text = factor.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            text = win.caveat,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SplitBar(allyPercent: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(EnemyRed.copy(alpha = 0.55f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((allyPercent / 100f).coerceIn(0f, 1f))
                .height(10.dp)
                .background(AllyBlue),
        )
    }
}

/** Grows right for your advantage, left for theirs. */
@Composable
private fun FactorBar(delta: Double, modifier: Modifier = Modifier) {
    val clamped = delta.coerceIn(-1.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (clamped < 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((-clamped).coerceIn(0f, 1f))
                            .height(7.dp)
                            .background(EnemyRed),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (clamped > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(clamped.coerceIn(0f, 1f))
                            .height(7.dp)
                            .background(AllyBlue),
                    )
                }
            }
        }
    }
}
