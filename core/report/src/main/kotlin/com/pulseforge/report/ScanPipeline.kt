package com.pulseforge.report

import com.pulseforge.apk.ApkAnalyzer
import com.pulseforge.apk.ApkException
import com.pulseforge.apk.AnalysisConfig
import com.pulseforge.model.EngineInfo
import com.pulseforge.model.ScanMeta
import com.pulseforge.model.ScanReport
import com.pulseforge.model.ScanStage
import com.pulseforge.performance.PerformanceAnalyzer
import com.pulseforge.security.PrivacyAnalyzer
import com.pulseforge.security.RuleCatalog
import com.pulseforge.security.SecurityEngine
import java.io.File
import java.util.UUID

/**
 * The single entry point that turns an APK file into a report.
 *
 * Used by the WorkManager job on device and directly by unit tests, so both paths exercise exactly
 * the same analysis code.
 */
class ScanPipeline(
    private val analyzer: ApkAnalyzer = ApkAnalyzer(AnalysisConfig()),
    private val securityEngine: SecurityEngine = SecurityEngine(),
    private val appVersion: String = "1.0.0",
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(
        file: File,
        displayName: String,
        onProgress: suspend (ScanStage, Float, String) -> Unit = { _, _, _ -> }
    ): ScanReport {
        val startedAt = clock()
        val stages = mutableListOf<String>()

        // Analysis occupies the first 80% of the progress bar.
        val input = analyzer.analyze(file, displayName) { stage, fraction, message ->
            stages.add(stage.name)
            onProgress(stage, (fraction * 0.8f).coerceIn(0f, 0.8f), message)
        }

        onProgress(ScanStage.RUNNING_SECURITY_RULES, 0.85f, "Running ${RuleCatalog.rules.size} security rules")
        val findings = securityEngine.evaluate(input)

        onProgress(ScanStage.RUNNING_PERFORMANCE_RULES, 0.92f, "Analysing size and resources")
        val performance = PerformanceAnalyzer.analyse(input)
        val privacy = PrivacyAnalyzer.analyse(input)

        onProgress(ScanStage.SCORING, 0.97f, "Scoring")
        val scoring = ScoreCalculator.build(
            findings = findings,
            performance = performance,
            apkSizeBytes = input.facts.fileSizeBytes,
            rulesetVersion = RuleCatalog.VERSION,
            coverage = RuleCatalog.ids
        )

        val finishedAt = clock()
        val report = ScanReport(
            reportId = UUID.randomUUID().toString(),
            generatedAtEpochMs = finishedAt,
            engine = EngineInfo(
                appVersion = appVersion,
                ruleSetVersion = RuleCatalog.VERSION,
                analyzers = listOf(
                    "APK ZIP reader",
                    "Binary XML (AXML) parser",
                    "DEX table parser",
                    "resources.arsc string pool",
                    "APK Signing Block reader",
                    "apksig verifier",
                    "Security rule set ${RuleCatalog.VERSION}",
                    "Performance analyser",
                    "Privacy analyser"
                )
            ),
            apk = input.facts,
            findings = findings,
            performance = performance,
            privacy = privacy,
            scoring = scoring,
            meta = ScanMeta(
                startedAtEpochMs = startedAt,
                durationMs = finishedAt - startedAt,
                analysisMode = "Static analysis, on device, no execution",
                stagesCompleted = stages.distinct(),
                limitations = LIMITATIONS,
                isSampleData = false
            )
        )
        onProgress(ScanStage.COMPLETED, 1f, "Report ready")
        // Final redaction pass: no rule may leak a credential into a report, even by accident.
        return ReportRedactor.sanitize(report)
    }

    companion object {
        val LIMITATIONS: List<String> = listOf(
            "Static analysis only: the APK is never installed, loaded into a class loader or executed.",
            "A finding describes what the code can do, not what it does at runtime.",
            "Obfuscated, packed or dynamically loaded code is partially or fully invisible to this analysis.",
            "Dependency versions are not recoverable from an APK, so no vulnerability claim is made about bundled SDKs.",
            "Runtime behaviour (startup time, memory, FPS, battery, network traffic) cannot be measured from a file.",
            "A clean report does not prove the app is secure; it proves these checks found nothing."
        )
    }
}

/** Maps an [ApkException] to a stable, user-facing error description. */
fun ApkException.toUserMessage(): String = message ?: "The APK could not be analysed"
