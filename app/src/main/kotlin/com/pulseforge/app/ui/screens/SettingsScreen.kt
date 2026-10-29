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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulseforge.app.ui.AppViewModel
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.data.settings.ThemeMode
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    scans: List<ScanEntity>,
    onOpenAbout: () -> Unit,
    onOpenSourceReview: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val cachedBytes = remember(scans) { viewModel.cachedBytes() }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(title = "Appearance") {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = settings.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = settings.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "Follow the system theme"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    Text(
                        "Both themes meet contrast guidance; severity is always labelled with text, never colour alone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(title = "Scanning") {
                    SettingSwitch(
                        title = "Deep scan",
                        subtitle = "Parse DEX bytecode, resources.arsc and compiled XML resources.",
                        checked = settings.deepScan,
                        onChange = viewModel::setDeepScan
                    )
                    SettingSwitch(
                        title = "Keep the APK copy",
                        subtitle = "Needed to re-open a report later. Off: the copy is deleted once the report is saved.",
                        checked = settings.keepApkCopy,
                        onChange = viewModel::setKeepCopy
                    )
                    SettingSwitch(
                        title = "I own the source of the apps I scan",
                        subtitle = "Enables source-level fix suggestions. Leave off for third-party APKs: PulseForge " +
                            "will then only report, never claim it can rebuild the app.",
                        checked = settings.userOwnsSource,
                        onChange = viewModel::setUserOwnsSource
                    )
                }

                SectionCard(title = "Data on this device") {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("${scans.size} scans", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${UiFormat.bytes(cachedBytes)} of cached APKs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("deleteAllSettings")) {
                            Text("Delete all")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        text = "PulseForge requests no permissions and has no INTERNET access. Reports, APKs and " +
                            "settings never leave the device unless you export a file yourself.",
                        tone = BannerTone.PRIVACY
                    )
                }

                SectionCard(title = "Assistant") {
                    Text(
                        if (viewModel.modelAvailable()) "A local model is bundled with this build."
                        else "No language model is bundled. The assistant is a deterministic engine that composes " +
                            "answers from report fields - it cannot invent findings.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        viewModel.modelNote(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(title = "Learn more") {
                    TextButton(onClick = onOpenAbout) { Text("Methodology, scoring rules and licences") }
                    TextButton(onClick = onOpenSourceReview) { Text("Review source files I own") }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = { Text("This removes ${scans.size} reports and ${UiFormat.bytes(cachedBytes)} of cached APKs.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAllScans(); confirmDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
