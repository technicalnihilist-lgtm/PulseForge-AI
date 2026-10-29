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

    private val shapes = listOf(
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{30,45}\b"""),
        Regex("""\bsk_live_[0-9A-Za-z]{16,}\b"""),
        Regex("""\bxox[baprs]-[0-9A-Za-z\-]{10,}\b"""),
        Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""),
        Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----""")
    )

    private val sensitiveQuery = Regex(
        "([?&](?:api[_-]?key|apikey|key|token|access[_-]?token|secret|password|passwd|sig|signature|auth)=)([^&\\s\"#]+)",
        RegexOption.IGNORE_CASE
    )

    private val userInfo = Regex("(://)([^/@\\s:]+)(:)([^/@\\s]+)(@)")

    fun redact(text: String): String {
        var out = text
        for (shape in shapes) out = shape.replace(out) { match -> mask(match.value) }
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
