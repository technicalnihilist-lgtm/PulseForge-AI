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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.model.Impact
import com.pulseforge.model.ScanReport
import com.pulseforge.performance.OptimizationRecipe
import com.pulseforge.performance.PlannedAction
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.KeyValueRow
import com.pulseforge.ui.components.SectionCard
import com.pulseforge.ui.components.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The optimization surface for both sources.
 *
 * For an APK you own it shows the prioritised fixes and a paste-ready build recipe. For an app that
 * is installed on the device it additionally offers the only actions that are actually possible
 * without modifying it: revoke permissions, restrict background usage, uninstall - all through
 * system screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizeScreen(
    report: ScanReport?,
    plan: List<PlannedAction>,
    recipe: OptimizationRecipe?,
    installedPackage: String?,
    onBack: () -> Unit,
    onOpenAppSettings: (String) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onUninstall: (String) -> Unit,
    planText: () -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val body = planText()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                            ?: error("The destination could not be opened")
                    }
                    exportStatus = "Plan exported (${body.length} characters of Markdown)"
                }.onFailure { exportStatus = "Export failed: ${it.message}" }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Optimize") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        val current = report
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No report loaded", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    InfoBanner(
                        text = if (installedPackage != null) {
                            "This app is installed on the device. Android does not let one app rewrite or re-sign " +
                                "another, so the hardening actions below use system screens - revoke permissions, " +
                                "restrict background usage, or uninstall. The build recipe applies only if you own " +
                                "the source."
                        } else {
                            "PulseForge does not rebuild or re-sign APKs. Apply this plan in your own build, then " +
                                "scan the new APK and compare the two reports."
                        },
                        tone = BannerTone.WARNING
                    )
                }

                item {
                    SectionCard(title = "Summary") {
                        KeyValueRow("APK size", UiFormat.bytes(current.apk.fileSizeBytes))
                        KeyValueRow("Planned actions", plan.size.toString())
                        KeyValueRow(
                            "Estimated saving",
                            recipe?.estimatedSavingBytes?.takeIf { it > 0 }?.let { UiFormat.bytes(it) } ?: "not estimated"
                        )
                        KeyValueRow("Impact split", impactSplit(plan))
                    }
                }

                if (installedPackage != null) {
                    item {
                        SectionCard(
                            title = "Harden this app",
                            subtitle = "Actions Android actually allows on someone else's app"
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onOpenAppSettings(installedPackage) },
                                    modifier = Modifier.fillMaxWidth().testTag("revokePermissions")
                                ) { Text("Review and revoke permissions") }
                                OutlinedButton(
                                    onClick = onOpenBatterySettings,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Restrict background battery usage") }
                                OutlinedButton(
                                    onClick = { onUninstall(installedPackage) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Uninstall this app") }
                                Text(
                                    "PulseForge cannot disable a component, patch the APK or change its signature. " +
                                        "Anything else needs the app's own developer.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (plan.isNotEmpty()) {
                    item {
                        SectionCard(title = "Fix first", subtitle = "${plan.size} prioritised actions") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                plan.take(12).forEach { action -> ActionRow(action) }
                            }
                        }
                    }
                }

                val currentRecipe = recipe
                if (currentRecipe != null && !currentRecipe.isEmpty) {
                    item {
                        SectionCard(
                            title = "Build recipe",
                            subtitle = "Paste-ready configuration, conditioned on this report's evidence"
                        ) {
                            Text(
                                "Estimated total: " +
                                    (currentRecipe.estimatedSavingBytes.takeIf { it > 0 }?.let { UiFormat.bytes(it) }
                                        ?: "no size estimate"),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    currentRecipe.sections.forEach { section ->
                        item {
                            SectionCard(title = section.title, subtitle = "Target: ${section.target}") {
                                Text(section.rationale, style = MaterialTheme.typography.bodyMedium)
                                section.estimatedSavingBytes?.takeIf { it > 0 }?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Tag("estimate ${UiFormat.bytes(it)}")
                                }
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    SelectionContainer {
                                        Text(
                                            section.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SectionCard(title = "Notes") {
                            Column {
                                currentRecipe.notes.forEach {
                                    Text("- $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            val name = "optimize-${(current.apk.manifest?.packageName ?: "apk").replace('.', '_')}.md"
                            exportLauncher.launch(name)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("exportPlan")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Export this plan (Markdown)")
                    }
                }

                exportStatus?.let { item { InfoBanner(text = it) } }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ActionRow(action: PlannedAction) {
    Column {
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 22.dp, top = 2.dp)) {
            Tag(impactLabel(action.impact), color = impactColor(action.impact))
            Tag("effort ${action.effort.name.lowercase()}")
            if (!action.requiresSourceAccess) Tag("report only", color = MaterialTheme.colorScheme.outline)
        }
        Text(
            action.rationale,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp, top = 2.dp)
        )
    }
}

private fun impactLabel(impact: Impact): String = "impact ${impact.name.lowercase()}"

@Composable
private fun impactColor(impact: Impact): Color = when (impact) {
    Impact.HIGH -> MaterialTheme.colorScheme.error
    Impact.MEDIUM -> MaterialTheme.colorScheme.primary
    Impact.LOW -> MaterialTheme.colorScheme.outline
}

private fun impactSplit(plan: List<PlannedAction>): String {
    if (plan.isEmpty()) return "none"
    val high = plan.count { it.impact == Impact.HIGH }
    val medium = plan.count { it.impact == Impact.MEDIUM }
    val low = plan.count { it.impact == Impact.LOW }
    return "$high high, $medium medium, $low low"
}
