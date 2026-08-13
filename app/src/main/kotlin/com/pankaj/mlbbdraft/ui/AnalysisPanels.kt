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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pankaj.mlbbdraft.engine.model.EnemyBuildSignal
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.Side
import com.pankaj.mlbbdraft.engine.report.CompReport
import com.pankaj.mlbbdraft.engine.report.ArchetypeVerdict
import com.pankaj.mlbbdraft.engine.report.ItemAdvice
import com.pankaj.mlbbdraft.engine.report.PickAssessment
import com.pankaj.mlbbdraft.engine.report.PickVerdict
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
fun EnemyBuildSignalsPanel(
    signals: Set<EnemyBuildSignal>,
    confirmedEnemyItems: List<Item>,
    scanning: Boolean,
    importing: Boolean,
    status: String,
    onScan: () -> Unit,
    onUpload: () -> Unit,
    onToggle: (EnemyBuildSignal) -> Unit,
) {
    PanelCard(title = "ENEMY BUILD SIGNALS", accent = EnemyRed) {
        Button(
            onClick = onScan,
            enabled = !scanning && !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (scanning) "SCANNING ENEMY BUILD…" else "SCAN ENEMY BUILD")
        }
        Button(
            onClick = onUpload,
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (importing) "READING BUILD SCREENSHOT…" else "UPLOAD BUILD SCREENSHOT")
        }
        Text(
            text = "Scan a live red Equipment screen or upload a saved one. Only readable item names change advice; icon-only rows stay manual.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.isNotBlank()) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ConfirmedEnemyItemsRow(confirmedEnemyItems)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EnemyBuildSignal.entries.forEach { signal ->
                FilterChip(
                    selected = signal in signals,
                    onClick = { onToggle(signal) },
                    label = { Text(signal.shortLabel, fontSize = 10.sp) },
                )
            }
        }
        Text(
            text = if (signals.isEmpty()) {
                "No build traits confirmed — advice currently uses enemy heroes only."
            } else {
                "Confirmed: ${signals.sortedBy { it.label }.joinToString(" · ") { it.label }}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfirmedEnemyItemsRow(items: List<Item>) {
    if (items.isEmpty()) return
    Text(
        text = "SCAN SUCCESSFUL · ${items.size} CONFIRMED ENEMY ITEM${if (items.size == 1) "" else "S"}",
        style = MaterialTheme.typography.labelSmall,
        color = Good,
        fontWeight = FontWeight.Bold,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                ItemIcon(itemId = item.id, itemName = item.name, size = 42.dp)
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun ItemsPanel(
    advice: List<ItemAdvice>,
    catalogItems: List<Item>,
) {
    val itemByName = catalogItems.associateBy { it.name.lowercase() }
    PanelCard(title = "BUILD AGAINST THIS DRAFT") {
        if (advice.isEmpty()) {
            Text(
                text = "Add enemy heroes to get build advice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PanelCard
        }
        advice.forEach { adviceItem ->
            val iconItem = itemByName[adviceItem.item.lowercase()]
                ?: catalogItems.firstOrNull { adviceItem.item.startsWith(it.name, ignoreCase = true) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (iconItem != null) {
                    ItemIcon(itemId = iconItem.id, itemName = iconItem.name, size = 44.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PriorityDot(adviceItem.priority)
                        Text(
                            text = adviceItem.item,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = adviceItem.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = adviceItem.forWhom,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/**
 * One label for what a draft is trying to do, plus the answer to it.
 *
 * This is the fastest-to-read panel in the app on purpose. Five meters take longer to
 * absorb than a pick timer allows; "Dive comp — anti-dash CC beats this" does not.
 */
@Composable
fun ArchetypePanel(verdict: ArchetypeVerdict, side: Side) {
    val accent = if (side == Side.ALLY) AllyBlue else EnemyRed
    val who = if (side == Side.ALLY) "YOUR PLAN" else "THEIR PLAN"

    PanelCard(title = who, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(accent.copy(alpha = 0.22f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = verdict.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            if (verdict.isDistinct) {
                Text(
                    text = "  ${(verdict.confidence * 100).toInt()}% match",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (verdict.summary.isNotBlank()) {
            Text(text = verdict.summary, style = MaterialTheme.typography.bodySmall)
        }

        if (verdict.counterplay.isNotBlank()) {
            Text(
                text = "→ ${verdict.counterplay}",
                style = MaterialTheme.typography.bodySmall,
                color = Good,
            )
        }
    }
}

/**
 * Warnings about picks your team has already locked in.
 *
 * Kept visually loud and placed above the suggestion list, because it is the one thing
 * here that is time-critical: you can still ban the counter, cover with a later pick, or
 * change your items — but only if you notice.
 */
@Composable
fun PickWarningsPanel(warnings: List<PickAssessment>) {
    PanelCard(title = "PROBLEMS WITH YOUR PICKS", accent = Bad) {
        warnings.forEach { warning ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (warning.verdict == PickVerdict.BAD) {
                                    Bad.copy(alpha = 0.25f)
                                } else {
                                    Warn.copy(alpha = 0.25f)
                                },
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = warning.verdict.label,
                            fontSize = 10.sp,
                            color = if (warning.verdict == PickVerdict.BAD) Bad else Warn,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = "  ${warning.hero.name}" +
                            (warning.lane?.let { " · ${it.shortLabel}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                warning.problems.forEach { Bullet(it, Warn) }
                warning.advice?.let {
                    Text(
                        text = "→ $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Good,
                    )
                }
            }
        }
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
