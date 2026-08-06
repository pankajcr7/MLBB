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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.report.CompReport
import com.pankaj.mlbbdraft.engine.report.ItemAdvice
import com.pankaj.mlbbdraft.engine.report.ThreatReport
import com.pankaj.mlbbdraft.ui.theme.AllyBlue
import com.pankaj.mlbbdraft.ui.theme.Bad
import com.pankaj.mlbbdraft.ui.theme.EnemyRed
import com.pankaj.mlbbdraft.ui.theme.Good
import com.pankaj.mlbbdraft.ui.theme.Warn

@Composable
fun PanelCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
fun CompPanel(report: CompReport) {
    val accent = if (report.side == Side.ALLY) AllyBlue else EnemyRed
    val label = if (report.side == Side.ALLY) "YOUR COMP" else "ENEMY COMP"

    PanelCard(title = label, accent = accent) {
        if (report.heroes.isEmpty()) {
            Text(
                text = "No heroes on this side yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PanelCard
        }

        Text(
            text = report.heroes.joinToString(", ") { it.name },
            style = MaterialTheme.typography.bodySmall,
        )

        DamageSplitBar(report)

        Meter("Crowd control", report.crowdControl / 10.0)
        Meter("Engage", report.engage / 10.0)
        Meter("Peel", report.peel / 10.0)
        Meter("Waveclear", report.waveclear / 10.0)
        Meter("Sustain", report.sustain / 10.0)

        CurveRow(report)

        Text(
            text = "Frontliners: ${report.frontlineCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        report.warnings.forEach { Bullet(it, Warn) }
        report.strengths.forEach { Bullet(it, Good) }

        val missing = report.needs.missing
        if (missing.isNotEmpty()) {
            Text(
                text = "Still needs: " + missing.joinToString(", ") { it.label },
                style = MaterialTheme.typography.labelSmall,
                color = Warn,
            )
        }
    }
}

@Composable
private fun DamageSplitBar(report: CompReport) {
    val d = report.damage
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Damage: ${pct(d.physical)} physical · ${pct(d.magic)} magic" +
                if (d.trueDamage > 0.01) " · ${pct(d.trueDamage)} true" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Segment(d.physical, Color(0xFFE0A23C))
            Segment(d.magic, Color(0xFF7B6BE0))
            Segment(d.trueDamage, Color(0xFFCFD6E4))
        }
    }
}

@Composable
private fun Segment(fraction: Double, color: Color) {
    if (fraction <= 0.0) return
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
            .height(10.dp)
            .background(color),
    )
}

@Composable
private fun Meter(label: String, value: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        LinearProgressIndicator(
            progress = { value.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp),
            color = when {
                value >= 0.7 -> Good
                value >= 0.45 -> Warn
                else -> Bad
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = "%.1f".format(value * 10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
    }
}

@Composable
private fun CurveRow(report: CompReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            "Early" to report.curve.early,
            "Mid" to report.curve.mid,
            "Late" to report.curve.late,
        ).forEach { (name, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%.1f".format(value),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        value >= 7.5 -> Good
                        value >= 6.0 -> Warn
                        else -> Bad
                    },
                )
                Text(
                    text = name,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ItemsPanel(advice: List<ItemAdvice>) {
    PanelCard(title = "BUILD AGAINST THIS DRAFT") {
        if (advice.isEmpty()) {
            Text(
                text = "Add enemy heroes to get build advice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PanelCard
        }
        advice.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityDot(item.priority)
                    Text(
                        text = item.item,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.forWhom,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PriorityDot(priority: Int) {
    val color = when {
        priority >= 5 -> Bad
        priority >= 4 -> Warn
        else -> Good
    }
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text = "P$priority", fontSize = 10.sp, color = color)
    }
}

@Composable
fun ThreatsPanel(report: ThreatReport) {
    PanelCard(title = "THREAT REPORT") {
        Text(
            text = report.tempo,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        report.threats.forEach { threat ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "${threat.hero.name} — threat ${(threat.score * 100).toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = EnemyRed,
                )
                Text(
                    text = threat.tip,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        report.tips.forEach { Bullet(it, Warn) }
    }
}

@Composable
private fun Bullet(text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "•", color = color, style = MaterialTheme.typography.bodySmall)
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

private fun pct(value: Double): String = "${(value * 100).toInt()}%"
