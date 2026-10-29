package com.pulseforge.report

import com.pulseforge.model.Confidence
import com.pulseforge.model.Finding
import com.pulseforge.model.PerformanceAnalysis
import com.pulseforge.model.ScoreCard
import com.pulseforge.model.ScoreComponent
import com.pulseforge.model.Severity

/**
 * Documented, reproducible scoring.
 *
 * Security: start at 100 and subtract a fixed weight per finding, scaled by how much the analyser
 * trusts it. Every subtraction is listed, so the number can be recomputed by hand.
 *
 * Performance: start at 100 and subtract for measurable size and resource problems using fixed bands.
 *
 * Neither number is a verdict; both are triage aids and the report says so.
 */
object ScoreCalculator {

    const val SECURITY_DISCLAIMER =
        "The security score is a triage aid computed from the findings listed in this report. " +
            "It is not a certification, and a high score does not mean the app is secure: static " +
            "analysis cannot see runtime behaviour, server-side controls or code hidden behind " +
            "packers and heavy reflection. The score starts at 100, each finding deducts a fixed " +
            "weighted amount (listed below), and the result is clamped to the range 0-100."

    private const val CRITICAL_WEIGHT = 25
    private const val HIGH_WEIGHT = 12
    private const val MEDIUM_WEIGHT = 5
    private const val LOW_WEIGHT = 2

    fun securityBreakdown(findings: List<Finding>): List<ScoreComponent> = findings.mapNotNull { finding ->
        val base = when (finding.severity) {
            Severity.CRITICAL -> CRITICAL_WEIGHT
            Severity.HIGH -> HIGH_WEIGHT
            Severity.MEDIUM -> MEDIUM_WEIGHT
            Severity.LOW -> LOW_WEIGHT
            Severity.INFO -> 0
        }
        if (base == 0) return@mapNotNull null
        val multiplier = when (finding.confidence) {
            Confidence.CONFIRMED, Confidence.HIGH -> 1.0
            Confidence.MEDIUM -> 0.7
            Confidence.LOW -> 0.4
        }
        val points = kotlin.math.round(base * multiplier).toInt()
        if (points == 0) return@mapNotNull null
        ScoreComponent(
            label = finding.title,
            points = points,
            explanation = "${finding.severity.name} finding at ${finding.confidence.name} confidence " +
                "($base x ${"%.1f".format(multiplier)} = $points)"
        )
    }

    /** Raw total of every deduction, before clamping. Exposed so a reader can audit the maths. */
    fun totalSecurityDeductions(findings: List<Finding>): Int = securityBreakdown(findings).sumOf { it.points }

    fun securityScore(findings: List<Finding>): Int = (100 - totalSecurityDeductions(findings)).coerceIn(0, 100)

    fun performanceBreakdown(performance: PerformanceAnalysis, apkSizeBytes: Long): List<ScoreComponent> {
        val components = mutableListOf<ScoreComponent>()

        val duplicateBytes = performance.duplicates.sumOf { it.wastedBytes }
        if (duplicateBytes > 0) {
            val mb = duplicateBytes / (1024.0 * 1024)
            val points = (mb * 2).toInt().coerceAtMost(20).coerceAtLeast(1)
            components.add(
                ScoreComponent(
                    "Duplicate resources",
                    points,
                    "%.1f MB of byte-identical files; 2 points per MB, capped at 20".format(mb)
                )
            )
        }

        val oversizedBytes = performance.oversizedImages.sumOf { it.bytes }
        if (oversizedBytes > 0) {
            val mb = oversizedBytes / (1024.0 * 1024)
            val points = (mb * 1.5).toInt().coerceAtMost(25).coerceAtLeast(1)
            components.add(
                ScoreComponent(
                    "Oversized images",
                    points,
                    "%.1f MB in %d oversized images; 1.5 points per MB, capped at 25".format(mb, performance.oversizedImages.size)
                )
            )
        }

        if (performance.actions.any { it.id == "split-abi" }) {
            components.add(ScoreComponent("Multiple ABIs in one APK", 10, "Native code for several ABIs ships in every download"))
        }

        if (performance.measured.any { it.label == "DEX files" && it.detail == "Multidex build" }) {
            components.add(ScoreComponent("Multidex build", 5, "Multiple dex files add first-run verification cost"))
        }

        val sizeMb = apkSizeBytes / (1024.0 * 1024)
        when {
            sizeMb >= 150 -> components.add(ScoreComponent("Very large APK", 15, "%.0f MB download".format(sizeMb)))
            sizeMb >= 60 -> components.add(ScoreComponent("Large APK", 8, "%.0f MB download".format(sizeMb)))
            sizeMb >= 25 -> components.add(ScoreComponent("Above-average APK size", 4, "%.0f MB download".format(sizeMb)))
        }

        return components
    }

    fun performanceScore(performance: PerformanceAnalysis, apkSizeBytes: Long): Int =
        (100 - performanceBreakdown(performance, apkSizeBytes).sumOf { it.points }).coerceIn(0, 100)

    fun build(
        findings: List<Finding>,
        performance: PerformanceAnalysis,
        apkSizeBytes: Long,
        rulesetVersion: String,
        coverage: List<String>
    ): ScoreCard = ScoreCard(
        security = securityScore(findings),
        performance = performanceScore(performance, apkSizeBytes),
        securityBreakdown = securityBreakdown(findings).sortedByDescending { it.points },
        performanceBreakdown = performanceBreakdown(performance, apkSizeBytes).sortedByDescending { it.points },
        rulesetVersion = rulesetVersion,
        disclaimer = SECURITY_DISCLAIMER,
        coverage = coverage
    )
}
