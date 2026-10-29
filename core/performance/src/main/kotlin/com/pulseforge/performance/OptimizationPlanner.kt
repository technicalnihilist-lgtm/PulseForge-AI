package com.pulseforge.performance

import com.pulseforge.model.ApkFacts
import com.pulseforge.model.Effort
import com.pulseforge.model.Finding
import com.pulseforge.model.Impact
import com.pulseforge.model.PerformanceAnalysis
import com.pulseforge.model.Severity

/** One prioritized item in the action plan shown on the dashboard and in reports. */
data class PlannedAction(
    val priority: Int,
    val title: String,
    val origin: String,
    val impact: Impact,
    val effort: Effort,
    val requiresSourceAccess: Boolean,
    val rationale: String,
    val steps: List<String>,
    val estimatedSavingBytes: Long? = null,
    val findingId: String? = null
)

/**
 * Merges security findings and performance actions into one ordered plan.
 *
 * Ordering is deterministic: impact first, then effort, then title. Items that need source access
 * are flagged so the UI can label them "report only" for someone else's app.
 */
object OptimizationPlanner {

    fun plan(
        findings: List<Finding>,
        performance: PerformanceAnalysis,
        facts: ApkFacts,
        userOwnsSource: Boolean
    ): List<PlannedAction> {
        val actions = mutableListOf<PlannedAction>()

        findings.filter { it.severity.rank >= Severity.MEDIUM.rank }.forEach { finding ->
            actions.add(
                PlannedAction(
                    priority = 0,
                    title = finding.title,
                    origin = "Security - ${finding.ruleId}",
                    impact = when (finding.severity) {
                        Severity.CRITICAL, Severity.HIGH -> Impact.HIGH
                        Severity.MEDIUM -> Impact.MEDIUM
                        else -> Impact.LOW
                    },
                    effort = effortFor(finding),
                    requiresSourceAccess = true,
                    rationale = finding.summary,
                    steps = listOf(finding.remediation),
                    findingId = finding.id
                )
            )
        }

        performance.actions.forEach { action ->
            actions.add(
                PlannedAction(
                    priority = 0,
                    title = action.title,
                    origin = "Performance",
                    impact = action.impact,
                    effort = action.effort,
                    requiresSourceAccess = action.requiresSourceAccess,
                    rationale = action.rationale,
                    steps = action.steps,
                    estimatedSavingBytes = action.estimatedSavingBytes
                )
            )
        }

        val ordered = actions.sortedWith(
            compareByDescending<PlannedAction> { it.impact.ordinal * -1 }
                .thenBy { it.effort.ordinal }
                .thenBy { it.title }
        )

        return ordered.mapIndexed { index, action ->
            action.copy(priority = index + 1, requiresSourceAccess = action.requiresSourceAccess && userOwnsSource)
        }
    }

    private fun effortFor(finding: Finding): Effort = when (finding.ruleId) {
        "PF-MAN-005", "PF-MAN-007", "PF-MAN-008", "PF-MAN-009", "PF-MAN-006" -> Effort.LOW
        "PF-MAN-001", "PF-MAN-003", "PF-MAN-004", "PF-MAN-002", "PF-SIG-001" -> Effort.MEDIUM
        "PF-WEB-001-JSI", "PF-WEB-001-FILE", "PF-TLS-001-SSLERR", "PF-TLS-001-TRUST" -> Effort.HIGH
        else -> Effort.MEDIUM
    }
}
