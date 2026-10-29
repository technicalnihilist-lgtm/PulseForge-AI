package com.pulseforge.model

import kotlinx.serialization.Serializable

/** A number measured from the file, never guessed. */
@Serializable
data class MeasuredMetric(val label: String, val value: String, val detail: String? = null)

/** A number derived from measurements by an explicit, documented rule. */
@Serializable
data class EstimatedMetric(
    val label: String,
    val estimate: String,
    val basis: String,
    val confidence: Confidence
)

/** Something a static APK cannot tell you, and how a human could measure it. */
@Serializable
data class UnavailableMetric(val label: String, val reason: String, val howToMeasure: String)

@Serializable
data class OptimizationAction(
    val id: String,
    val title: String,
    val impact: Impact,
    val effort: Effort,
    val rationale: String,
    val steps: List<String>,
    val estimatedSavingBytes: Long? = null,
    val applicableWhen: String,
    val caveats: String? = null,
    /** False when the APK is third-party and cannot be rebuilt by the user. */
    val requiresSourceAccess: Boolean = true
)

@Serializable
data class PerformanceAnalysis(
    val measured: List<MeasuredMetric> = emptyList(),
    val estimates: List<EstimatedMetric> = emptyList(),
    val unavailable: List<UnavailableMetric> = emptyList(),
    val composition: List<CategorySize> = emptyList(),
    val duplicates: List<DuplicateGroup> = emptyList(),
    val oversizedImages: List<ImageAsset> = emptyList(),
    val actions: List<OptimizationAction> = emptyList(),
    val runtimeProfilingNote: String = ""
)

@Serializable
data class PermissionGroupSummary(
    val group: String,
    val permissions: List<String>,
    val risk: Severity,
    val rationale: String
)

@Serializable
data class TrackerSignal(val name: String, val evidence: String, val confidence: Confidence)

@Serializable
data class PrivacyAnalysis(
    val permissionGroups: List<PermissionGroupSummary> = emptyList(),
    val trackerSignals: List<TrackerSignal> = emptyList(),
    val notes: List<String> = emptyList(),
    val dangerousPermissionCount: Int = 0,
    val exportedComponentCount: Int = 0
)

@Serializable
data class ScoreComponent(val label: String, val points: Int, val explanation: String)

/**
 * Transparent, rule-based scores. Every deducted point is listed so the number can be recomputed
 * by hand. A score is a triage aid, not a security verdict.
 */
@Serializable
data class ScoreCard(
    val security: Int? = null,
    val performance: Int? = null,
    val securityBreakdown: List<ScoreComponent> = emptyList(),
    val performanceBreakdown: List<ScoreComponent> = emptyList(),
    val rulesetVersion: String = "",
    val disclaimer: String = "",
    val coverage: List<String> = emptyList()
)

@Serializable
data class EngineInfo(
    val appVersion: String,
    val ruleSetVersion: String,
    val analyzers: List<String> = emptyList()
)

@Serializable
data class ScanMeta(
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val analysisMode: String,
    val stagesCompleted: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val isSampleData: Boolean = false
)

/** A concrete, reviewable change proposed for source the user supplied. */
@Serializable
data class SourceSuggestion(
    val id: String,
    val title: String,
    val filePath: String,
    val line: Int? = null,
    val problem: String,
    val suggestion: String,
    val currentSnippet: String,
    val proposedSnippet: String,
    val testSnippet: String? = null,
    val confidence: Confidence = Confidence.MEDIUM
)

@Serializable
data class SourceReview(
    val filesAnalysed: List<String> = emptyList(),
    val suggestions: List<SourceSuggestion> = emptyList(),
    val notes: List<String> = emptyList()
)

@Serializable
data class ScanReport(
    val schemaVersion: Int = 1,
    val reportId: String,
    val generatedAtEpochMs: Long,
    val engine: EngineInfo,
    val apk: ApkFacts,
    val findings: List<Finding> = emptyList(),
    val performance: PerformanceAnalysis = PerformanceAnalysis(),
    val privacy: PrivacyAnalysis = PrivacyAnalysis(),
    val scoring: ScoreCard = ScoreCard(),
    val meta: ScanMeta
) {
    val counts: FindingCounts get() = FindingCounts.of(findings)
}
