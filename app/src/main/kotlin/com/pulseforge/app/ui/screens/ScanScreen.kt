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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulseforge.app.ui.AppViewModel
import com.pulseforge.model.ScanStage
import com.pulseforge.model.ScanStatus
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(viewModel: AppViewModel, scanId: String, onFinished: (String) -> Unit, onBack: () -> Unit) {
    val scan by viewModel.observeScan(scanId).collectAsStateWithLifecycle()

    LaunchedEffect(scan?.status) {
        val entity = scan
        if (entity != null && entity.status == ScanStatus.COMPLETED.name) onFinished(entity.id)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Scanning") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val entity = scan
                if (entity == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Loading scan...")
                    }
                    return@Column
                }

                SectionCard(title = entity.displayName, subtitle = entity.status.lowercase()) {
                    LinearProgressIndicator(
                        progress = { entity.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag("scanProgress")
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${UiFormat.percent(entity.progress)} - ${entity.message ?: entity.stage.replace('_', ' ')}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (entity.status == ScanStatus.FAILED.name || entity.status == ScanStatus.CANCELLED.name) {
                    SectionCard(title = "Scan stopped") {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(
                                    entity.errorMessage ?: "The scan did not finish",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                entity.errorHint?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                entity.errorCode?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Code: $it", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                            OutlinedButton(
                                onClick = { viewModel.deleteScan(entity.id); onBack() },
                                modifier = Modifier.weight(1f)
                            ) { Text("Discard") }
                        }
                    }
                } else {
                    SectionCard(title = "Stages") {
                        Column {
                            ScanStage.entries
                                .filter { it != ScanStage.QUEUED && it != ScanStage.CANCELLED }
                                .forEach { stage -> StageRow(stage, entity.stage, entity.progress) }
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.cancelScan(entity.id); onBack() },
                        modifier = Modifier.fillMaxWidth().testTag("cancelScan")
                    ) { Text("Cancel scan") }

                    InfoBanner(
                        text = "You can leave this screen: the scan runs in a background worker and the result " +
                            "appears in History when it is ready."
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StageRow(stage: ScanStage, currentStage: String, progress: Float) {
    val currentIndex = ScanStage.entries.indexOfFirst { it.name == currentStage }
    val index = ScanStage.entries.indexOf(stage)
    val done = index < currentIndex || progress >= 1f
    val active = index == currentIndex

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(
                Icons.Filled.Check,
                contentDescription = "done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            active -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            else -> Icon(
                Icons.Filled.Close,
                contentDescription = "pending",
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            stage.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
