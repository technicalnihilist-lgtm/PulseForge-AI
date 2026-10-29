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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.Severity
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.ScoreDial
import com.pulseforge.ui.components.SectionCard
import com.pulseforge.ui.components.SeverityChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    scans: List<ScanEntity>,
    onImport: () -> Unit,
    onOpenReport: (String) -> Unit,
    onOpenScan: (String) -> Unit,
    onOpenSourceReview: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val latest = scans.firstOrNull { it.status == "COMPLETED" }
    val running = scans.firstOrNull { it.status == "RUNNING" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PulseForge AI", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "APK security analyzer & optimizer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenAbout, modifier = Modifier.testTag("methodology")) {
                        Text("Method")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    InfoBanner(
                        text = "Everything runs on this device. PulseForge has no INTERNET access, so no APK, " +
                            "source file, secret or report can leave your phone. The only permission it asks " +
                            "for is QUERY_ALL_PACKAGES, to list installed apps.",
                        tone = BannerTone.PRIVACY
                    )
                }

                if (running != null) {
                    item {
                        SectionCard(title = "Scan in progress", subtitle = running.displayName) {
                            Text(
                                "${running.stage.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} - " +
                                    UiFormat.percent(running.progress),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(onClick = { onOpenScan(running.id) }) { Text("View progress") }
                        }
                    }
                }

                item {
                    SectionCard(
                        title = if (latest == null) "Start your first scan" else "Latest scan",
                        subtitle = latest?.displayName
                    ) {
                        if (latest == null) {
                            EmptyState(
                                title = "No scans yet",
                                message = "Import an APK to get a security, performance and privacy report. " +
                                    "Nothing is installed or executed.",
                                icon = Icons.Filled.Lock
                            )
                            Button(onClick = onImport, modifier = Modifier.fillMaxWidth().testTag("importApk")) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Import an APK")
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ScoreDial(latest.securityScore, "security")
                                Spacer(Modifier.size(14.dp))
                                ScoreDial(latest.performanceScore, "performance")
                                Spacer(Modifier.size(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        latest.packageName ?: "package unknown",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${UiFormat.bytes(latest.fileSizeBytes)} - ${latest.totalFindings} findings",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (latest.criticalCount > 0) SeverityChip(Severity.CRITICAL)
                                        if (latest.highCount > 0) SeverityChip(Severity.HIGH)
                                        if (latest.mediumCount > 0) SeverityChip(Severity.MEDIUM)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { onOpenReport(latest.id) },
                                    modifier = Modifier.weight(1f).testTag("openReport")
                                ) { Text("Open report") }
                                FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                                    Text("Scan another")
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard(title = "Tools") {
                        ToolRow("Review source files", "Concrete code changes for files you own", onOpenSourceReview)
                        ToolRow("Methodology & limitations", "How findings, scores and estimates are produced", onOpenAbout)
                    }
                }

                if (scans.size > 1) {
                    item {
                        SectionCard(title = "Recent scans", subtitle = "${scans.size} in history") {
                            Column { scans.take(4).forEach { scan -> ScanRow(scan) { onOpenReport(scan.id) } } }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Open") }
    }
}

@Composable
fun ScanRow(scan: ScanEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(scan.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${UiFormat.relative(scan.startedAtEpochMs)} - ${scan.status.lowercase()}" +
                    (scan.securityScore?.let { " - security $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) { Text("Open") }
    }
}
