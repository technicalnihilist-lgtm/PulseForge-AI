package com.pulseforge.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulseforge.app.ui.AppViewModel
import com.pulseforge.app.ui.ImportState
import com.pulseforge.app.ui.UriUtils
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.KeyValueRow
import com.pulseforge.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: AppViewModel, onStarted: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var document by remember { mutableStateOf<UriUtils.DocumentInfo?>(null) }
    var deepScan by remember { mutableStateOf(settings.deepScan) }
    var keepCopy by remember { mutableStateOf(settings.keepApkCopy) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            document = UriUtils.describe(context, uri)
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    LaunchedEffect(importState) {
        val state = importState
        if (state is ImportState.Started) {
            viewModel.consumeImportResult()
            onStarted(state.scanId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import APK") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                InfoBanner(
                    text = "Pick an APK with the system file picker. PulseForge copies it into private app " +
                        "storage, analyses it, and never installs, executes or uploads it."
                )

                SectionCard(title = "1. Choose the file") {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().testTag("chooseApk")
                    ) { Text(if (pickedUri == null) "Choose APK" else "Choose a different file") }

                    document?.let { info ->
                        Spacer(Modifier.height(12.dp))
                        KeyValueRow("File", info.displayName)
                        KeyValueRow("Size", info.sizeBytes?.let { UiFormat.bytes(it) } ?: "unknown")
                        if (!info.displayName.endsWith(".apk", ignoreCase = true)) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner(
                                text = "This file is not named .apk. PulseForge will still check the ZIP signature " +
                                    "and refuse anything that is not an APK.",
                                tone = BannerTone.WARNING
                            )
                        }
                    }
                }

                SectionCard(title = "2. Options") {
                    OptionRow(
                        title = "Deep scan",
                        subtitle = "Parse DEX bytecode, resources.arsc and compiled XML. Slower but finds far more.",
                        checked = deepScan,
                        onCheckedChange = { deepScan = it; viewModel.setDeepScan(it) }
                    )
                    OptionRow(
                        title = "Keep the APK copy",
                        subtitle = "Lets you re-run the scan later. Off: the copy is deleted as soon as the report is ready.",
                        checked = keepCopy,
                        onCheckedChange = { keepCopy = it; viewModel.setKeepCopy(it) }
                    )
                }

                when (val state = importState) {
                    is ImportState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(state.step, style = MaterialTheme.typography.bodyMedium)
                    }
                    is ImportState.Failed -> SectionCard(title = "Import failed") {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(10.dp))
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> Unit
                }

                Button(
                    onClick = {
                        val uri = pickedUri ?: return@Button
                        viewModel.importAndScan(uri, document?.displayName ?: "imported.apk")
                    },
                    enabled = pickedUri != null && importState !is ImportState.Working,
                    modifier = Modifier.fillMaxWidth().testTag("startScan")
                ) { Text("Analyze APK") }

                Text(
                    "Analysis is read-only. PulseForge never installs the APK, never executes its code, and never " +
                        "bypasses signature, licence or payment checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
