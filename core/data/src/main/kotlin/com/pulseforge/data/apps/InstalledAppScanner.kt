package com.pulseforge.data.apps

import android.content.Context
import com.pulseforge.apk.ApkScanInput
import com.pulseforge.model.Confidence
import com.pulseforge.model.EngineInfo
import com.pulseforge.model.EvidenceItem
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.FindingCategory
import com.pulseforge.model.InstalledApp
import com.pulseforge.model.ScanMeta
import com.pulseforge.model.ScanReport
import com.pulseforge.model.ScanStage
import com.pulseforge.model.Severity
import com.pulseforge.performance.PerformanceAnalyzer
import com.pulseforge.report.ReportRedactor
import com.pulseforge.report.ScanPipeline
import com.pulseforge.report.ScoreCalculator
import com.pulseforge.security.PrivacyAnalyzer
import com.pulseforge.security.RuleCatalog
import com.pulseforge.security.SecurityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Scans an app that is already installed on this device.
 *
 * Two paths, and the report always says which one ran:
 *
 * 1. **Full static analysis** — the APK at `ApplicationInfo.sourceDir` is readable, so the same
 *    pipeline as an imported file runs (DEX, resources, certificate, duplicates).
 * 2. **Manifest-level analysis** — the platform will not let us read the APK, so facts come from
 *    PackageManager. The report states that code-level checks did not run.
 *
 * PulseForge cannot modify another app: Android forbids rewriting or re-signing an installed APK,
 * and doing so would break its signature. The optimization plan is therefore advisory for
 * third-party apps, and the UI offers the system App-info screen so the user can revoke
 * permissions or uninstall.
 */
class InstalledAppScanner(
    private val context: Context,
    private val repository: InstalledAppsRepository,
    private val pipeline: ScanPipeline,
    private val engine: SecurityEngine = SecurityEngine()
) {

    suspend fun scan(
        app: InstalledApp,
        apkFile: File?,
        onProgress: suspend (ScanStage, Float, String) -> Unit = { _, _, _ -> }
    ): ScanReport = withContext(Dispatchers.IO) {
        if (apkFile != null) {
            pipeline.run(apkFile, "${app.displayTitle}.apk", onProgress)
        } else {
            onProgress(ScanStage.PARSING_MANIFEST, 0.4f, "Reading package metadata")
            metadataReport(app)
        }
    }

    private fun metadataReport(app: InstalledApp): ScanReport {
        val startedAt = System.currentTimeMillis()
        val facts = PackageFactsBuilder.build(app, repository.packageInfo(app.packageName), repository.launcherComponents())
        val input = ApkScanInput(facts = facts)

        val findings = engine.evaluate(input).toMutableList()
        findings += skippedChecksFinding(app)

        val performance = PerformanceAnalyzer.analyse(input)
        val privacy = PrivacyAnalyzer.analyse(input)
        val scoring = ScoreCalculator.build(
            findings = findings,
            performance = performance,
            apkSizeBytes = facts.fileSizeBytes,
            rulesetVersion = RuleCatalog.VERSION,
            coverage = RuleCatalog.ids
        )

        val finishedAt = System.currentTimeMillis()
        val report = ScanReport(
            reportId = UUID.randomUUID().toString(),
            generatedAtEpochMs = finishedAt,
            engine = EngineInfo(
                appVersion = "1.0.0",
                ruleSetVersion = RuleCatalog.VERSION,
                analyzers = listOf(
                    "PackageManager metadata",
                    "Security rule set ${RuleCatalog.VERSION}",
                    "Privacy analyser"
                )
            ),
            apk = facts,
            findings = findings.sortedWith(compareByDescending<Finding> { it.severity.rank }.thenBy { it.title }),
            performance = performance,
            privacy = privacy,
            scoring = scoring,
            meta = ScanMeta(
                startedAtEpochMs = startedAt,
                durationMs = finishedAt - startedAt,
                analysisMode = "Installed app - PackageManager metadata only (APK not readable)",
                stagesCompleted = listOf("PARSING_MANIFEST", "RUNNING_SECURITY_RULES", "SCORING"),
                limitations = ScanPipeline.LIMITATIONS + listOf(
                    "This app's APK could not be opened, so DEX, resource, duplicate and certificate checks did not run.",
                    "Intent filters are not exposed by PackageManager; only the launcher filter is reconstructed."
                ),
                isSampleData = false
            )
        )
        return ReportRedactor.sanitize(report)
    }

    private fun skippedChecksFinding(app: InstalledApp) = Finding(
        id = "PF-META-001|${app.packageName}",
        ruleId = "PF-META-001",
        title = "Code-level checks did not run",
        category = FindingCategory.QUALITY,
        severity = Severity.INFO,
        confidence = Confidence.CONFIRMED,
        evidenceType = EvidenceType.DIRECT_OBSERVATION,
        summary = "PulseForge could not open this app's APK, so the analysis covers manifest metadata only.",
        technicalDetail = "The manifest flags, permissions and exported components below come from PackageManager " +
            "and are accurate. Bytecode patterns (crypto, WebView, secrets), resource analysis, duplicate " +
            "detection and the signing certificate could not be inspected, so their absence here is not " +
            "evidence of safety. This is a platform restriction on this device, not a property of the app.",
        remediation = "To get the full report, obtain the APK (for example with an app-backup tool you trust) " +
            "and import it as a file. PulseForge analyses it the same way.",
        evidence = listOf(
            EvidenceItem("Package", app.packageName, "PackageManager"),
            EvidenceItem("APK path", app.apkPath ?: "unavailable", "ApplicationInfo.sourceDir"),
            EvidenceItem("Readable", app.apkReadable.toString(), "File.canRead")
        ),
        falsePositiveNote = "Informational: it describes the limits of this report, not a defect in the app."
    )
}
