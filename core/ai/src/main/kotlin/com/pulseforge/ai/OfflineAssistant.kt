package com.pulseforge.ai

import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.ScanReport
import com.pulseforge.performance.PlannedAction

/**
 * The assistant that ships in the app.
 *
 * It is deterministic: every sentence is generated from fields that already exist in the report,
 * so it cannot invent a vulnerability, a measurement or a test result. When it does not know
 * something it says so, under "unknowns".
 */
class OfflineAssistant(private val modelProvider: LocalModelProvider = NoLocalModel) {

    fun modelInfo(): LocalModelInfo = modelProvider.describe()

    fun answer(question: String, report: ScanReport, plan: List<PlannedAction> = emptyList()): AssistantAnswer {
        val q = question.lowercase().trim()
        if (q.isEmpty()) return overview(report, plan)

        findFinding(q, report)?.let { return explain(it, report) }

        return when {
            q.containsAny("priorit", "first", "fix", "plan", "what should", "remediat", "action") -> prioritised(report, plan)
            q.containsAny("score", "rating", "number") -> scoreAnswer(report)
            q.containsAny("cannot", "can't", "unknown", "unverif", "limitation", "sure", "confiden") -> unknownsAnswer(report)
            q.containsAny("privacy", "permission", "tracker", "data") -> privacyAnswer(report)
            q.containsAny("performance", "size", "slow", "memory", "startup", "fps", "battery") -> performanceAnswer(report)
            q.containsAny("secret", "key", "credential", "password", "token") -> secretsAnswer(report)
            q.containsAny("manifest", "exported", "component", "activity", "provider") -> manifestAnswer(report)
            q.containsAny("sign", "certificate", "cert") -> signingAnswer(report)
            q.containsAny("summary", "overview", "what is", "about this app", "report") -> overview(report, plan)
            else -> searchAnswer(q, report) ?: capabilities(report)
        }
    }

    private fun overview(report: ScanReport, plan: List<PlannedAction>): AssistantAnswer {
        val manifest = report.apk.manifest
        val counts = report.counts
        val top = report.findings.take(3)

        return AssistantAnswer(
            question = "Overview",
            headline = if (counts.total == 0) "No findings in this APK" else "${counts.total} findings, ${counts.critical} critical",
            plainExplanation = buildString {
                append("This is a static analysis of ${report.apk.fileName}")
                if (manifest != null) append(" (${manifest.packageName} ${manifest.versionName ?: ""})")
                append(". Nothing was installed or executed. ")
                append(
                    if (counts.total == 0) {
                        "None of the ${report.scoring.coverage.size} checks found anything. That is good news, " +
                            "but it is not a guarantee: code hidden behind a packer or reached only by reflection is invisible here."
                    } else {
                        "The most serious items are: " + top.joinToString("; ") { "${it.title} (${it.severity.name.lowercase()})" } + "."
                    }
                )
                append(" The security score is ${report.scoring.security ?: "n/a"} out of 100, computed only from the findings listed below.")
            },
            technicalDetail = "Analysers that ran: ${report.engine.analyzers.joinToString(", ")}. " +
                "Rules: ${report.scoring.coverage.size} (ruleset ${report.engine.ruleSetVersion}). " +
                "Scan took ${report.meta.durationMs} ms. Manifest parsed: ${report.apk.manifest != null}. " +
                "Signature verified: ${report.apk.signing?.verified ?: false}.",
            facts = buildList {
                add("APK size: ${report.apk.fileSizeBytes} bytes, SHA-256 ${report.apk.sha256.take(16)}...")
                manifest?.let {
                    add("minSdk ${it.minSdk ?: "?"}, targetSdk ${it.targetSdk ?: "?"}")
                    add("${it.requestedPermissions.size} permissions requested, ${it.components.size} components declared")
                }
                add("Severity split: ${counts.critical} critical, ${counts.high} high, ${counts.medium} medium, ${counts.low} low, ${counts.info} info")
            },
            unknowns = report.meta.limitations,
            suggestedActions = plan.take(3).map { "${it.title} - ${it.rationale.take(140)}" },
            citedFindingIds = top.map { it.id },
            modelNote = modelNote()
        )
    }

    private fun explain(finding: Finding, report: ScanReport): AssistantAnswer = AssistantAnswer(
        question = finding.title,
        headline = "${finding.title} - ${finding.severity.name.lowercase()} severity, ${confidenceText(finding.confidence)}",
        plainExplanation = finding.summary,
        technicalDetail = finding.technicalDetail,
        facts = buildList {
            finding.evidence.forEach { add("${it.label}: ${it.value} (${it.source})") }
            add("Rule ${finding.ruleId} fired on this APK.")
            if (finding.masvsControls.isNotEmpty()) add("Mapped controls: ${finding.masvsControls.joinToString(", ")}")
            if (finding.cweIds.isNotEmpty()) add("Weakness classes: ${finding.cweIds.joinToString(", ")}")
        },
        hypotheses = if (finding.evidenceType == EvidenceType.PATTERN_MATCH) {
            listOf(
                "The pattern exists in the APK, but the code path that reaches it was not verified.",
                finding.falsePositiveNote ?: "Review the call site before treating this as confirmed."
            )
        } else {
            listOf(finding.falsePositiveNote ?: "The observation itself is certain; its impact depends on the app's logic.")
        },
        unknowns = listOf(
            "Whether this code is reachable at runtime.",
            "What data actually flows through it (static analysis sees structure, not values)."
        ),
        suggestedActions = listOf(finding.remediation),
        citedFindingIds = listOf(finding.id),
        modelNote = modelNote()
    )

    private fun prioritised(report: ScanReport, plan: List<PlannedAction>): AssistantAnswer {
        if (plan.isEmpty()) {
            return AssistantAnswer(
                question = "What should I fix first?",
                headline = "Nothing to fix from this scan",
                plainExplanation = "No finding or optimisation item crossed the reporting threshold, so there is no " +
                    "prioritised plan. The report still lists what could not be verified.",
                technicalDetail = "The planner only emits actions for findings at medium severity or above and for " +
                    "measurable size problems.",
                facts = listOf("0 planned actions"),
                unknowns = report.meta.limitations,
                modelNote = modelNote()
            )
        }
        val top = plan.take(5)
        return AssistantAnswer(
            question = "What should I fix first?",
            headline = "${plan.size} actions, starting with: ${top.first().title}",
            plainExplanation = "The plan is ordered by impact, then by effort. The first item is the one that buys the " +
                "most risk reduction for the least work.",
            technicalDetail = top.joinToString("\n") { action ->
                "${action.priority}. ${action.title} (impact ${action.impact.name.lowercase()}, " +
                    "effort ${action.effort.name.lowercase()}) - ${action.rationale}"
            },
            facts = top.map { "${it.title} - ${it.origin}" },
            hypotheses = listOf("Effort estimates are PulseForge's judgement, not measurements."),
            unknowns = if (report.apk.manifest?.debuggable == true) {
                listOf("Whether this is your own debug build, in which case the debuggable finding is expected.")
            } else emptyList(),
            suggestedActions = top.map { it.steps.firstOrNull() ?: it.rationale },
            citedFindingIds = top.mapNotNull { it.findingId },
            modelNote = modelNote()
        )
    }

    private fun scoreAnswer(report: ScanReport): AssistantAnswer {
        val breakdown = report.scoring.securityBreakdown
        return AssistantAnswer(
            question = "How was the score calculated?",
            headline = "Security ${report.scoring.security ?: "n/a"}/100, performance ${report.scoring.performance ?: "n/a"}/100",
            plainExplanation = "The score starts at 100. Each finding subtracts a fixed weight by severity, scaled by " +
                "confidence. Nothing else affects it, so you can recompute it by hand from the list below.",
            technicalDetail = buildString {
                append("Weights: critical 25, high 12, medium 5, low 2, info 0. ")
                append("Confidence multipliers: confirmed/high 1.0, medium 0.7, low 0.4. Result clamped to 0-100.\n")
                if (breakdown.isEmpty()) append("No deductions were applied.")
                else breakdown.forEach { append("- ${it.label}: -${it.points} (${it.explanation})\n") }
                append("\nPerformance deductions: ")
                append(
                    report.scoring.performanceBreakdown.joinToString("; ") { "${it.label} -${it.points} (${it.explanation})" }
                        .ifEmpty { "none" }
                )
            },
            facts = breakdown.take(8).map { "${it.label}: -${it.points}" },
            unknowns = listOf("A score cannot express risk that no rule covers - see the coverage list."),
            modelNote = modelNote()
        )
    }

    private fun unknownsAnswer(report: ScanReport): AssistantAnswer = AssistantAnswer(
        question = "What can't you tell me?",
        headline = "What this analysis cannot verify",
        plainExplanation = "Static analysis reads the file. It cannot watch the app run, so anything that depends on " +
            "runtime behaviour is out of scope - and I will not guess at it.",
        technicalDetail = buildString {
            report.meta.limitations.forEach { append("- $it\n") }
            append("\nSpecifically unavailable here:\n")
            report.performance.unavailable.forEach { append("- ${it.label}: ${it.reason}\n") }
            val lowConfidence = report.findings.filter { it.confidence == Confidence.LOW }
            if (lowConfidence.isNotEmpty()) {
                append("\nFindings with low confidence (treat as leads, not facts): ")
                append(lowConfidence.joinToString(", ") { it.title })
            }
        },
        facts = listOf("${report.findings.count { it.evidenceType == EvidenceType.DIRECT_OBSERVATION }} findings are direct observations"),
        hypotheses = report.findings
            .filter { it.evidenceType == EvidenceType.PATTERN_MATCH }
            .take(5)
            .map { "${it.title} - pattern match, reachability unverified" },
        unknowns = report.meta.limitations,
        modelNote = modelNote()
    )

    private fun privacyAnswer(report: ScanReport): AssistantAnswer {
        val groups = report.privacy.permissionGroups
        return AssistantAnswer(
            question = "Privacy",
            headline = "${report.privacy.dangerousPermissionCount} dangerous permissions, " +
                "${report.privacy.exportedComponentCount} exported components",
            plainExplanation = if (groups.isEmpty()) {
                "No dangerous permissions were found. Exported components still matter: they decide which other " +
                    "apps can talk to this one."
            } else {
                "The app asks for data in these categories: " + groups.take(5).joinToString(", ") { it.group } +
                    ". Each one is a capability the app must justify."
            },
            technicalDetail = buildString {
                groups.forEach { append("- ${it.group} (${it.risk.name}): ${it.permissions.joinToString(", ")} - ${it.rationale}\n") }
                if (report.privacy.trackerSignals.isNotEmpty()) {
                    append("\nSDK signals: ")
                    append(report.privacy.trackerSignals.joinToString(", ") { "${it.name} (${it.confidence.name})" })
                }
                report.privacy.notes.forEach { append("\n$it") }
            },
            facts = groups.map { "${it.group}: ${it.permissions.size} permission(s), risk ${it.risk.name}" },
            hypotheses = report.privacy.trackerSignals.map { "${it.name} is bundled; what it collects is not observable statically." },
            unknowns = listOf(
                "What data leaves the device, and to whom.",
                "Whether the app declares these permissions in its Play data-safety form."
            ),
            suggestedActions = report.findings.filter { it.category.name == "PRIVACY" }.take(3).map { it.remediation },
            citedFindingIds = report.findings.filter { it.category.name == "PRIVACY" }.take(3).map { it.id },
            modelNote = modelNote()
        )
    }

    private fun performanceAnswer(report: ScanReport): AssistantAnswer {
        val perf = report.performance
        return AssistantAnswer(
            question = "Performance",
            headline = "Score ${report.scoring.performance ?: "n/a"}/100 - ${perf.measured.firstOrNull()?.value ?: "size unknown"}",
            plainExplanation = "These numbers come from the file itself. Startup time, frame rate, memory in use and " +
                "battery cannot be measured from an APK, and I will not invent them.",
            technicalDetail = buildString {
                append("Measured:\n")
                perf.measured.forEach { append("- ${it.label}: ${it.value}${it.detail?.let { d -> " ($d)" } ?: ""}\n") }
                if (perf.estimates.isNotEmpty()) {
                    append("\nEstimated:\n")
                    perf.estimates.forEach { append("- ${it.label}: ${it.estimate} - ${it.basis}\n") }
                }
                append("\nNot measurable: ")
                append(perf.unavailable.joinToString(", ") { it.label })
            },
            facts = perf.measured.map { "${it.label}: ${it.value}" },
            hypotheses = perf.estimates.map { "${it.label}: ${it.estimate} (${it.confidence.name} confidence)" },
            unknowns = perf.unavailable.map { "${it.label}: ${it.reason}" },
            suggestedActions = perf.actions.take(4).map { "${it.title} - ${it.rationale.take(120)}" },
            modelNote = modelNote()
        )
    }

    private fun secretsAnswer(report: ScanReport): AssistantAnswer {
        val secrets = report.findings.filter { it.ruleId.startsWith("PF-SEC-001") }
        return AssistantAnswer(
            question = "Secrets",
            headline = if (secrets.isEmpty()) "No credential patterns matched" else "${secrets.size} credential pattern(s) matched",
            plainExplanation = if (secrets.isEmpty()) {
                "The scanner looked for AWS keys, Google API keys, Stripe keys, private keys, JWTs and " +
                    "credential-like assignments in the bytecode, resources and assets. Nothing matched."
            } else {
                "Some strings look like credentials. Values are masked in this app on purpose: read them on your own " +
                    "machine if you need to confirm, then rotate anything real."
            },
            technicalDetail = buildString {
                secrets.forEach { finding ->
                    append("- ${finding.title} (${finding.severity.name}, ${finding.confidence.name}): ")
                    append(finding.evidence.joinToString("; ") { "${it.label}=${it.value} [${it.source}]" })
                    append("\n")
                }
                if (secrets.isEmpty()) append("Patterns are matched against DEX strings, resources.arsc values and text assets.")
            },
            facts = secrets.flatMap { it.evidence.map { e -> "${e.label}: ${e.value} (${e.source})" } },
            hypotheses = listOf("A matching string may be a placeholder, a public identifier or a test fixture."),
            unknowns = listOf("Whether a matched key is still active."),
            suggestedActions = secrets.map { it.remediation }.distinct(),
            citedFindingIds = secrets.map { it.id },
            modelNote = modelNote()
        )
    }

    private fun manifestAnswer(report: ScanReport): AssistantAnswer {
        val manifest = report.apk.manifest
            ?: return AssistantAnswer(
                question = "Manifest",
                headline = "The manifest could not be parsed",
                plainExplanation = "The binary manifest was unreadable, so every manifest-based check was skipped. " +
                    "That is a limitation of this report, not a statement about the app.",
                technicalDetail = report.apk.manifestParseError ?: "Unknown parse error.",
                unknowns = listOf("Everything that depends on the manifest."),
                modelNote = modelNote()
            )
        val exported = manifest.components.filter { it.effectiveExported(manifest.targetSdk) }
        return AssistantAnswer(
            question = "Manifest",
            headline = "${manifest.components.size} components, ${exported.size} exported",
            plainExplanation = if (exported.isEmpty()) {
                "No component is reachable from other apps."
            } else {
                "Other apps can reach: " + exported.take(5).joinToString(", ") { it.shortName } +
                    ". Exported components are entry points; what they do with the incoming data decides the risk."
            },
            technicalDetail = buildString {
                append("debuggable=${manifest.debuggable ?: "unset"}, allowBackup=${manifest.allowBackup ?: "unset"}, ")
                append("usesCleartextTraffic=${manifest.usesCleartextTraffic ?: "unset"}\n")
                manifest.components.forEach { c ->
                    append("- ${c.type.name.lowercase()} ${c.name}: exported=${c.exported ?: "implicit"}, ")
                    append("permission=${c.permission ?: "none"}, filters=${c.intentFilters.size}\n")
                }
            },
            facts = exported.map { "${it.type.name} ${it.name} is exported (permission: ${it.permission ?: "none"})" },
            unknowns = listOf("Whether exported components validate their inputs."),
            citedFindingIds = report.findings.filter { it.ruleId.startsWith("PF-MAN-00") }.map { it.id },
            modelNote = modelNote()
        )
    }

    private fun signingAnswer(report: ScanReport): AssistantAnswer {
        val signing = report.apk.signing
        val cert = signing?.certificate
        if (signing == null || cert == null) {
            return AssistantAnswer(
                question = "Signing",
                headline = "No signing certificate found",
                plainExplanation = "The archive either is not signed or uses a scheme PulseForge could not read.",
                technicalDetail = signing?.warnings?.joinToString("\n") ?: "No signature information.",
                unknowns = listOf("The origin of the APK."),
                modelNote = modelNote()
            )
        }
        return AssistantAnswer(
            question = "Signing",
            headline = "Signed with ${cert.keyAlgorithm} ${cert.keySizeBits}-bit (${signing.schemeSummary})",
            plainExplanation = "The APK carries a ${signing.schemeSummary} signature. Verified: ${signing.verified}. " +
                "A signature proves the archive was not modified after signing by the holder of the key; it does not " +
                "prove the app is trustworthy.",
            technicalDetail = "Subject: ${cert.subject}\nIssuer: ${cert.issuer}\nSHA-256: ${cert.sha256}\n" +
                "Valid until: ${cert.notAfterEpochMs}\nDebug key: ${cert.isKnownDebugKey}",
            facts = listOf(
                "Schemes: ${signing.schemeSummary}",
                "Verified: ${signing.verified} (${signing.verificationMessage ?: "-"})",
                "Subject: ${cert.subject}"
            ),
            unknowns = listOf("Whether the signing key is held securely by the publisher."),
            citedFindingIds = report.findings.filter { it.ruleId == "PF-SIG-001" }.map { it.id },
            modelNote = modelNote()
        )
    }

    private fun searchAnswer(query: String, report: ScanReport): AssistantAnswer? {
        val terms = query.split(' ', ',', '.').filter { it.length > 3 }
        val matches = report.findings.filter { finding ->
            terms.any { term ->
                finding.title.contains(term, true) || finding.summary.contains(term, true) ||
                    finding.ruleId.contains(term, true) || finding.affectedComponent?.contains(term, true) == true
            }
        }
        if (matches.isEmpty()) return null
        return explain(matches.first(), report).copy(
            question = query,
            headline = "${matches.size} finding(s) match \"$query\"",
            technicalDetail = matches.joinToString("\n\n") { "${it.title} (${it.severity.name}): ${it.summary}" }
        )
    }

    private fun capabilities(report: ScanReport) = AssistantAnswer(
        question = "What can you answer?",
        headline = "I can explain anything in this report",
        plainExplanation = "Ask me about a finding, the score, permissions and privacy, performance, secrets, " +
            "the manifest or the signature. I answer only from this scan's data, and I separate what was observed " +
            "from what is inferred.",
        technicalDetail = "Examples: \"why is debuggable critical?\", \"what should I fix first?\", " +
            "\"what can't you verify?\", \"explain the webview finding\", \"how is the score calculated?\"",
        facts = listOf("${report.findings.size} findings available to discuss"),
        unknowns = report.meta.limitations,
        modelNote = modelNote()
    )

    private fun findFinding(q: String, report: ScanReport): Finding? {
        report.findings.firstOrNull { finding ->
            val title = finding.title.lowercase()
            (q.contains(title) && title.length > 8) ||
                q.contains(finding.ruleId.lowercase()) ||
                (finding.affectedComponent?.let { q.contains(it.lowercase()) } == true)
        }?.let { return it }

        val words = q.split(' ', '?', ',', '.').filter { it.length > 4 }
        return report.findings.maxByOrNull { finding ->
            words.count { finding.title.contains(it, true) || finding.summary.contains(it, true) }
        }?.takeIf { finding -> words.any { finding.title.contains(it, true) || finding.summary.contains(it, true) } }
    }

    private fun confidenceText(confidence: Confidence): String = when (confidence) {
        Confidence.CONFIRMED -> "confirmed by direct observation"
        Confidence.HIGH -> "high confidence"
        Confidence.MEDIUM -> "medium confidence - verify before acting"
        Confidence.LOW -> "low confidence - treat as a lead"
    }

    private fun modelNote(): String = if (modelProvider.describe().available) {
        "Answered by the bundled on-device model ${modelProvider.describe().name}."
    } else {
        "Deterministic engine - no language model is bundled or used. Every statement above is generated from " +
            "fields in this report."
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { this.contains(it) }
}
