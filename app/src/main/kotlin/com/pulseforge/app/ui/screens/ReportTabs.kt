package com.pulseforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.ComponentType
import com.pulseforge.model.ScanReport
import com.pulseforge.report.ReportDiff
import com.pulseforge.ui.UiFormat
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.KeyValueRow
import com.pulseforge.ui.components.ProportionBar
import com.pulseforge.ui.components.SectionCard
import com.pulseforge.ui.components.SeverityChip
import com.pulseforge.ui.components.Tag

@Composable
fun PerformanceTab(report: ScanReport, modifier: Modifier) {
    val perf = report.performance
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(title = "Measured", subtitle = "Read directly from the APK") {
                Column { perf.measured.forEach { KeyValueRow(it.label, it.value) } }
            }
        }
        if (perf.estimates.isNotEmpty()) {
            item {
                SectionCard(title = "Estimated", subtitle = "Derived - the basis is stated for each value") {
                    Column {
                        perf.estimates.forEach { estimate ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(estimate.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Text(estimate.estimate, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                }
                                Text(estimate.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Tag("confidence ${estimate.confidence.name.lowercase()}")
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "Not measurable from an APK", subtitle = "PulseForge will not invent these") {
                Column {
                    perf.unavailable.forEach { metric ->
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text(metric.label, style = MaterialTheme.typography.bodyLarge)
                            Text(metric.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("How to measure: ${metric.howToMeasure}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(perf.runtimeProfilingNote, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (perf.composition.isNotEmpty()) {
            item {
                SectionCard(title = "Size composition") {
                    val total = perf.composition.sumOf { it.bytes }.coerceAtLeast(1)
                    Column {
                        perf.composition.forEach { category ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Row {
                                    Text(category.category, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(UiFormat.bytes(category.bytes), style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.height(3.dp))
                                ProportionBar(category.bytes.toFloat() / total, MaterialTheme.colorScheme.primary)
                                Text(
                                    "${category.count} files - ${"%.0f".format(category.bytes * 100.0 / total)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        if (perf.duplicates.isNotEmpty()) {
            item {
                SectionCard(title = "Duplicate files", subtitle = "${perf.duplicates.size} groups") {
                    Column {
                        perf.duplicates.forEach { duplicate ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text("${UiFormat.bytes(duplicate.wastedBytes)} wasted", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                duplicate.paths.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (perf.oversizedImages.isNotEmpty()) {
            item {
                SectionCard(title = "Oversized images") {
                    Column {
                        perf.oversizedImages.forEach { image ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(image.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    Text(
                                        if (image.width > 0) "${image.width}x${image.height} - ${image.format}" else image.format,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(UiFormat.bytes(image.bytes), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (perf.actions.isNotEmpty()) {
            item {
                SectionCard(title = "Optimization plan") {
                    Column {
                        perf.actions.forEachIndexed { index, action ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text("${index + 1}. ${action.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "impact ${action.impact.name.lowercase()} - effort ${action.effort.name.lowercase()}" +
                                        (action.estimatedSavingBytes?.let { " - est. ${UiFormat.bytes(it)}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(action.rationale, style = MaterialTheme.typography.bodySmall)
                                action.steps.take(3).forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                                action.caveats?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun PrivacyTab(report: ScanReport, modifier: Modifier) {
    val privacy = report.privacy
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(title = "Privacy overview") {
                KeyValueRow("Dangerous permissions", privacy.dangerousPermissionCount.toString())
                KeyValueRow("Exported components", privacy.exportedComponentCount.toString())
                KeyValueRow("SDK signals", privacy.trackerSignals.size.toString())
            }
        }
        if (privacy.permissionGroups.isNotEmpty()) {
            item {
                SectionCard(title = "Permission groups") {
                    Column {
                        privacy.permissionGroups.forEach { group ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SeverityChip(group.risk)
                                    Spacer(Modifier.size(8.dp))
                                    Text(group.group, style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(group.permissions.joinToString(", "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text(group.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (privacy.trackerSignals.isNotEmpty()) {
            item {
                SectionCard(title = "SDK signals", subtitle = "Detected from class names, not behaviour") {
                    Column {
                        privacy.trackerSignals.forEach { signal ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(signal.name, style = MaterialTheme.typography.bodyLarge)
                                Text(signal.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "Notes") {
                Column { privacy.notes.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun PermissionsTab(report: ScanReport, modifier: Modifier) {
    val permissions = report.apk.manifest?.requestedPermissions.orEmpty()
    val groups = report.privacy.permissionGroups
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(title = "${permissions.size} permissions requested") {
                if (permissions.isEmpty()) {
                    Text("This app requests no permissions.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column {
                        permissions.sorted().forEach { permission ->
                            val group = groups.firstOrNull { g -> g.permissions.any { permission.endsWith(".$it") } }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(permission.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        permission,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                group?.let { SeverityChip(it.risk) }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ManifestTab(report: ScanReport, modifier: Modifier) {
    val manifest = report.apk.manifest
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (manifest == null) {
            item {
                InfoBanner(
                    text = "The manifest could not be parsed: ${report.apk.manifestParseError ?: "unknown error"}",
                    tone = BannerTone.WARNING
                )
            }
        } else {
            item {
                SectionCard(title = "Application") {
                    KeyValueRow("package", manifest.packageName, mono = true)
                    KeyValueRow("versionName", manifest.versionName ?: "-")
                    KeyValueRow("versionCode", manifest.versionCode?.toString() ?: "-")
                    KeyValueRow("minSdk", manifest.minSdk?.toString() ?: "-")
                    KeyValueRow("targetSdk", manifest.targetSdk?.toString() ?: "-")
                    KeyValueRow("debuggable", manifest.debuggable?.toString() ?: "unset (false)")
                    KeyValueRow("allowBackup", manifest.allowBackup?.toString() ?: "unset (true)")
                    KeyValueRow("usesCleartextTraffic", manifest.usesCleartextTraffic?.toString() ?: "unset")
                    KeyValueRow("networkSecurityConfig", manifest.networkSecurityConfig ?: "none")
                    KeyValueRow("applicationClass", manifest.applicationClass ?: "android.app.Application")
                }
            }
            item {
                SectionCard(
                    title = "Components",
                    subtitle = "${manifest.components.size} declared - ${report.privacy.exportedComponentCount} exported"
                ) {
                    Column {
                        ComponentType.entries.forEach { type ->
                            val ofType = manifest.components.filter { it.type == type }
                            if (ofType.isEmpty()) return@forEach
                            Text(
                                type.name.lowercase().replaceFirstChar { it.uppercase() } + "s (${ofType.size})",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                            ofType.forEach { component ->
                                Column(Modifier.padding(vertical = 3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            component.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (component.effectiveExported(manifest.targetSdk)) {
                                            Tag("exported", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    component.intentFilters.forEach { filter ->
                                        Text(
                                            "  filter: ${filter.summary}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    component.permission?.let { Text("  permission: $it", style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Features") {
                    if (manifest.features.isEmpty()) {
                        Text("No uses-feature declared.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column {
                            manifest.features.forEach {
                                Text("${it.name}${if (it.required) " (required)" else " (optional)"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SigningTab(report: ScanReport, modifier: Modifier) {
    val signing = report.apk.signing
    val cert = signing?.certificate
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(title = "Signature") {
                if (signing == null || cert == null) {
                    Text("No certificate could be extracted from this archive.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    KeyValueRow("Schemes", signing.schemeSummary)
                    KeyValueRow("Verified", "${signing.verified} - ${signing.verificationMessage ?: "-"}")
                    KeyValueRow("Subject", cert.subject)
                    KeyValueRow("Issuer", cert.issuer)
                    KeyValueRow("Serial", cert.serialNumber, mono = true)
                    KeyValueRow("Algorithm", "${cert.keyAlgorithm} ${cert.keySizeBits} bit")
                    KeyValueRow("Valid from", UiFormat.dateTime(cert.notBeforeEpochMs))
                    KeyValueRow("Valid until", UiFormat.dateTime(cert.notAfterEpochMs))
                    KeyValueRow("Self-signed", cert.isSelfSigned.toString())
                    KeyValueRow("Debug key", cert.isKnownDebugKey.toString())
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(cert.sha256, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        if (signing != null && signing.warnings.isNotEmpty()) {
            item {
                SectionCard(title = "Warnings") {
                    Column { signing.warnings.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
        item {
            InfoBanner(
                text = "A valid signature means the archive was signed by the holder of this key and has not been " +
                    "modified since. It says nothing about whether the app is safe."
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ResourcesTab(report: ScanReport, modifier: Modifier) {
    val resources = report.apk.resources
    val dex = report.apk.dex
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(title = "Bytecode") {
                KeyValueRow("DEX files", dex.dexFiles.toString())
                KeyValueRow("Referenced methods", UiFormat.count(dex.totalReferencedMethods))
                KeyValueRow("Defined classes", UiFormat.count(dex.definedClasses))
                KeyValueRow("String constants", UiFormat.count(dex.stringConstantCount))
                KeyValueRow("Multidex", dex.multidex.toString())
            }
        }
        if (dex.packageRoots.isNotEmpty()) {
            item {
                SectionCard(title = "Package roots", subtitle = "Where the app's own code lives") {
                    Column { dex.packageRoots.take(10).forEach { KeyValueRow(it.prefix, "${it.classCount} classes", mono = true) } }
                }
            }
        }
        if (resources != null) {
            item {
                SectionCard(title = "Archive") {
                    KeyValueRow("Entries", resources.entryCount.toString())
                    KeyValueRow("Uncompressed", UiFormat.bytes(resources.totalUncompressedBytes))
                    KeyValueRow("Images", "${resources.imageCount} - ${UiFormat.bytes(resources.imageBytes)}")
                    KeyValueRow("ABIs", resources.abis.joinToString(", ").ifBlank { "none" })
                    KeyValueRow("resources.arsc", if (resources.hasResourcesArsc) "present" else "missing")
                }
            }
            if (resources.largestEntries.isNotEmpty()) {
                item {
                    SectionCard(title = "Largest entries") {
                        Column {
                            resources.largestEntries.take(12).forEach {
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(
                                        it.path,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Text(UiFormat.bytes(it.bytes), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (resources.unusedAssetCandidates.isNotEmpty()) {
                item {
                    SectionCard(title = "Possibly unused assets", subtitle = "Heuristic: the filename never appears in the bytecode") {
                        Column {
                            resources.unusedAssetCandidates.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
        if (dex.apiUsages.isNotEmpty()) {
            item {
                SectionCard(title = "Security-relevant API usage", subtitle = "From the DEX method table") {
                    Column {
                        dex.apiUsages.take(25).forEach { usage ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    usage.display,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("x${usage.occurrences}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun CompareTab(
    report: ScanReport,
    candidates: List<ScanEntity>,
    loadReport: suspend (String) -> ScanReport?,
    modifier: Modifier
) {
    var baselineId by remember { mutableStateOf<String?>(null) }
    var baseline by remember { mutableStateOf<ScanReport?>(null) }

    LaunchedEffect(baselineId) { baseline = baselineId?.let { loadReport(it) } }

    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(
                title = "Compare with another scan",
                subtitle = "Pick the earlier build; this report is treated as the newer one"
            ) {
                if (candidates.isEmpty()) {
                    EmptyState(
                        title = "Nothing to compare",
                        message = "Scan the same app twice - before and after a change - and the difference appears here."
                    )
                } else {
                    Column {
                        candidates.take(6).forEach { candidate ->
                            TextButton(onClick = { baselineId = candidate.id }) {
                                Text(
                                    "${candidate.displayName} - ${UiFormat.relative(candidate.startedAtEpochMs)}",
                                    fontWeight = if (baselineId == candidate.id) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        val earlier = baseline
        if (earlier != null) {
            val delta = ReportDiff.compare(earlier, report)
            item {
                SectionCard(title = "Result") {
                    KeyValueRow(
                        "Size",
                        "${if (delta.sizeDeltaBytes <= 0) "" else "+"}${UiFormat.bytes(delta.sizeDeltaBytes)} " +
                            "(${"%.1f".format(delta.sizeDeltaPercent)}%)",
                        emphasize = true
                    )
                    KeyValueRow("Security score", "${delta.scoreDelta ?: 0}", emphasize = true)
                    KeyValueRow("Performance score", "${delta.performanceDelta ?: 0}")
                    KeyValueRow("Findings resolved", delta.findingsResolved.size.toString())
                    KeyValueRow("Findings added", delta.findingsAdded.size.toString())
                    KeyValueRow("Unchanged", delta.findingsUnchanged.toString())
                }
            }
            if (delta.findingsResolved.isNotEmpty()) {
                item {
                    SectionCard(title = "Resolved") {
                        Column {
                            delta.findingsResolved.forEach {
                                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SeverityChip(it.severity)
                                    Spacer(Modifier.size(8.dp))
                                    Text(it.title, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (delta.findingsAdded.isNotEmpty()) {
                item {
                    SectionCard(title = "Newly introduced") {
                        Column {
                            delta.findingsAdded.forEach {
                                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SeverityChip(it.severity)
                                    Spacer(Modifier.size(8.dp))
                                    Text(it.title, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                InfoBanner(
                    text = "A smaller size or higher score is only meaningful if the two APKs are the same app. " +
                        "PulseForge compares reports, not builds: it cannot prove the change caused the difference.",
                    tone = BannerTone.WARNING
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
