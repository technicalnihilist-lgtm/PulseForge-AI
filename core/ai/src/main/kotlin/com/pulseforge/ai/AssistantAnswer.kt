package com.pulseforge.ai

enum class AssistantMode {
    /** Rule-based, grounded in the report. Always available, fully offline. */
    DETERMINISTIC,

    /** A bundled on-device model produced the prose. Never claimed unless it really ran. */
    LOCAL_MODEL
}

/**
 * An answer that keeps facts, hypotheses and unknowns separate, as the product requires.
 * [citedFindingIds] lets the UI link each statement back to the finding it came from.
 */
data class AssistantAnswer(
    val question: String,
    val headline: String,
    val plainExplanation: String,
    val technicalDetail: String,
    val facts: List<String> = emptyList(),
    val hypotheses: List<String> = emptyList(),
    val unknowns: List<String> = emptyList(),
    val suggestedActions: List<String> = emptyList(),
    val citedFindingIds: List<String> = emptyList(),
    val mode: AssistantMode = AssistantMode.DETERMINISTIC,
    val modelNote: String = "Deterministic engine - no language model was used."
)
