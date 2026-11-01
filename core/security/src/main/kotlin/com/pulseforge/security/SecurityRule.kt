package com.pulseforge.security

import com.pulseforge.apk.ApkScanInput
import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceItem
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.FindingCategory
import com.pulseforge.model.Reference
import com.pulseforge.model.Severity

/** One deterministic check over parsed APK facts. */
interface SecurityRule {
    val id: String
    val title: String
    fun evaluate(ctx: RuleContext): List<Finding>
}

/** Read-only view over everything a rule may look at. */
class RuleContext(val input: ApkScanInput) {

    val facts get() = input.facts
    val manifest get() = facts.manifest
    val dex get() = input.dex
    val targetSdk: Int? get() = manifest?.targetSdk
    val minSdk: Int? get() = manifest?.minSdk

    val strings: StringIndex by lazy {
        StringIndex(input.dex.strings, input.resourceStrings, input.assetTextSamples)
    }

    val xmlSummaries: Map<String, String> get() = input.xmlResourceSummaries

    fun occurrences(owner: String, member: String): Int = dex.occurrences(owner, member)

    fun anyOccurrence(vararg keys: String): Int = dex.anyUsage(*keys)

    /** True when any compiled res/xml document matches [predicate]. */
    fun anyXml(predicate: (String) -> Boolean): String? = xmlSummaries.entries.firstOrNull { predicate(it.value) }?.value
}

/** Fast lookup helpers over the DEX string pool, resource strings and text assets. */
class StringIndex(
    private val dexStrings: List<String>,
    private val resourceStrings: List<String>,
    private val assetTexts: Map<String, String>
) {
    private val combined: List<String> by lazy { dexStrings + resourceStrings }

    fun containsAny(vararg needles: String): Boolean = needles.any { firstContaining(it) != null }

    fun firstContaining(needle: String): String? = combined.firstOrNull { it.contains(needle, ignoreCase = true) }

    /**
     * Lower-cased view of the pool, built once. [hasExact] used to lower-case every string on every
     * call, which dominated the crypto rules on large APKs.
     */
    private val lowered: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        combined.mapTo(HashSet(combined.size)) { it.lowercase() }
    }

    /** Exact, case-insensitive membership test for values such as algorithm names. */
    fun hasExact(vararg values: String): List<String> = values.filter { it.lowercase() in lowered }

    /**
     * Tests several patterns in a single pass over the pool.
     *
     * The secrets rule has eight patterns; calling [regexMatches] once per pattern scanned the whole
     * DEX string pool eight times. This walks it once and stops as soon as every pattern has its
     * quota, which is the difference between ~8 full scans and one.
     */
    fun multiRegexMatches(
        patterns: List<Regex>,
        limitPerPattern: Int = 6
    ): Map<Int, List<Pair<String, String>>> {
        if (patterns.isEmpty()) return emptyMap()
        val results = HashMap<Int, MutableList<Pair<String, String>>>(patterns.size)

        fun record(index: Int, source: String, value: String) {
            val list = results.getOrPut(index) { mutableListOf() }
            if (list.size < limitPerPattern) list.add(source to value)
        }

        fun full(index: Int) = (results[index]?.size ?: 0) >= limitPerPattern
        fun allFull() = patterns.indices.all { full(it) }

        for (s in combined) {
            if (s.length > 4096) continue
            for (i in patterns.indices) {
                if (full(i)) continue
                if (patterns[i].containsMatchIn(s)) record(i, "DEX/resource string pool", s)
            }
            if (allFull()) return results.mapValues { it.value.toList() }
        }

        for ((path, text) in assetTexts) {
            for (line in text.lineSequence()) {
                if (line.length > 4096) continue
                for (i in patterns.indices) {
                    if (full(i)) continue
                    if (patterns[i].containsMatchIn(line)) record(i, path, line.trim())
                }
                if (allFull()) return results.mapValues { it.value.toList() }
            }
        }

        return results.mapValues { it.value.toList() }
    }

    fun regexMatches(regex: Regex, limit: Int = 10): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (s in combined) {
            if (s.length > 4096) continue
            if (regex.containsMatchIn(s)) {
                out.add("DEX/resource string pool" to s)
                if (out.size >= limit) return out
            }
        }
        for ((path, text) in assetTexts) {
            for (line in text.lineSequence()) {
                if (line.length <= 4096 && regex.containsMatchIn(line)) {
                    out.add(path to line.trim())
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    fun size(): Int = combined.size
}

/** Masks a secret so it can be shown as evidence without leaking it into logs or reports. */
object Redactor {
    fun mask(value: String, keep: Int = 4): String {
        val trimmed = value.trim()
        if (trimmed.length <= keep * 2) return "*".repeat(trimmed.length.coerceAtMost(8))
        return trimmed.take(keep) + "*".repeat((trimmed.length - keep * 2).coerceAtMost(12)) + trimmed.takeLast(keep)
    }

    private val userInfoPattern = Regex("(://)([^/@\\s:]+)(:)([^/@\\s]+)(@)")

    /** Masks userinfo inside a URL, so only the scheme and host remain readable. */
    fun maskUrlCredentials(url: String): String = url.replace(userInfoPattern, "$1***:***$5")

    private val sensitiveQuery = Regex(
        "([?&](?:api[_-]?key|apikey|key|token|access[_-]?token|secret|password|passwd|sig|signature|auth)=)([^&\\s#]+)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Full URL redaction: userinfo plus the values of credential-looking query parameters. A URL
     * such as one carrying an api_key parameter is a common way for a key to reach a report, so
     * every URL in evidence goes through this.
     */
    fun maskUrlSecrets(url: String): String {
        val withoutUserInfo = maskUrlCredentials(url)
        return sensitiveQuery.replace(withoutUserInfo) { match ->
            match.groupValues[1] + mask(match.groupValues[2])
        }
    }
}

/** Shared constructor for findings so ids and metadata stay consistent. */
internal fun finding(
    ruleId: String,
    title: String,
    severity: Severity,
    confidence: Confidence,
    evidenceType: EvidenceType,
    summary: String,
    detail: String,
    remediation: String,
    category: FindingCategory = FindingCategory.SECURITY,
    affected: String? = null,
    evidence: List<EvidenceItem> = emptyList(),
    references: List<Reference> = emptyList(),
    fpNote: String? = null,
    masvs: List<String> = emptyList(),
    cwe: List<String> = emptyList(),
    idSuffix: String = ""
): Finding = Finding(
    id = buildString {
        append(ruleId)
        if (affected != null) append('|').append(affected)
        if (idSuffix.isNotEmpty()) append('#').append(idSuffix)
    },
    ruleId = ruleId,
    title = title,
    category = category,
    severity = severity,
    confidence = confidence,
    evidenceType = evidenceType,
    summary = summary,
    technicalDetail = detail,
    affectedComponent = affected,
    evidence = evidence,
    remediation = remediation,
    references = references,
    falsePositiveNote = fpNote,
    masvsControls = masvs,
    cweIds = cwe
)

internal fun ev(label: String, value: String, source: String) = EvidenceItem(label, value, source)

/** Runs every registered rule and returns findings in a stable, severity-ordered list. */
class SecurityEngine(private val rules: List<SecurityRule> = RuleCatalog.rules) {

    fun evaluate(input: ApkScanInput): List<Finding> {
        val ctx = RuleContext(input)
        val findings = mutableListOf<Finding>()
        for (rule in rules) {
            try {
                findings.addAll(rule.evaluate(ctx))
            } catch (e: Exception) {
                // A rule must never take down a scan; the failure is surfaced as a finding instead.
                findings.add(
                    finding(
                        ruleId = "ENGINE_ERROR",
                        title = "Rule ${rule.id} could not complete",
                        severity = Severity.INFO,
                        confidence = Confidence.LOW,
                        evidenceType = EvidenceType.INFERENCE,
                        summary = "The rule ${rule.id} failed while analysing this APK.",
                        detail = "Exception: ${e::class.simpleName}: ${e.message}",
                        remediation = "This is a PulseForge issue, not an app issue. The rest of the report is unaffected.",
                        idSuffix = rule.id
                    )
                )
            }
        }
        return findings.distinctBy { it.id }.sortedWith(compareByDescending<Finding> { it.severity.rank }.thenBy { it.title })
    }

    fun ruleIds(): List<String> = rules.map { it.id }
}
