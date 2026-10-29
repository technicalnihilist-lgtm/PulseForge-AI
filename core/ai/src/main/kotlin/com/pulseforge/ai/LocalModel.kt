package com.pulseforge.ai

/**
 * Describes an optional on-device model.
 *
 * PulseForge ships no language model. This type exists so the app can tell the truth about it: if
 * a build bundles a model file, [available] is true and the assistant may use it; otherwise the
 * assistant runs the deterministic engine and says so. There is deliberately no code path that
 * pretends a model exists.
 */
data class LocalModelInfo(
    val available: Boolean,
    val name: String = "none",
    val sizeBytes: Long = 0,
    val note: String = "No on-device language model is bundled with this build."
)

interface LocalModelProvider {
    fun describe(): LocalModelInfo
}

/** The default provider: no model, deterministic answers only. */
object NoLocalModel : LocalModelProvider {
    override fun describe() = LocalModelInfo(available = false)
}
