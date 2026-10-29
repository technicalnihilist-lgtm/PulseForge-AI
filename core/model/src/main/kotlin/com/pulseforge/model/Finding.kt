package com.pulseforge.model

import kotlinx.serialization.Serializable

/** A single piece of proof attached to a finding. Values are already redacted. */
@Serializable
data class EvidenceItem(val label: String, val value: String, val source: String)

@Serializable
data class Reference(val framework: String, val id: String, val title: String, val url: String)

/**
 * A reported observation about the APK. [evidenceType] and [confidence] are what the UI uses to
 * keep confirmed facts separate from heuristics.
 */
@Serializable
data class Finding(
    val id: String,
    val ruleId: String,
    val title: String,
    val category: FindingCategory,
    val severity: Severity,
    val confidence: Confidence,
    val evidenceType: EvidenceType,
    val summary: String,
    val technicalDetail: String,
    val affectedComponent: String? = null,
    val evidence: List<EvidenceItem> = emptyList(),
    val remediation: String,
    val references: List<Reference> = emptyList(),
    val falsePositiveNote: String? = null,
    val masvsControls: List<String> = emptyList(),
    val cweIds: List<String> = emptyList()
) {
    val isConfirmedObservation: Boolean get() = evidenceType == EvidenceType.DIRECT_OBSERVATION
}

/** Aggregated counts used by the dashboard. */
@Serializable
data class FindingCounts(
    val critical: Int = 0,
    val high: Int = 0,
    val medium: Int = 0,
    val low: Int = 0,
    val info: Int = 0
) {
    val total: Int get() = critical + high + medium + low + info

    companion object {
        fun of(findings: List<Finding>): FindingCounts = FindingCounts(
            critical = findings.count { it.severity == Severity.CRITICAL },
            high = findings.count { it.severity == Severity.HIGH },
            medium = findings.count { it.severity == Severity.MEDIUM },
            low = findings.count { it.severity == Severity.LOW },
            info = findings.count { it.severity == Severity.INFO }
        )
    }
}
