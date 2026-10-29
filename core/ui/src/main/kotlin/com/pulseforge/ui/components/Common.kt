package com.pulseforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Severity
import com.pulseforge.ui.theme.SeverityColors

/** Small pill that renders a severity. Always paired with the text, never colour alone. */
@Composable
fun SeverityChip(severity: Severity, modifier: Modifier = Modifier) {
    Surface(
        color = SeverityColors.container(severity),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.semantics { contentDescription = "Severity ${severity.name}" }
    ) {
        Text(
            text = severity.name,
            style = MaterialTheme.typography.labelSmall,
            color = SeverityColors.content(severity),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Confidence + evidence-type badge, keeping "confirmed" visually distinct from "heuristic". */
@Composable
fun ConfidencePill(confidence: Confidence, evidenceType: EvidenceType, modifier: Modifier = Modifier) {
    val color = SeverityColors.confidence(confidence)
    val label = when (evidenceType) {
        EvidenceType.DIRECT_OBSERVATION -> "Observed - ${confidence.name.lowercase()}"
        EvidenceType.PATTERN_MATCH -> "Heuristic - ${confidence.name.lowercase()}"
        EvidenceType.INFERENCE -> "Inferred - ${confidence.name.lowercase()}"
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    emphasize: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector = Icons.Filled.Info,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Informational banner used for limitations, sample data and privacy notes. */
@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, tone: BannerTone = BannerTone.NEUTRAL) {
    val (bg, fg, icon) = when (tone) {
        BannerTone.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Info
        )
        BannerTone.WARNING -> Triple(
            SeverityColors.container(Severity.MEDIUM),
            SeverityColors.content(Severity.MEDIUM),
            Icons.Filled.Warning
        )
        BannerTone.PRIVACY -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.Info
        )
    }
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
        }
    }
}

enum class BannerTone { NEUTRAL, WARNING, PRIVACY }

/** A score dial: the number is always printed, so colour is never the only signal. */
@Composable
fun ScoreDial(
    score: Int?,
    label: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 76.dp
) {
    val severity = when {
        score == null -> Severity.INFO
        score >= 80 -> Severity.LOW
        score >= 60 -> Severity.MEDIUM
        score >= 35 -> Severity.HIGH
        else -> Severity.CRITICAL
    }
    Box(
        modifier = modifier
            .size(size)
            .background(SeverityColors.container(severity), RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score?.toString() ?: "-",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = SeverityColors.content(severity)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = SeverityColors.content(severity),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Surface(
        color = (color ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/** Horizontal proportion bar used by the size-composition chart. */
@Composable
fun ProportionBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun RowOfTags(items: List<String>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier = modifier) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                row.forEach { Tag(it) }
            }
        }
    }
}
