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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pulseforge.app.ui.AppViewModel
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.Severity
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.ScoreDial
import com.pulseforge.ui.components.SeverityChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    scans: List<ScanEntity>,
    onOpenReport: (String) -> Unit,
    onOpenScan: (String) -> Unit
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    if (scans.isNotEmpty()) {
                        TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.testTag("deleteAll")) {
                            Text("Delete all")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (scans.isEmpty()) {
                EmptyState(
                    title = "No scans yet",
                    message = "Imported APKs and their reports are stored only on this device. Deleting a scan also " +
                        "deletes the cached APK.",
                    icon = Icons.Filled.List,
                    modifier = Modifier.widthIn(max = 480.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(scans, key = { it.id }) { scan ->
                        ScanHistoryCard(
                            scan = scan,
                            onOpen = { if (scan.status == "COMPLETED") onOpenReport(scan.id) else onOpenScan(scan.id) },
                            onDelete = { viewModel.deleteScan(scan.id) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete every scan?") },
            text = {
                Text(
                    "This removes ${scans.size} reports and any cached APK copies from this device. " +
                        "Exported files are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAllScans(); confirmDeleteAll = false }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ScanHistoryCard(scan: ScanEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(scan.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        scan.packageName ?: scan.status.lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                IconButton(onClick = { confirm = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete scan")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scan.status == "COMPLETED") {
                    ScoreDial(scan.securityScore, "security", size = 54.dp)
                    Spacer(Modifier.size(10.dp))
                    ScoreDial(scan.performanceScore, "perf", size = 54.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (scan.criticalCount > 0) SeverityChip(Severity.CRITICAL)
                            if (scan.highCount > 0) SeverityChip(Severity.HIGH)
                            if (scan.mediumCount > 0) SeverityChip(Severity.MEDIUM)
                        }
                        Text(
                            "${scan.totalFindings} findings - ${UiFormat.bytes(scan.fileSizeBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            UiFormat.relative(scan.startedAtEpochMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            scan.status.lowercase().replaceFirstChar { it.uppercase() } + " - ${UiFormat.percent(scan.progress)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            scan.errorMessage ?: scan.message ?: scan.stage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onOpen) { Text(if (scan.status == "COMPLETED") "Report" else "Open") }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this scan?") },
            text = { Text("The report and any cached APK copy for ${scan.displayName} will be removed.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
