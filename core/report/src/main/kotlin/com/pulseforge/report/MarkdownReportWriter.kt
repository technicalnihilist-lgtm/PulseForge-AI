package com.pulseforge.report

import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.ScanReport

/** Human-readable Markdown export. Also the format used for the sample reports in the repo. */
object MarkdownReportWriter {

    fun write(report: ScanReport): String = buildString {
        val apk = report.apk
        val manifest = apk.manifest

        appendLine("# PulseForge AI - APK analysis report")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|---|---|")
        appendLine("| File | `${apk.fileName}` |")
        appendLine("| Size | ${ReportFormat.bytes(apk.fileSizeBytes)} |")
        appendLine("| SHA-256 | `${apk.sha256}` |")
        appendLine("| Package | ${manifest?.packageName ?: "_manifest unreadable_"} |")
        appendLine("| Version | ${manifest?.versionName ?: "?"} (${manifest?.versionCode ?: "?"}) |")
        appendLine("| minSdk / targetSdk | ${manifest?.minSdk ?: "?"} / ${manifest?.targetSdk ?: "?"} |")
        appendLine("| Report id | `${report.reportId}` |")
        appendLine("| Generated | ${ReportFormat.date(report.generatedAtEpochMs)} |")
        appendLine("| Engine | PulseForge ${report.engine.appVersion}, rules ${report.engine.ruleSetVersion} |")
        appendLine("| Mode | ${report.meta.analysisMode} |")
        appendLine("| Scan duration | ${ReportFormat.duration(report.meta.durationMs)} |")
        if (report.meta.isSampleData) {
            appendLine("| **Sample data** | **Generated from a synthetic test fixture, not a real app.** |")
        }
        appendLine()

        appendLine("## Summary")
        appendLine()
        val counts = report.counts
        appendLine("| Severity | Count |")
        appendLine("|---|---|")
        appendLine("| Critical | ${counts.critical} |")
        appendLine("| High | ${counts.high} |")
        appendLine("| Medium | ${counts.medium} |")
        appendLine("| Low | ${counts.low} |")
        appendLine("| Info | ${counts.info} |")
        appendLine()
        appendLine("Security score: **${report.scoring.security ?: "n/a"}/100**  ")
        appendLine("Performance score: **${report.scoring.performance ?: "n/a"}/100**")
        appendLine()
        appendLine("> ${report.scoring.disclaimer}")
        appendLine()

        appendLine("### How the security score was computed")
        appendLine()
        if (report.scoring.securityBreakdown.isEmpty()) {
            appendLine("No deductions: no finding reached Low severity or above.")
        } else {
            appendLine("| Deduction | Points | Rule |")
            appendLine("|---|---|---|")
            report.scoring.securityBreakdown.forEach {
                appendLine("| ${it.label.replace("|", "/")} | -${it.points} | ${it.explanation} |")
            }
        }
        appendLine()
        appendLine(
            "Performance deductions: " + if (report.scoring.performanceBreakdown.isEmpty()) "none" else
                report.scoring.performanceBreakdown.joinToString("; ") { "${it.label} -${it.points}" }
        )
        appendLine()

        appendLine("## Security findings")
        appendLine()
        if (report.findings.isEmpty()) {
            appendLine("No findings from the ${report.scoring.coverage.size} rules that ran.")
        }
        report.findings.forEachIndexed { index, finding ->
            appendLine("### ${index + 1}. ${finding.title}")
            appendLine()
            appendLine(
                "- **Severity:** ${finding.severity.name} | **Confidence:** ${finding.confidence.name} | " +
                    "**Evidence:** ${evidenceLabel(finding.evidenceType)}"
            )
            appendLine("- **Rule:** `${finding.ruleId}` | **Category:** ${finding.category.name}")
            finding.affectedComponent?.let { appendLine("- **Component:** `$it`") }
            if (finding.masvsControls.isNotEmpty() || finding.cweIds.isNotEmpty()) {
                appendLine("- **Mapped to:** ${(finding.masvsControls + finding.cweIds).joinToString(", ")}")
            }
            appendLine()
            appendLine(finding.summary)
            appendLine()
            appendLine(finding.technicalDetail)
            appendLine()
            if (finding.evidence.isNotEmpty()) {
                appendLine("**Evidence**")
                appendLine()
                appendLine("| What | Value | Source |")
                appendLine("|---|---|---|")
                finding.evidence.forEach { e ->
                    appendLine("| ${e.label.replace("|", "/")} | `${e.value.replace("|", "/")}` | ${e.source} |")
                }
                appendLine()
            }
            appendLine("**Remediation.** ${finding.remediation}")
            appendLine()
            finding.falsePositiveNote?.let {
                appendLine("**Could this be a false positive?** $it")
                appendLine()
            }
            if (finding.references.isNotEmpty()) {
                appendLine(
                    "**References:** " + finding.references.joinToString("; ") {
                        "${it.framework} ${it.id} - ${it.title} (${it.url})"
                    }
                )
                appendLine()
            }
        }

        appendLine("## Performance")
        appendLine()
        appendLine("### Measured (from the file)")
        appendLine()
        appendLine("| Metric | Value | Detail |")
        appendLine("|---|---|---|")
        report.performance.measured.forEach { appendLine("| ${it.label} | ${it.value} | ${it.detail ?: ""} |") }
        appendLine()
        if (report.performance.estimates.isNotEmpty()) {
            appendLine("### Estimated (with basis)")
            appendLine()
            appendLine("| Metric | Estimate | Basis | Confidence |")
            appendLine("|---|---|---|---|")
            report.performance.estimates.forEach {
                appendLine("| ${it.label} | ${it.estimate} | ${it.basis} | ${it.confidence.name} |")
            }
            appendLine()
        }
        appendLine("### Not measurable from an APK")
        appendLine()
        report.performance.unavailable.forEach {
            appendLine("- **${it.label}** - ${it.reason} _How to measure:_ ${it.howToMeasure}")
        }
        appendLine()
        appendLine(report.performance.runtimeProfilingNote)
        appendLine()

        if (report.performance.composition.isNotEmpty()) {
            appendLine("### Size composition")
            appendLine()
            appendLine("| Category | Bytes | Files |")
            appendLine("|---|---|---|")
            report.performance.composition.forEach {
                appendLine("| ${it.category} | ${ReportFormat.bytes(it.bytes)} | ${it.count} |")
            }
            appendLine()
        }

        if (report.performance.duplicates.isNotEmpty()) {
            appendLine("### Duplicate files")
            appendLine()
            report.performance.duplicates.forEach { dup ->
                appendLine(
                    "- ${ReportFormat.bytes(dup.wastedBytes)} wasted - `${dup.sha256.take(16)}...` in " +
                        dup.paths.joinToString(", ") { "`$it`" }
                )
            }
            appendLine()
        }

        if (report.performance.actions.isNotEmpty()) {
            appendLine("## Optimization plan")
            appendLine()
            report.performance.actions.forEachIndexed { index, action ->
                appendLine(
                    "**${index + 1}. ${action.title}** - impact ${action.impact.name.lowercase()}, " +
                        "effort ${action.effort.name.lowercase()}" +
                        (action.estimatedSavingBytes?.let { ", est. saving ${ReportFormat.bytes(it)}" } ?: "")
                )
                appendLine()
                appendLine(action.rationale)
                appendLine()
                action.steps.forEach { appendLine("- $it") }
                action.caveats?.let { appendLine(); appendLine("_Caveat: ${it}_") }
                appendLine()
            }
        }

        appendLine("## Privacy")
        appendLine()
        appendLine("Dangerous permissions requested: **${report.privacy.dangerousPermissionCount}**  ")
        appendLine("Exported components: **${report.privacy.exportedComponentCount}**")
        appendLine()
        if (report.privacy.permissionGroups.isNotEmpty()) {
            appendLine("| Group | Risk | Permissions | Why it matters |")
            appendLine("|---|---|---|---|")
            report.privacy.permissionGroups.forEach {
                appendLine("| ${it.group} | ${it.risk.name} | ${it.permissions.joinToString(", ")} | ${it.rationale} |")
            }
            appendLine()
        }
        if (report.privacy.trackerSignals.isNotEmpty()) {
            appendLine("Detected SDK signals: " + report.privacy.trackerSignals.joinToString(", ") { it.name })
            appendLine()
        }
        report.privacy.notes.forEach { appendLine("- $it") }
        appendLine()

        appendLine("## Manifest")
        appendLine()
        if (manifest != null) {
            appendLine("| Attribute | Value |")
            appendLine("|---|---|")
            appendLine("| debuggable | ${manifest.debuggable ?: "unset (false)"} |")
            appendLine("| allowBackup | ${manifest.allowBackup ?: "unset (true)"} |")
            appendLine("| usesCleartextTraffic | ${manifest.usesCleartextTraffic ?: "unset"} |")
            appendLine("| networkSecurityConfig | ${manifest.networkSecurityConfig ?: "none"} |")
            appendLine("| extractNativeLibs | ${manifest.extractNativeLibs ?: "unset"} |")
            appendLine("| applicationClass | ${manifest.applicationClass ?: "android.app.Application"} |")
            appendLine()
            appendLine("| Component | Type | Exported | Permission | Intent filters |")
            appendLine("|---|---|---|---|---|")
            manifest.components.forEach { c ->
                appendLine(
                    "| `${c.name}` | ${c.type.name} | " +
                        "${c.exported?.toString() ?: "implicit (${c.effectiveExported(manifest.targetSdk)})"} | " +
                        "${c.permission ?: "-"} | ${c.intentFilters.size} |"
                )
            }
            appendLine()
            appendLine("Requested permissions: " + manifest.requestedPermissions.joinToString(", ").ifBlank { "none" })
            appendLine()
        } else {
            appendLine("Manifest could not be parsed: ${report.apk.manifestParseError ?: "unknown error"}")
            appendLine()
        }

        appendLine("## Signing")
        appendLine()
        val signing = apk.signing
        val cert = signing?.certificate
        if (signing != null && cert != null) {
            appendLine("| Field | Value |")
            appendLine("|---|---|")
            appendLine("| Schemes | ${signing.schemeSummary} |")
            appendLine("| Verified | ${signing.verified} (${signing.verificationMessage ?: "-"}) |")
            appendLine("| Subject | ${cert.subject} |")
            appendLine("| Issuer | ${cert.issuer} |")
            appendLine("| SHA-256 | `${cert.sha256}` |")
            appendLine("| Key | ${cert.keyAlgorithm} ${cert.keySizeBits} bits |")
            appendLine("| Valid until | ${ReportFormat.date(cert.notAfterEpochMs)} |")
            appendLine()
        } else {
            appendLine("No certificate could be extracted.")
            appendLine()
        }

        appendLine("## Limitations")
        appendLine()
        report.meta.limitations.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## What was checked")
        appendLine()
        appendLine(report.scoring.coverage.joinToString(", "))
        appendLine()
        appendLine("---")
        appendLine()
        appendLine(
            "Generated by PulseForge AI ${report.engine.appVersion}. All analysis ran locally on the device; " +
                "no APK, source file or report was uploaded."
        )
    }

    private fun evidenceLabel(type: EvidenceType): String = when (type) {
        EvidenceType.DIRECT_OBSERVATION -> "direct observation"
        EvidenceType.PATTERN_MATCH -> "pattern match (heuristic)"
        EvidenceType.INFERENCE -> "inference from several observations"
    }

    fun confidenceLabel(confidence: Confidence): String = confidence.name
}
