package com.pulseforge.model

import kotlinx.serialization.Serializable

/** Impact of a finding, ordered by rank. */
@Serializable
enum class Severity(val rank: Int) {
    CRITICAL(5), HIGH(4), MEDIUM(3), LOW(2), INFO(1)
}

/**
 * How much the analyser trusts a finding.
 * CONFIRMED - read directly out of the artifact.
 * HIGH      - derived from a direct observation with an unambiguous rule.
 * MEDIUM    - bytecode/string pattern match that can be reached in benign ways.
 * LOW       - weak signal, a lead for manual review.
 */
@Serializable
enum class Confidence { CONFIRMED, HIGH, MEDIUM, LOW }

/** Provenance of the evidence, kept separate from [Confidence]. */
@Serializable
enum class EvidenceType { DIRECT_OBSERVATION, PATTERN_MATCH, INFERENCE }

@Serializable
enum class FindingCategory { SECURITY, PRIVACY, PERFORMANCE, COMPATIBILITY, QUALITY }

@Serializable
enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

@Serializable
enum class Impact { HIGH, MEDIUM, LOW }

@Serializable
enum class Effort { LOW, MEDIUM, HIGH }

/** Ordered scan stages; the UI renders progress against this list. */
@Serializable
enum class ScanStage(val label: String) {
    QUEUED("Queued"),
    VALIDATING("Validating APK"),
    READING_ZIP("Reading archive"),
    PARSING_MANIFEST("Parsing manifest"),
    PARSING_DEX("Parsing bytecode"),
    ANALYSING_RESOURCES("Analysing resources"),
    VERIFYING_SIGNATURE("Verifying signature"),
    RUNNING_SECURITY_RULES("Running security rules"),
    RUNNING_PERFORMANCE_RULES("Running performance rules"),
    SCORING("Scoring"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

@Serializable
enum class ScanStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class ScanProgress(
    val stage: ScanStage,
    val stageIndex: Int,
    val stageCount: Int,
    val fraction: Float,
    val message: String
)

/** Structured error codes; the UI maps these to human guidance. */
@Serializable
enum class ScanErrorCode {
    FILE_NOT_FOUND,
    NOT_AN_APK,
    UNREADABLE_ARCHIVE,
    CORRUPT_MANIFEST,
    TOO_LARGE,
    OUT_OF_MEMORY,
    STORAGE_FULL,
    CANCELLED,
    INTERNAL_ERROR
}

@Serializable
data class ScanError(
    val code: ScanErrorCode,
    val message: String,
    val recoverable: Boolean,
    val hint: String
)
