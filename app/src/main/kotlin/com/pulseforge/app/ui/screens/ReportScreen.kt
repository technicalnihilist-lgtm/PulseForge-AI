package com.pulseforge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.app.export.ExportFormat
import com.pulseforge.app.export.ReportExporter
import com.pulseforge.app.ui.components.FindingCard
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.ScanReport
import com.pulseforge.model.Severity
import com.pulseforge.performance.PlannedAction
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.KeyValueRow
import com.pulseforge.ui.components.ScoreDial
import com.pulseforge.ui.components.SectionCard
import com.pulseforge.ui.components.SeverityChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val tabs = listOf(
    "Overview", "Security", "Performance", "Privacy", "Permissions", "Manifest", "Signing", "Resources", "Compare"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: ScanReport?,
    scan: ScanEntity?,
    plan: List<PlannedAction>,
    candidates: List<ScanEntity>,
    onLoadReport: suspend (String) -> ScanReport?,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOptimize: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pendingFormat by remember { mutableStateOf(ExportFormat.PDF) }

    fun writeTo(uri: android.net.Uri?) {
        val current = report ?: return
        if (uri == null) {
            exportStatus = null
            return
        }
        val format = pendingFormat
        scope.launch {
            runCatching {
                exportStatus = "Writing ${format.label.lowercase()}..."
                val bytes = ReportExporter.bytes(context, current, format)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("The destination could not be opened")
                }
                exportStatus = "Saved ${ReportExporter.fileName(current, format)}"
            }.onFailure { exportStatus = "Export failed: ${it.message}" }
        }
    }

    // One launcher per format so the system picker receives the right MIME type.
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { writeTo(it) }
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { writeTo(it) }
    val mdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { writeTo(it) }

    fun export(format: ExportFormat) {
        val current = report ?: return
        pendingFormat = format
        val name = ReportExporter.fileName(current, format)
        when (format) {
            ExportFormat.PDF -> pdfLauncher.launch(name)
            ExportFormat.JSON -> jsonLauncher.launch(name)
            ExportFormat.MARKDOWN -> mdLauncher.launch(name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(scan?.displayName ?: "Report", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            scan?.packageName ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = onOptimize, modifier = Modifier.testTag("openOptimize")) {
                        Text("Optimize")
                    }
                    IconButton(onClick = onAsk) { Icon(Icons.Filled.Face, contentDescription = "Ask the assistant") }
                    Box {
                        IconButton(onClick = { exportMenuOpen = true }, modifier = Modifier.testTag("exportMenu")) {
                            Icon(Icons.Filled.Share, contentDescription = "Export report")
                        }
                        DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                            ExportFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text("Export ${format.label}") },
                                    onClick = {
                                        exportMenuOpen = false
                                        export(format)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        val current = report
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("Loading report...", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 8.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }
            exportStatus?.let { InfoBanner(text = it, modifier = Modifier.padding(12.dp)) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth()
                when (selectedTab) {
                    0 -> OverviewTab(current, plan, modifier, onAsk)
                    1 -> SecurityTab(current, modifier)
                    2 -> PerformanceTab(current, modifier)
                    3 -> PrivacyTab(current, modifier)
                    4 -> PermissionsTab(current, modifier)
                    5 -> ManifestTab(current, modifier)
                    6 -> SigningTab(current, modifier)
                    7 -> ResourcesTab(current, modifier)
                    else -> CompareTab(current, candidates, onLoadReport, modifier)
                }
            }
        }
    }
}

@Composable
private fun OverviewTab(report: ScanReport, plan: List<PlannedAction>, modifier: Modifier, onAsk: () -> Unit) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (report.meta.isSampleData) {
            item {
                InfoBanner(
                    text = "SAMPLE DATA - this report was generated from a synthetic test fixture, not a real app.",
                    tone = BannerTone.WARNING
                )
            }
        }

        item {
            SectionCard(title = "Summary") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreDial(report.scoring.security, "security")
                    Spacer(Modifier.size(16.dp))
                    ScoreDial(report.scoring.performance, "performance")
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SeverityChip(Severity.CRITICAL)
                            Text(report.counts.critical.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SeverityChip(Severity.HIGH)
                            Text(report.counts.high.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SeverityChip(Severity.MEDIUM)
                            Text(report.counts.medium.toString(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    report.scoring.disclaimer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard(title = "APK") {
                KeyValueRow("File", report.apk.fileName)
                KeyValueRow("Size", UiFormat.bytes(report.apk.fileSizeBytes))
                KeyValueRow("Package", report.apk.manifest?.packageName ?: "unreadable")
                KeyValueRow(
                    "Version",
                    "${report.apk.manifest?.versionName ?: "?"} (${report.apk.manifest?.versionCode ?: "?"})"
                )
                KeyValueRow("SDK", "min ${report.apk.manifest?.minSdk ?: "?"} - target ${report.apk.manifest?.targetSdk ?: "?"}")
                KeyValueRow("Signature", report.apk.signing?.schemeSummary ?: "none")
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        report.apk.sha256,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard(title = "Do this first", subtitle = "${plan.size} prioritised actions") {
                if (plan.isEmpty()) {
                    Text("Nothing crossed the reporting threshold.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column {
                        plan.take(4).forEach { action ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${action.priority}.",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(action.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                }
                                Text(
                                    "${action.origin} - impact ${action.impact.name.lowercase()} - effort ${action.effort.name.lowercase()}" +
                                        if (!action.requiresSourceAccess) " - report only" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAsk, modifier = Modifier.fillMaxWidth()) { Text("Ask the assistant about this report") }
            }
        }

        item {
            SectionCard(title = "Scan details") {
                KeyValueRow("Mode", report.meta.analysisMode)
                KeyValueRow("Duration", UiFormat.duration(report.meta.durationMs))
                KeyValueRow("Engine", "${report.engine.appVersion} - rules ${report.engine.ruleSetVersion}")
                KeyValueRow("Report id", report.reportId.take(8), mono = true)
                KeyValueRow("Generated", UiFormat.dateTime(report.generatedAtEpochMs))
            }
        }

        item {
            SectionCard(title = "Limitations", subtitle = "What this report cannot tell you") {
                Column {
                    report.meta.limitations.forEach {
                        Text("- $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SecurityTab(report: ScanReport, modifier: Modifier) {
    var severityFilter by remember { mutableStateOf<Severity?>(null) }
    var confirmedOnly by remember { mutableStateOf(false) }

    val findings = report.findings
        .filter { severityFilter == null || it.severity == severityFilter }
        .filter { !confirmedOnly || it.isConfirmedObservation }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(selected = severityFilter == null, onClick = { severityFilter = null }, label = { Text("All") })
            listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW).forEach { severity ->
                FilterChip(
                    selected = severityFilter == severity,
                    onClick = { severityFilter = if (severityFilter == severity) null else severity },
                    label = { Text(severity.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = confirmedOnly,
                onClick = { confirmedOnly = !confirmedOnly },
                label = { Text("Observed only") }
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "${findings.size} shown",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (findings.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing here",
                        message = "No finding matches this filter. That means these checks found nothing - " +
                            "not that the app is secure."
                    )
                }
            }
            items(findings, key = { it.id }) { finding -> FindingCard(finding) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
