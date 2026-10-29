package com.pulseforge.app.ai

import android.content.Context
import com.pulseforge.ai.LocalModelInfo
import com.pulseforge.ai.LocalModelProvider

/**
 * Checks whether this build actually bundles an on-device model.
 *
 * PulseForge ships without one, so this reports "not available" and the assistant runs the
 * deterministic engine. If a future build adds the asset below, the flag flips automatically and
 * the About screen shows the model name and size - no code pretends a model exists when the asset
 * is missing.
 */
class AssetLocalModel(private val context: Context) : LocalModelProvider {

    companion object {
        private const val MODEL_PATH = "models/pulseforge-lite.tflite"
    }

    override fun describe(): LocalModelInfo = try {
        val stream = context.assets.open(MODEL_PATH)
        val size = stream.available().toLong()
        stream.close()
        LocalModelInfo(
            available = true,
            name = MODEL_PATH.substringAfterLast('/'),
            sizeBytes = size,
            note = "A local model asset is bundled. The assistant still labels which parts of an answer " +
                "come from the model and which come from the report."
        )
    } catch (e: Exception) {
        LocalModelInfo(available = false)
    }
}
