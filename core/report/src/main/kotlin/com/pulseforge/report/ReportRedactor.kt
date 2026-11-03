package com.pulseforge.report

import com.pulseforge.model.ScanReport

/**
 * Last line of defence before a report leaves the app.
 *
 * Individual rules already mask the values they report, but a rule that quotes a larger string
 * (a JSON blob, a URL with a query parameter) could still carry a credential. This pass rewrites
 * every evidence value, summary and detail so anything matching a known secret shape is masked,
 * whichever rule produced it.
 */
object ReportRedactor {

    /**
     * One compiled alternation instead of six separate patterns. Every shape is masked the same
     * way, so a single left-to-right pass over the text produces exactly the same output as the
     * six sequential passes it replaces - and it walks each summary, detail and evidence value
     * once rather than six times.
     */
    private val shapes = Regex(
        "(?:" + listOf(
            """\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""",
            """\bAIza[0-9A-Za-z_\-]{30,45}\b""",
            """\bsk_live_[0-9A-Za-z]{16,}\b""",
            """\bxox[baprs]-[0-9A-Za-z\-]{10,}\b""",
            """\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}""",
            """-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"""
        ).joinToString("|") + ")"
    )

    private val sensitiveQuery = Regex(
        "([?&](?:api[_-]?key|apikey|key|token|access[_-]?token|secret|password|passwd|sig|signature|auth)=)([^&\\s\"#]+)",
        RegexOption.IGNORE_CASE
    )

    private val userInfo = Regex("(://)([^/@\\s:]+)(:)([^/@\\s]+)(@)")

    fun redact(text: String): String {
        var out = shapes.replace(text) { match -> mask(match.value) }
        out = userInfo.replace(out) { "$1***:***$5" }
        out = sensitiveQuery.replace(out) { match -> match.groupValues[1] + mask(match.groupValues[2]) }
        return out
    }

    fun sanitize(report: ScanReport): ScanReport = report.copy(
        findings = report.findings.map { finding ->
            finding.copy(
                summary = redact(finding.summary),
                technicalDetail = redact(finding.technicalDetail),
                evidence = finding.evidence.map { it.copy(value = redact(it.value)) }
            )
        }
    )

    private fun mask(value: String, keep: Int = 4): String {
        if (value.length <= keep * 2) return "*".repeat(value.length.coerceAtMost(8))
        return value.take(keep) + "*".repeat((value.length - keep * 2).coerceAtMost(12)) + value.takeLast(keep)
    }
}
