package com.pulseforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.model.DeviceOverview
import com.pulseforge.model.InstalledApp
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.KeyValueRow
import com.pulseforge.ui.components.ScoreDial
import com.pulseforge.ui.components.SectionCard
import com.pulseforge.ui.components.SeverityChip

/**
 * Device-wide optimization view, modelled on the cleaner screens phones ship with - but every
 * number here is measured or documented, and every button does something Android actually allows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceOverviewScreen(
    overview: DeviceOverview?,
    scanning: Boolean,
    onScanAll: () -> Unit,
    onCleanCache: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onScanApp: (InstalledApp) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Optimize device") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (overview == null || !overview.hasData) {
                    item {
                        EmptyState(
                            title = "Nothing measured yet",
                            message = "Run a device scan to score every installed app. It reads package metadata " +
                                "only, so it takes a second and does not touch any app's data."
                        )
                    }
                } else {
                    item {
                        SectionCard(title = "Device score", subtitle = "${overview.scannedApps} apps measured") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScoreDial(overview.score, "score", size = 88.dp)
                                Spacer(Modifier.size(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        scoreHeadline(overview.score),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "100 minus the deductions listed below. Recompute it by hand at any time.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (overview.breakdown.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                overview.breakdown.forEach { item ->
                                    Row(Modifier.padding(vertical = 3.dp)) {
                                        Text(
                                            "${item.points}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(width = 42.dp, height = 20.dp)
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                item.explanation,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SectionCard(title = "Actions") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onScanAll,
                                    enabled = !scanning,
                                    modifier = Modifier.fillMaxWidth().testTag("scanAllApps")
                                ) {
                                    if (scanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(Modifier.size(10.dp))
                                        Text("Measuring...")
                                    } else {
                                        Text("Re-scan all apps")
                                    }
                                }
                                OutlinedButton(
                                    onClick = onCleanCache,
                                    modifier = Modifier.fillMaxWidth().testTag("cleanCache")
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Clean PulseForge cache (${UiFormat.bytes(overview.reclaimableBytes)})")
                                }
                                Text(
                                    "Cache cleaning only touches PulseForge's own files. Freeing another app's memory " +
                                        "or cache is not permitted by Android, so no button pretends to.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        SectionCard(title = "Findings by category") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                overview.categories.forEach { category ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        SeverityChip(category.severity)
                                        Spacer(Modifier.size(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(category.title, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                category.subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                category.actionHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (overview.offenders.isNotEmpty()) {
                        item {
                            SectionCard(
                                title = "Worth a look",
                                subtitle = "${overview.offenders.size} app(s) with the strongest static signals"
                            ) {
                                Column {
                                    overview.offenders.forEach { app ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(app.displayTitle, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    "${app.packageName} - ${app.dangerousPermissionCount} dangerous, " +
                                                        "${app.exportedComponentCount} exported",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            SeverityChip(app.quickRisk)
                                            Spacer(Modifier.size(6.dp))
                                            TextButton(onClick = { onScanApp(app) }) { Text("Scan") }
                                            TextButton(onClick = { onOpenSettings(app.packageName) }) { Text("Info") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SectionCard(title = "What this view cannot do") {
                            Column {
                                overview.limitations.forEach {
                                    Text("- $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    InfoBanner(
                        text = "This is not a virus scanner and it does not measure battery or CPU. It reads package " +
                            "metadata, scores what it can see, and hands you the system screens to act on it.",
                        tone = BannerTone.NEUTRAL
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private fun scoreHeadline(score: Int): String = when {
    score >= 90 -> "Few items can be optimized"
    score >= 75 -> "A few items can be optimized"
    score >= 50 -> "Several items need attention"
    else -> "Many items need attention"
}
