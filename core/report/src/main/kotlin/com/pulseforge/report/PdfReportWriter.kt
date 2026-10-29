package com.pulseforge.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.ScanReport
import com.pulseforge.model.Severity
import java.io.OutputStream

/**
 * Renders a report as a paginated A4 PDF with the platform PdfDocument, so there is no third-party
 * PDF library to ship or license. This class touches android.graphics and is exercised on device
 * and under Robolectric; JSON and Markdown cover the same content for plain JVM tests.
 */
class PdfReportWriter {

    fun write(report: ScanReport, output: OutputStream) {
        val document = PdfDocument()
        val writer = PageWriter(document)
        try {
            writer.newPage()
            renderHeader(writer, report)
            renderSummary(writer, report)
            renderScoreExplanation(writer, report)
            renderFindings(writer, report)
            renderPerformance(writer, report)
            renderActions(writer, report)
            renderPrivacy(writer, report)
            renderManifest(writer, report)
            renderSigning(writer, report)
            renderLimitations(writer, report)
            writer.finish()
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun renderHeader(w: PageWriter, report: ScanReport) {
        w.title("PulseForge AI")
        w.subtitle("Universal APK Security Analyzer & Optimizer - static analysis report")
        w.gap(6f)
        w.keyValue("File", report.apk.fileName)
        w.keyValue("Size", ReportFormat.bytes(report.apk.fileSizeBytes))
        w.keyValue("SHA-256", report.apk.sha256)
        w.keyValue("Package", report.apk.manifest?.packageName ?: "manifest unreadable")
        report.apk.manifest?.let { manifest ->
            w.keyValue("Version", "${manifest.versionName ?: "?"} (${manifest.versionCode ?: "?"})")
            w.keyValue("SDK", "min ${manifest.minSdk ?: "?"} / target ${manifest.targetSdk ?: "?"}")
        }
        w.keyValue("Generated", ReportFormat.date(report.generatedAtEpochMs))
        w.keyValue("Engine", "${report.engine.appVersion} / rules ${report.engine.ruleSetVersion}")
        w.keyValue("Mode", report.meta.analysisMode)
        if (report.meta.isSampleData) {
            w.gap(4f)
            w.callout("SAMPLE DATA - generated from a synthetic test fixture, not a real application.")
        }
        w.gap(10f)
    }

    private fun renderSummary(w: PageWriter, report: ScanReport) {
        w.heading("Summary")
        val counts = report.counts
        w.body(
            "Security score ${report.scoring.security ?: "n/a"}/100 / " +
                "Performance score ${report.scoring.performance ?: "n/a"}/100"
        )
        w.gap(4f)
        w.body(
            "${counts.total} findings: ${counts.critical} critical, ${counts.high} high, " +
                "${counts.medium} medium, ${counts.low} low, ${counts.info} informational."
        )
        w.gap(6f)
        w.body(report.scoring.disclaimer, italic = true)
        w.gap(8f)
    }

    private fun renderScoreExplanation(w: PageWriter, report: ScanReport) {
        w.heading("How the score was computed")
        if (report.scoring.securityBreakdown.isEmpty()) {
            w.body("No deductions: nothing reached low severity or above.")
        } else {
            report.scoring.securityBreakdown.forEach { component ->
                w.bullet("${component.label}  -${component.points}  (${component.explanation})")
            }
        }
        if (report.scoring.performanceBreakdown.isNotEmpty()) {
            w.gap(4f)
            w.body("Performance deductions:")
            report.scoring.performanceBreakdown.forEach { component ->
                w.bullet("${component.label}  -${component.points}  (${component.explanation})")
            }
        }
        w.gap(8f)
    }

    private fun renderFindings(w: PageWriter, report: ScanReport) {
        w.heading("Security findings")
        if (report.findings.isEmpty()) {
            w.body("No findings from the ${report.scoring.coverage.size} rules that ran.")
            w.gap(8f)
            return
        }
        report.findings.forEachIndexed { index, finding ->
            w.ensureSpace(70f)
            w.findingHeader("${index + 1}. ${finding.title}", finding.severity)
            w.meta(
                "Rule ${finding.ruleId} / confidence ${finding.confidence.name} / " + evidenceLabel(finding) +
                    (finding.affectedComponent?.let { " / $it" } ?: "")
            )
            w.gap(3f)
            w.body(finding.summary)
            w.gap(2f)
            w.body(finding.technicalDetail)
            if (finding.evidence.isNotEmpty()) {
                w.gap(3f)
                w.body("Evidence", bold = true)
                finding.evidence.forEach { evidence ->
                    w.bullet("${evidence.label}: ${evidence.value}  [${evidence.source}]", mono = true)
                }
            }
            w.gap(2f)
            w.body("Remediation: ${finding.remediation}")
            finding.falsePositiveNote?.let { w.body("False-positive check: $it", italic = true) }
            if (finding.masvsControls.isNotEmpty() || finding.cweIds.isNotEmpty()) {
                w.meta("Mapped to: " + (finding.masvsControls + finding.cweIds).joinToString(", "))
            }
            w.gap(8f)
        }
    }

    private fun renderPerformance(w: PageWriter, report: ScanReport) {
        w.heading("Performance")
        w.body("Measured from the file", bold = true)
        report.performance.measured.forEach {
            w.bullet("${it.label}: ${it.value}${it.detail?.let { d -> " - $d" } ?: ""}")
        }
        if (report.performance.estimates.isNotEmpty()) {
            w.gap(4f)
            w.body("Estimated (basis stated)", bold = true)
            report.performance.estimates.forEach { w.bullet("${it.label}: ${it.estimate} - ${it.basis}") }
        }
        w.gap(4f)
        w.body("Not measurable from an APK", bold = true)
        report.performance.unavailable.forEach { w.bullet("${it.label}: ${it.reason}") }
        w.gap(4f)
        w.body(report.performance.runtimeProfilingNote, italic = true)
        w.gap(8f)
    }

    private fun renderActions(w: PageWriter, report: ScanReport) {
        if (report.performance.actions.isEmpty()) return
        w.heading("Optimization plan")
        report.performance.actions.forEachIndexed { index, action ->
            w.ensureSpace(50f)
            w.body("${index + 1}. ${action.title}", bold = true)
            w.meta(
                "Impact ${action.impact.name.lowercase()} / effort ${action.effort.name.lowercase()}" +
                    (action.estimatedSavingBytes?.let { " / est. saving ${ReportFormat.bytes(it)}" } ?: "")
            )
            w.body(action.rationale)
            action.steps.take(5).forEach { w.bullet(it) }
            action.caveats?.let { w.body("Caveat: $it", italic = true) }
            w.gap(6f)
        }
        w.gap(4f)
    }

    private fun renderPrivacy(w: PageWriter, report: ScanReport) {
        w.heading("Privacy")
        w.body(
            "Dangerous permissions: ${report.privacy.dangerousPermissionCount} / " +
                "exported components: ${report.privacy.exportedComponentCount}"
        )
        report.privacy.permissionGroups.forEach { group ->
            w.bullet("${group.group} (${group.risk.name}): ${group.permissions.joinToString(", ")}")
        }
        if (report.privacy.trackerSignals.isNotEmpty()) {
            w.bullet("SDK signals: " + report.privacy.trackerSignals.joinToString(", ") { it.name })
        }
        report.privacy.notes.forEach { w.body(it, italic = true) }
        w.gap(8f)
    }

    private fun renderManifest(w: PageWriter, report: ScanReport) {
        w.heading("Manifest")
        val manifest = report.apk.manifest
        if (manifest == null) {
            w.body("Manifest could not be parsed: ${report.apk.manifestParseError ?: "unknown"}")
            w.gap(8f)
            return
        }
        w.keyValue("debuggable", manifest.debuggable?.toString() ?: "unset (false)")
        w.keyValue("allowBackup", manifest.allowBackup?.toString() ?: "unset (true)")
        w.keyValue("usesCleartextTraffic", manifest.usesCleartextTraffic?.toString() ?: "unset")
        w.keyValue("networkSecurityConfig", manifest.networkSecurityConfig ?: "none")
        w.gap(4f)
        manifest.components.take(30).forEach { component ->
            w.bullet(
                "${component.type.name.lowercase()} ${component.name} - " +
                    "exported=${component.exported?.toString() ?: "implicit(${component.effectiveExported(manifest.targetSdk)})"}" +
                    (component.permission?.let { ", permission=$it" } ?: "")
            )
        }
        w.gap(4f)
        w.body("Permissions: " + manifest.requestedPermissions.joinToString(", ").ifBlank { "none" })
        w.gap(8f)
    }

    private fun renderSigning(w: PageWriter, report: ScanReport) {
        w.heading("Signing")
        val signing = report.apk.signing
        val cert = signing?.certificate
        if (signing == null || cert == null) {
            w.body("No signing certificate could be extracted.")
            w.gap(8f)
            return
        }
        w.keyValue("Schemes", signing.schemeSummary)
        w.keyValue("Verified", "${signing.verified} (${signing.verificationMessage ?: "-"})")
        w.keyValue("Subject", cert.subject)
        w.keyValue("Issuer", cert.issuer)
        w.keyValue("SHA-256", cert.sha256)
        w.keyValue("Key", "${cert.keyAlgorithm} ${cert.keySizeBits} bits")
        w.keyValue("Valid until", ReportFormat.date(cert.notAfterEpochMs))
        w.gap(8f)
    }

    private fun renderLimitations(w: PageWriter, report: ScanReport) {
        w.heading("Limitations")
        report.meta.limitations.forEach { w.bullet(it) }
        w.gap(4f)
        w.body("Checks performed: " + report.scoring.coverage.joinToString(", "), italic = true)
    }

    private fun evidenceLabel(finding: Finding): String = when (finding.evidenceType) {
        EvidenceType.DIRECT_OBSERVATION -> "observed in the APK"
        EvidenceType.PATTERN_MATCH -> "heuristic pattern match"
        EvidenceType.INFERENCE -> "inferred"
    }

    private class PageWriter(private val document: PdfDocument) {

        private val pageWidth = 595
        private val pageHeight = 842
        private val margin = 42f
        private val contentWidth = pageWidth - margin * 2
        private val footerReserve = 26f

        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = margin
        private var pageNumber = 0

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 30, 34)
            textSize = 9.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val bodyBoldPaint = Paint(bodyPaint).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        private val italicPaint = Paint(bodyPaint).apply { typeface = Typeface.create("sans-serif", Typeface.ITALIC) }
        private val monoPaint = Paint(bodyPaint).apply {
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            textSize = 8f
            color = Color.rgb(60, 64, 70)
        }
        private val titlePaint = Paint(bodyPaint).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            color = Color.rgb(20, 30, 70)
        }
        private val subtitlePaint = Paint(bodyPaint).apply {
            textSize = 10f
            color = Color.rgb(90, 96, 104)
        }
        private val headingPaint = Paint(bodyPaint).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            color = Color.rgb(20, 30, 70)
        }
        private val metaPaint = Paint(bodyPaint).apply {
            textSize = 8f
            color = Color.rgb(105, 110, 118)
        }
        private val footerPaint = Paint(bodyPaint).apply {
            textSize = 7.5f
            color = Color.rgb(140, 144, 150)
        }
        private val rulePaint = Paint().apply { color = Color.rgb(214, 218, 226); strokeWidth = 0.7f }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun newPage() {
            finishPage()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            val newPage = document.startPage(info)
            page = newPage
            canvas = newPage.canvas
            y = margin
        }

        fun finishPage() {
            val current = page ?: return
            val c = current.canvas
            c.drawLine(margin, pageHeight - margin + 6, pageWidth - margin, pageHeight - margin + 6, rulePaint)
            c.drawText("PulseForge AI / static analysis / page $pageNumber", margin, pageHeight - margin + 16, footerPaint)
            document.finishPage(current)
            page = null
            canvas = null
        }

        fun finish() = finishPage()

        fun ensureSpace(required: Float) {
            if (y + required > pageHeight - margin - footerReserve) newPage()
        }

        fun gap(amount: Float) { y += amount }

        fun title(text: String) {
            ensureSpace(30f)
            canvas?.drawText(text, margin, y + 16, titlePaint)
            y += 26f
        }

        fun subtitle(text: String) {
            ensureSpace(16f)
            canvas?.drawText(text, margin, y + 8, subtitlePaint)
            y += 14f
        }

        fun heading(text: String) {
            ensureSpace(34f)
            y += 6f
            canvas?.drawText(text, margin, y + 10, headingPaint)
            y += 16f
            canvas?.drawLine(margin, y, pageWidth - margin, y, rulePaint)
            y += 8f
        }

        fun body(text: String, bold: Boolean = false, italic: Boolean = false) {
            val paint = when {
                bold -> bodyBoldPaint
                italic -> italicPaint
                else -> bodyPaint
            }
            writeWrapped(text, paint, 0f)
            y += 3f
        }

        fun meta(text: String) = writeWrapped(text, metaPaint, 0f)

        fun bullet(text: String, mono: Boolean = false) {
            val paint = if (mono) monoPaint else bodyPaint
            ensureSpace(14f)
            canvas?.drawText("-", margin + 2, y + paint.textSize, paint)
            writeWrapped(text, paint, 12f)
            y += 1.5f
        }

        fun keyValue(label: String, value: String) {
            val labelWidth = 110f
            ensureSpace(14f)
            canvas?.drawText(label, margin, y + bodyBoldPaint.textSize, bodyBoldPaint)
            writeWrapped(value, bodyPaint, labelWidth, startY = y)
            y = maxOf(y + 12f, y + bodyPaint.textSize + 3f)
        }

        fun callout(text: String) {
            ensureSpace(22f)
            val rect = RectF(margin, y, pageWidth - margin, y + 18f)
            chipPaint.color = Color.rgb(255, 243, 205)
            canvas?.drawRoundRect(rect, 3f, 3f, chipPaint)
            canvas?.drawText(text, margin + 6f, y + 12f, bodyBoldPaint)
            y += 22f
        }

        fun findingHeader(text: String, severity: Severity) {
            val chipWidth = 62f
            ensureSpace(24f)
            val color = when (severity) {
                Severity.CRITICAL -> Color.rgb(176, 0, 32)
                Severity.HIGH -> Color.rgb(200, 60, 20)
                Severity.MEDIUM -> Color.rgb(176, 120, 0)
                Severity.LOW -> Color.rgb(40, 100, 160)
                Severity.INFO -> Color.rgb(90, 96, 104)
            }
            chipPaint.color = color
            canvas?.drawRoundRect(RectF(margin, y + 1f, margin + chipWidth, y + 13f), 3f, 3f, chipPaint)
            val chipTextPaint = Paint(bodyPaint).apply {
                this.color = Color.WHITE
                textSize = 7.5f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            canvas?.drawText(severity.name, margin + 5f, y + 10f, chipTextPaint)
            writeWrapped(text, bodyBoldPaint, chipWidth + 6f, startY = y + 1f, maxWidth = contentWidth - chipWidth - 6f)
            y += maxOf(15f, bodyBoldPaint.textSize + 6f)
        }

        private fun writeWrapped(
            text: String,
            paint: Paint,
            indent: Float,
            startY: Float = y,
            maxWidth: Float = contentWidth - indent
        ) {
            val c = canvas ?: return
            val words = text.split(' ')
            var line = StringBuilder()
            var lineY = startY + paint.textSize

            fun flush() {
                if (line.isEmpty()) return
                c.drawText(line.toString(), margin + indent, lineY, paint)
                line = StringBuilder()
                lineY += paint.textSize + 2.6f
            }

            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    flush()
                    if (lineY > pageHeight - margin - footerReserve) {
                        newPage()
                        lineY = margin + paint.textSize
                    }
                    line.append(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            flush()
            y = lineY - paint.textSize + 1.5f
        }
    }
}
