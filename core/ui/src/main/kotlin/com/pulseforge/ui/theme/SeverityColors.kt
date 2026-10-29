package com.pulseforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Severity

/** Severity palette that adapts to the current theme. */
object SeverityColors {

    @Composable
    @ReadOnlyComposable
    fun content(severity: Severity): Color {
        val dark = isSystemInDarkTheme()
        return when (severity) {
            Severity.CRITICAL -> if (dark) Color(0xFFFFB4AB) else SeverityCritical
            Severity.HIGH -> if (dark) Color(0xFFFFB59A) else SeverityHigh
            Severity.MEDIUM -> if (dark) Color(0xFFFFD98A) else SeverityMedium
            Severity.LOW -> if (dark) Color(0xFFA8CFFF) else SeverityLow
            Severity.INFO -> if (dark) Color(0xFFC4C6CF) else SeverityInfo
        }
    }

    @Composable
    @ReadOnlyComposable
    fun container(severity: Severity): Color {
        val dark = isSystemInDarkTheme()
        return when (severity) {
            Severity.CRITICAL -> if (dark) Color(0xFF5C1020) else SeverityCriticalContainer
            Severity.HIGH -> if (dark) Color(0xFF5C2A14) else SeverityHighContainer
            Severity.MEDIUM -> if (dark) Color(0xFF4A3700) else SeverityMediumContainer
            Severity.LOW -> if (dark) Color(0xFF16354F) else SeverityLowContainer
            Severity.INFO -> if (dark) Color(0xFF2C2F36) else SeverityInfoContainer
        }
    }

    @Composable
    @ReadOnlyComposable
    fun confidence(confidence: Confidence): Color {
        val dark = isSystemInDarkTheme()
        return when (confidence) {
            Confidence.CONFIRMED -> if (dark) Color(0xFF9EE29B) else ConfidenceConfirmed
            Confidence.HIGH -> if (dark) Color(0xFFB6DDB4) else Color(0xFF2E6B32)
            Confidence.MEDIUM -> if (dark) Color(0xFFFFD98A) else ConfidenceHeuristic
            Confidence.LOW -> if (dark) Color(0xFFFFB4AB) else Color(0xFF8B2C2C)
        }
    }

    fun evidenceLabel(type: EvidenceType): String = when (type) {
        EvidenceType.DIRECT_OBSERVATION -> "Observed"
        EvidenceType.PATTERN_MATCH -> "Heuristic"
        EvidenceType.INFERENCE -> "Inferred"
    }
}
