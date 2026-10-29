package com.pulseforge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.model.Finding
import com.pulseforge.ui.components.ConfidencePill
import com.pulseforge.ui.components.Divider
import com.pulseforge.ui.components.SeverityChip
import com.pulseforge.ui.components.Tag

/**
 * A finding as a card. Collapsed it shows the claim and how sure the analyser is; expanded it adds
 * the evidence, the remediation and the false-positive note.
 */
@Composable
fun FindingCard(finding: Finding, modifier: Modifier = Modifier, startExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(startExpanded) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("finding_${finding.ruleId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityChip(finding.severity)
                Spacer(Modifier.size(8.dp))
                ConfidencePill(finding.confidence, finding.evidenceType)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(finding.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                finding.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            finding.affectedComponent?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Divider()
                    Text(finding.technicalDetail, style = MaterialTheme.typography.bodyMedium)
                    if (finding.evidence.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Evidence", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        finding.evidence.forEach { evidence ->
                            Column(Modifier.padding(vertical = 3.dp)) {
                                Row {
                                    Text(
                                        evidence.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        evidence.source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        evidence.value,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Remediation", style = MaterialTheme.typography.labelLarge)
                    Text(finding.remediation, style = MaterialTheme.typography.bodyMedium)
                    finding.falsePositiveNote?.let { note ->
                        Spacer(Modifier.height(10.dp))
                        Text("Could this be a false positive?", style = MaterialTheme.typography.labelLarge)
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (finding.masvsControls.isNotEmpty() || finding.cweIds.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            finding.masvsControls.forEach { Tag(it) }
                            finding.cweIds.forEach { Tag(it) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Rule ${finding.ruleId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
