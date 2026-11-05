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

        // Two passes over the two maps, and no intermediate map copies. filterKeys() built a fresh
        // LinkedHashMap for each side and values.toList() copied again, so the old version walked
        // the findings five times and allocated two maps to produce two lists.
        val resolved = ArrayList<Finding>(before.size)
        for ((findingKey, finding) in before) {
            if (findingKey !in after) resolved.add(finding)
        }
        val added = ArrayList<Finding>(after.size)
        var unchanged = 0
        for ((findingKey, finding) in after) {
            if (findingKey in before) unchanged++ else added.add(finding)
        }

        return Delta(
            baseline = baseline,
            candidate = candidate,
            sizeDeltaBytes = candidate.apk.fileSizeBytes - baseline.apk.fileSizeBytes,
            scoreDelta = deltaOrNull(baseline.scoring.security, candidate.scoring.security),
            performanceDelta = deltaOrNull(baseline.scoring.performance, candidate.scoring.performance),
            findingsResolved = resolved,
            findingsAdded = added,
            findingsUnchanged = unchanged
        )
    }

    private fun deltaOrNull(before: Int?, after: Int?): Int? = if (before == null || after == null) null else after - before
}
