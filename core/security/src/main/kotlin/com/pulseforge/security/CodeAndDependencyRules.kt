package com.pulseforge.security

import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.FindingCategory
import com.pulseforge.model.Severity

/** Shell command execution from the app process. */
object RuntimeExecRule : SecurityRule {
    override val id = "PF-COD-001"
    override val title = "Process execution"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val exec = ctx.occurrences("Ljava/lang/Runtime;", "exec") +
            ctx.occurrences("Ljava/lang/ProcessBuilder;", "start")
        if (exec == 0) return emptyList()
        val commands = ctx.strings.regexMatches(Regex("""\b(logcat|su|sh|pm |am |getprop|chmod)\b"""), limit = 5)
        return listOf(
            finding(
                ruleId = id,
                title = "App executes external processes",
                severity = Severity.MEDIUM,
                confidence = Confidence.MEDIUM,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "Runtime.exec or ProcessBuilder.start is referenced $exec time(s).",
                detail = "Executing processes from an app is legitimate for a few tools, but it is also how command " +
                    "injection and privilege-escalation attempts look. If any part of the command line comes from an " +
                    "Intent, a deep link or a server response, that input must be validated.",
                remediation = "Avoid shelling out; use platform APIs. If a process is unavoidable, pass arguments as an " +
                    "array (never a single shell string) and allow-list the executable.",
                evidence = buildList {
                    add(ev("Runtime.exec / ProcessBuilder.start", "$exec reference(s)", "DEX method table"))
                    commands.forEach { (source, value) -> add(ev("Command-like string", value.take(80), source)) }
                },
                references = listOf(References.cweCommandInjection, References.masvsCode1),
                fpNote = "Screenshot tools, log collectors and root checks commonly use exec legitimately.",
                masvs = listOf("MASVS-CODE-1"),
                cwe = listOf("CWE-78")
            )
        )
    }
}

/** Dynamic code loading. */
object DynamicCodeRule : SecurityRule {
    override val id = "PF-COD-002"
    override val title = "Dynamic code loading"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val loaders = listOf(
            "Ldalvik/system/DexClassLoader;" to "<init>",
            "Ldalvik/system/PathClassLoader;" to "<init>",
            "Ldalvik/system/InMemoryDexClassLoader;" to "<init>",
            "Ldalvik/system/DexFile;" to "loadClass"
        ).sumOf { (owner, member) -> ctx.occurrences(owner, member) }
        if (loaders == 0) return emptyList()

        return listOf(
            finding(
                ruleId = id,
                title = "App loads code at runtime",
                severity = Severity.HIGH,
                confidence = Confidence.MEDIUM,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "A DEX class loader is referenced, so code that was not part of the installed APK can be executed.",
                detail = "Dynamic loading is used by plugin frameworks, hotfix systems and some malware. Without " +
                    "signature or hash verification of the loaded DEX, an attacker who can write the payload file " +
                    "achieves code execution inside the app. On Android 14+ such files must be marked read-only.",
                remediation = "Prefer shipping code in the APK. If dynamic loading is required, verify the payload's " +
                    "signature or SHA-256 against a pinned value, store it in app-private storage, and set the file " +
                    "read-only before loading.",
                evidence = listOf(ev("Class loader", "$loaders reference(s)", "DEX method table")),
                references = listOf(References.cweDynamicCode, References.masvsResilience1),
                fpNote = "Multi-dex installation and some SDKs reference these classes without loading remote code.",
                masvs = listOf("MASVS-RESILIENCE-1"),
                cwe = listOf("CWE-494")
            )
        )
    }
}

/** Sensitive values written to logcat. */
object SensitiveLoggingRule : SecurityRule {
    override val id = "PF-COD-003"
    override val title = "Sensitive logging"

    private val sensitive = Regex("(?i)(password|passwd|token|secret|api[_-]?key|authorization|session|cookie)")

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val logCalls = listOf("d", "v", "i", "w", "e").sumOf { ctx.occurrences("Landroid/util/Log;", it) }
        if (logCalls == 0) return emptyList()

        val suspects = ctx.strings.regexMatches(sensitive, limit = 6).filter { (_, value) -> value.length in 4..200 }
        if (suspects.isEmpty()) return emptyList()

        return listOf(
            finding(
                ruleId = id,
                title = "Log statements reference sensitive fields",
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "Log calls ($logCalls) coexist with strings mentioning credentials or tokens.",
                detail = "Logcat is readable by the user, by bug-report tooling and by anyone with the debug build. " +
                    "On Android 4.1+ apps can only read their own logs, but logs still end up in crash reports and " +
                    "support bundles. PulseForge cannot tell whether a specific log statement prints a secret.",
                remediation = "Strip logging of user data in release builds (R8 rules or a no-op Log wrapper), " +
                    "and never log tokens, passwords or full request bodies.",
                evidence = buildList {
                    add(ev("android.util.Log calls", "$logCalls reference(s)", "DEX method table"))
                    suspects.forEach { (source, value) ->
                        add(ev("Sensitive-looking string", Redactor.mask(value.take(80)), source))
                    }
                },
                references = listOf(References.cweLogLeak, References.masvsStorage2),
                fpNote = "High false-positive rate: the string and the log call may be unrelated.",
                masvs = listOf("MASVS-STORAGE-2"),
                cwe = listOf("CWE-532")
            )
        )
    }
}

/** Heavy reflection use. */
object ReflectionRule : SecurityRule {
    override val id = "PF-COD-004"
    override val title = "Reflection"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val reflect = ctx.occurrences("Ljava/lang/reflect/Method;", "invoke") +
            ctx.occurrences("Ljava/lang/Class;", "forName")
        if (reflect < 3) return emptyList()
        return listOf(
            finding(
                ruleId = id,
                title = "Heavy reflection usage",
                severity = Severity.INFO,
                confidence = Confidence.MEDIUM,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "Class.forName and Method.invoke are referenced $reflect times.",
                detail = "Reflection is normal in DI frameworks and serializers. It also hides call edges from static " +
                    "analysis, which means a clean result from any static scanner (including this one) is weaker for " +
                    "this app.",
                remediation = "No action required unless reflection is used to reach hidden APIs; in that case document " +
                    "why, because non-SDK interfaces are blocked on newer Android versions.",
                evidence = listOf(ev("Reflective calls", "$reflect reference(s)", "DEX method table")),
                references = listOf(References.masvsResilience1),
                category = FindingCategory.QUALITY,
                fpNote = "Informational: this affects analyser coverage, not necessarily security.",
                masvs = listOf("MASVS-RESILIENCE-1")
            )
        )
    }
}

/** Deprecated HTTP stacks and unversioned third-party SDKs. */
object DependencyRules : SecurityRule {
    override val id = "PF-DEP-001"
    override val title = "Dependencies"

    private val deprecated = mapOf(
        "Lorg/apache/http/" to "Apache HttpClient (legacy org.apache.http, removed from the platform in API 28)",
        "Landroid/net/http/SslError;" to "Legacy SSL error handling"
    )

    private val sdkPrefixes = mapOf(
        "Lcom/google/firebase/" to "Firebase",
        "Lcom/google/android/gms/" to "Google Play services",
        "Lcom/facebook/" to "Facebook SDK",
        "Lcom/squareup/okhttp" to "OkHttp",
        "Lokhttp3/" to "OkHttp",
        "Lretrofit2/" to "Retrofit",
        "Lio/reactivex/" to "RxJava",
        "Lcom/google/gson/" to "Gson",
        "Lorg/bouncycastle/" to "BouncyCastle",
        "Lcom/appsflyer/" to "AppsFlyer",
        "Lcom/amplitude/" to "Amplitude",
        "Lcom/mixpanel/" to "Mixpanel",
        "Lcom/segment/" to "Segment",
        "Lcom/adjust/" to "Adjust",
        "Lcom/onesignal/" to "OneSignal",
        "Lcom/crashlytics/" to "Crashlytics",
        "Lcom/sentry/" to "Sentry",
        "Lcom/unity3d/" to "Unity runtime",
        "Lio/flutter/" to "Flutter runtime"
    )

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val findings = mutableListOf<Finding>()

        val usedDeprecated = deprecated.keys.filter { prefix ->
            ctx.dex.methodRefs.keys.any { it.startsWith(prefix) } || ctx.dex.definedClasses.any { it.startsWith(prefix) }
        }
        if (usedDeprecated.isNotEmpty()) {
            findings.add(
                finding(
                    ruleId = "$id-LEGACY",
                    title = "Deprecated HTTP stack in use",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "The app references ${usedDeprecated.joinToString { deprecated[it] ?: it }}.",
                    detail = "The legacy Apache HttpClient was removed from the platform in API 28 and is only available " +
                        "through a compatibility jar. It receives no security fixes and encourages cleartext-capable " +
                        "request code. Its presence usually marks an old networking layer.",
                    remediation = "Migrate to OkHttp or HttpURLConnection over TLS, remove the org.apache.http.legacy " +
                        "dependency, and re-test certificate handling.",
                    evidence = usedDeprecated.map { prefix -> ev("Package", prefix, "DEX class/method table") },
                    references = listOf(References.cweUnmaintained, References.masvsNetwork1),
                    masvs = listOf("MASVS-NETWORK-1"),
                    cwe = listOf("CWE-1104")
                )
            )
        }

        val detected = sdkPrefixes.entries
            .filter { (prefix, _) -> ctx.dex.methodRefs.keys.any { it.startsWith(prefix) } }
            .map { it.value }
            .distinct()
            .sorted()

        if (detected.isNotEmpty()) {
            findings.add(
                finding(
                    ruleId = "$id-SDK",
                    title = "${detected.size} third-party SDK(s) detected",
                    severity = Severity.INFO,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "Bundled SDKs: ${detected.joinToString(", ")}.",
                    detail = "PulseForge can identify which SDKs are present from their class prefixes, but an APK " +
                        "does not carry dependency versions. Without a version it is impossible to say whether any of " +
                        "them has a known vulnerability - any tool that claims otherwise from the APK alone is guessing.",
                    remediation = "For your own app, run the Gradle dependencies task and compare against the SDK vendors' " +
                        "security bulletins. For a third-party APK, ask the vendor for their SBOM.",
                    evidence = detected.map { ev("SDK", it, "DEX class table") },
                    references = listOf(References.cweUnmaintained),
                    category = FindingCategory.QUALITY,
                    fpNote = "Detection is prefix-based; a renamed or shaded SDK will not be recognised.",
                    cwe = listOf("CWE-1104")
                )
            )
        }
        return findings
    }
}
