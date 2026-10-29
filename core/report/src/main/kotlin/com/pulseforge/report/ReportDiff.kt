package com.pulseforge.report

import com.pulseforge.model.Finding
import com.pulseforge.model.ScanReport

/**
 * Compares two reports of the same app (before/after). Used by the comparison screen and by the
 * "did my optimisation help?" workflow.
 */
object ReportDiff {

    data class Delta(
        val baseline: ScanReport,
        val candidate: ScanReport,
        val sizeDeltaBytes: Long,
        val scoreDelta: Int?,
        val performanceDelta: Int?,
        val findingsResolved: List<Finding>,
        val findingsAdded: List<Finding>,
        val findingsUnchanged: Int
    ) {
        val sizeDeltaPercent: Double
            get() = if (baseline.apk.fileSizeBytes <= 0) 0.0 else sizeDeltaBytes * 100.0 / baseline.apk.fileSizeBytes
    }

    private fun key(finding: Finding): String = "${finding.ruleId}|${finding.affectedComponent ?: ""}"

    fun compare(baseline: ScanReport, candidate: ScanReport): Delta {
        val before = baseline.findings.associateBy { key(it) }
        val after = candidate.findings.associateBy { key(it) }

        return Delta(
            baseline = baseline,
            candidate = candidate,
            sizeDeltaBytes = candidate.apk.fileSizeBytes - baseline.apk.fileSizeBytes,
            scoreDelta = deltaOrNull(baseline.scoring.security, candidate.scoring.security),
            performanceDelta = deltaOrNull(baseline.scoring.performance, candidate.scoring.performance),
            findingsResolved = before.filterKeys { it !in after }.values.toList(),
            findingsAdded = after.filterKeys { it !in before }.values.toList(),
            findingsUnchanged = before.keys.count { it in after }
        )
    }

    private fun deltaOrNull(before: Int?, after: Int?): Int? = if (before == null || after == null) null else after - before
}
