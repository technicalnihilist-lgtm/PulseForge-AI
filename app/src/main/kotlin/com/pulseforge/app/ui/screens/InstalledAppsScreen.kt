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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.model.InstalledApp
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.SectionCard
import com.pulseforge.ui.components.SeverityChip

/**
 * Lists the apps installed on this device and starts a scan for one of them.
 *
 * Stateless on purpose: the list, the toggle and the callbacks all come from the caller, which
 * keeps the screen testable and lets the UI test render it with synthetic data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    apps: List<InstalledApp>,
    loading: Boolean,
    includeSystem: Boolean,
    onToggleSystem: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onScan: (InstalledApp) -> Unit,
    onOpenSettings: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visible = apps.filter { app ->
        query.isBlank() ||
            app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Installed apps") },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.testTag("refreshApps")) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    InfoBanner(
                        text = "PulseForge can read and analyse apps installed on this device. It cannot modify " +
                            "them: Android forbids rewriting or re-signing another app, and any such change would " +
                            "break its signature. Use the report to decide, then use the system App-info screen to " +
                            "revoke permissions or uninstall.",
                        tone = BannerTone.WARNING
                    )
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f).testTag("appSearch"),
                            placeholder = { Text("Search by name or package") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                        )
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = includeSystem,
                            onClick = { onToggleSystem(!includeSystem) },
                            label = { Text("Include system apps") },
                            modifier = Modifier.testTag("systemToggle")
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "${visible.size} shown",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (loading && apps.isEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text("Reading the package list...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (!loading && visible.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No apps to show",
                            message = "Nothing matches this filter. PulseForge needs the QUERY_ALL_PACKAGES " +
                                "permission to list installed apps; if the list stays empty, the platform is " +
                                "restricting package visibility."
                        )
                    }
                }

                items(visible, key = { it.packageName }) { app ->
                    InstalledAppCard(app = app, onScan = { onScan(app) }, onOpenSettings = { onOpenSettings(app.packageName) })
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun InstalledAppCard(app: InstalledApp, onScan: () -> Unit, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                SeverityChip(app.quickRisk)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("v${app.versionName ?: "?"} (${app.versionCode})")
                    if (app.isSystemApp) append(" - system")
                    if (app.isDebuggable) append(" - debuggable")
                    append(" - ${UiFormat.bytes(app.apkSizeBytes)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${app.permissionCount} permissions (${app.dangerousPermissionCount} dangerous) - " +
                    "${app.exportedComponentCount} exported components",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!app.apkReadable) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "APK not readable on this device - the scan will cover manifest metadata only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, modifier = Modifier.weight(1f).testTag("scan_${app.packageName}")) {
                    Text("Scan")
                }
                TextButton(onClick = onOpenSettings) { Text("App info") }
            }
        }
    }
}
