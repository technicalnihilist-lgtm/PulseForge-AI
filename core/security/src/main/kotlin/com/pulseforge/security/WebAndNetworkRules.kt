package com.pulseforge.security

import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.Severity

private const val WEB_SETTINGS = "Landroid/webkit/WebSettings;"
private const val WEB_VIEW = "Landroid/webkit/WebView;"
private const val WEB_VIEW_CLIENT = "Landroid/webkit/WebViewClient;"
private const val SSL_HANDLER = "Landroid/webkit/SslErrorHandler;"

/** WebView configuration that exposes the app to injected or malicious content. */
object WebViewRules : SecurityRule {
    override val id = "PF-WEB-001"
    override val title = "WebView configuration"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val findings = mutableListOf<Finding>()

        val jsEnabled = ctx.occurrences(WEB_SETTINGS, "setJavaScriptEnabled")
        val jsInterface = ctx.occurrences(WEB_VIEW, "addJavascriptInterface")
        val fileAccess = ctx.occurrences(WEB_SETTINGS, "setAllowFileAccess")
        val universalAccess = ctx.occurrences(WEB_SETTINGS, "setAllowUniversalAccessFromFileURLs")
        val fileUrlAccess = ctx.occurrences(WEB_SETTINGS, "setAllowFileAccessFromFileURLs")
        val savePassword = ctx.occurrences(WEB_SETTINGS, "setSavePassword")
        val multiWindow = ctx.occurrences(WEB_SETTINGS, "setSupportMultipleWindows")

        if (jsInterface > 0) {
            val combined = jsEnabled > 0
            findings.add(
                finding(
                    ruleId = "$id-JSI",
                    title = "JavaScript bridge exposed to web content",
                    severity = if (combined) Severity.CRITICAL else Severity.HIGH,
                    confidence = Confidence.HIGH,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "addJavascriptInterface() is used" +
                        if (combined) ", and JavaScript is enabled, so loaded pages can call into app code." else ".",
                    detail = "addJavascriptInterface exposes every method annotated with @JavascriptInterface to any page " +
                        "loaded in that WebView. If the WebView can reach attacker-controlled or user-supplied content " +
                        "(deep links, ads, in-app browsers), that content can invoke those methods. " +
                        "The analyser sees the API reference, not which URL is loaded at runtime.",
                    remediation = "Remove the bridge where possible. Otherwise load only first-party HTTPS URLs, " +
                        "validate the origin before exposing objects, keep @JavascriptInterface methods minimal and " +
                        "side-effect free, and never pass user data through the bridge unsanitised.",
                    evidence = buildList {
                        add(ev("addJavascriptInterface", "$jsInterface reference(s)", "DEX method table"))
                        add(ev("setJavaScriptEnabled", "$jsEnabled reference(s)", "DEX method table"))
                    },
                    references = listOf(References.cweExposedMethod, References.masvsPlatform2),
                    fpNote = "Apps that only load their own bundled assets through the bridge may accept this risk.",
                    masvs = listOf("MASVS-PLATFORM-2"),
                    cwe = listOf("CWE-749")
                )
            )
        }

        if (universalAccess > 0 || fileUrlAccess > 0) {
            findings.add(
                finding(
                    ruleId = "$id-FILE",
                    title = "WebView allows file pages to reach other origins",
                    severity = Severity.HIGH,
                    confidence = Confidence.HIGH,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "setAllowUniversalAccessFromFileURLs or setAllowFileAccessFromFileURLs is referenced, " +
                        "letting a local HTML file read arbitrary local files and send them out.",
                    detail = "When a file page can make cross-origin requests, an attacker who can plant or influence " +
                        "one HTML file (downloaded content, shared storage, a malicious intent) can read the app's " +
                        "private files and exfiltrate them.",
                    remediation = "Remove both calls (they default to false on modern WebView) and serve local content " +
                        "through WebViewAssetLoader over an https origin instead of file.",
                    evidence = buildList {
                        if (universalAccess > 0) add(ev("setAllowUniversalAccessFromFileURLs", "$universalAccess reference(s)", "DEX method table"))
                        if (fileUrlAccess > 0) add(ev("setAllowFileAccessFromFileURLs", "$fileUrlAccess reference(s)", "DEX method table"))
                    },
                    references = listOf(References.cweFileAccess, References.masvsPlatform2),
                    masvs = listOf("MASVS-PLATFORM-2"),
                    cwe = listOf("CWE-552")
                )
            )
        }

        if (fileAccess > 0) {
            findings.add(
                finding(
                    ruleId = "$id-ACCESS",
                    title = "WebView file access enabled",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "setAllowFileAccess is referenced, which lets WebView content read local files.",
                    detail = "File access is enabled by default up to API 29 and can expose app-private files to loaded " +
                        "content. The reference may also be a call that disables it, so confirm the argument in source.",
                    remediation = "Call setAllowFileAccess(false) unless a local-file feature requires it.",
                    evidence = listOf(ev("setAllowFileAccess", "$fileAccess reference(s)", "DEX method table")),
                    references = listOf(References.cweFileAccess, References.masvsPlatform2),
                    fpNote = "The call could be setAllowFileAccess(false); the argument is not visible in the method table.",
                    masvs = listOf("MASVS-PLATFORM-2"),
                    cwe = listOf("CWE-552")
                )
            )
        }

        if (savePassword > 0) {
            findings.add(
                finding(
                    ruleId = "$id-SAVEPW",
                    title = "Deprecated WebView password saving referenced",
                    severity = Severity.LOW,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "setSavePassword is referenced; the API is deprecated and no longer stores passwords securely.",
                    detail = "setSavePassword was deprecated in API 18 and is a no-op on modern WebView. Its presence " +
                        "suggests legacy code paths that may not have been reviewed for modern WebView behaviour.",
                    remediation = "Remove the call and rely on the platform credential provider or your own secure storage.",
                    evidence = listOf(ev("setSavePassword", "$savePassword reference(s)", "DEX method table")),
                    references = listOf(References.masvsPlatform2),
                    masvs = listOf("MASVS-PLATFORM-2")
                )
            )
        }

        if (multiWindow > 0) {
            findings.add(
                finding(
                    ruleId = "$id-MULTIWINDOW",
                    title = "WebView multiple windows enabled",
                    severity = Severity.LOW,
                    confidence = Confidence.LOW,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "setSupportMultipleWindows is referenced, which can open a second window that bypasses " +
                        "the client's URL checks.",
                    detail = "With multiple windows, popups are created without going through shouldOverrideUrlLoading, " +
                        "so origin restrictions applied there do not cover the new window.",
                    remediation = "Keep multiple windows disabled, or implement onCreateWindow with an allow-list.",
                    evidence = listOf(ev("setSupportMultipleWindows", "$multiWindow reference(s)", "DEX method table")),
                    references = listOf(References.masvsPlatform2),
                    masvs = listOf("MASVS-PLATFORM-2")
                )
            )
        }

        return findings
    }
}

/** SSL errors that the app ignores, and custom trust managers. */
object TlsValidationRules : SecurityRule {
    override val id = "PF-TLS-001"
    override val title = "TLS certificate validation"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val findings = mutableListOf<Finding>()
        val proceed = ctx.occurrences(SSL_HANDLER, "proceed")
        val onReceivedSslError = ctx.occurrences(WEB_VIEW_CLIENT, "onReceivedSslError")
        val trustManager = ctx.occurrences("Ljavax/net/ssl/X509TrustManager;", "checkServerTrusted")
        val hostnameVerifier = ctx.occurrences("Ljavax/net/ssl/HostnameVerifier;", "verify")
        val allHosts = ctx.strings.containsAny("ALLOW_ALL_HOSTNAME_VERIFIER", "AllowAllHostnameVerifier")

        if (proceed > 0 || onReceivedSslError > 0) {
            findings.add(
                finding(
                    ruleId = "$id-SSLERR",
                    title = "WebView SSL errors may be ignored",
                    severity = Severity.HIGH,
                    confidence = if (proceed > 0) Confidence.MEDIUM else Confidence.LOW,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "onReceivedSslError or SslErrorHandler.proceed is referenced, the classic pattern for " +
                        "clicking through certificate errors.",
                    detail = "Calling handler.proceed() inside onReceivedSslError accepts any certificate, which turns TLS " +
                        "into an integrity-free channel and enables interception. The reference alone does not prove " +
                        "proceed() is reached; review the callback.",
                    remediation = "Always call handler.cancel() (or simply do not override the callback).",
                    evidence = buildList {
                        if (onReceivedSslError > 0) add(ev("onReceivedSslError", "$onReceivedSslError reference(s)", "DEX method table"))
                        if (proceed > 0) add(ev("SslErrorHandler.proceed", "$proceed reference(s)", "DEX method table"))
                    },
                    references = listOf(References.cweCertValidation, References.masvsNetwork1),
                    fpNote = "A cancel-only implementation also references these APIs; confirm the call path.",
                    masvs = listOf("MASVS-NETWORK-1"),
                    cwe = listOf("CWE-295")
                )
            )
        }

        if (trustManager > 0) {
            findings.add(
                finding(
                    ruleId = "$id-TRUST",
                    title = "Custom X509TrustManager implementation",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "The app implements checkServerTrusted itself, which often means certificates are accepted " +
                        "without proper validation.",
                    detail = "A custom trust manager is legitimate for pinning or for trusting a private CA, but the most " +
                        "common variant returns without checking anything. Static analysis cannot see the body here, " +
                        "so this is a pointer for review, not a confirmed defect.",
                    remediation = "Delegate to the platform trust manager and add pinning on top (network security config " +
                        "or CertificatePinner) rather than reimplementing validation.",
                    evidence = buildList {
                        add(ev("checkServerTrusted", "$trustManager reference(s)", "DEX method table"))
                        if (hostnameVerifier > 0) add(ev("HostnameVerifier.verify", "$hostnameVerifier reference(s)", "DEX method table"))
                        if (allHosts) add(ev("ALLOW_ALL_HOSTNAME_VERIFIER", "present in string pool", "DEX string pool"))
                    },
                    references = listOf(References.cweCertValidation, References.masvsNetwork1),
                    fpNote = "Pinning implementations legitimately implement this interface.",
                    masvs = listOf("MASVS-NETWORK-1"),
                    cwe = listOf("CWE-295")
                )
            )
        }

        if (allHosts && hostnameVerifier == 0) {
            findings.add(
                finding(
                    ruleId = "$id-HOSTNAME",
                    title = "Hostname verification disabled",
                    severity = Severity.HIGH,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "An allow-all hostname verifier is referenced.",
                    detail = "Disabling hostname verification means any certificate valid for any domain is accepted for " +
                        "your endpoint, defeating the point of TLS.",
                    remediation = "Use the default hostname verifier; add pinning instead.",
                    evidence = listOf(ev("ALLOW_ALL_HOSTNAME_VERIFIER", "present", "DEX string pool")),
                    references = listOf(References.cweCertValidation, References.masvsNetwork1),
                    masvs = listOf("MASVS-NETWORK-1"),
                    cwe = listOf("CWE-295")
                )
            )
        }
        return findings
    }
}

/** Certificate pinning status. Pinning is a recommendation, not a defect, so this is informational. */
object PinningRule : SecurityRule {
    override val id = "PF-NET-002"
    override val title = "Certificate pinning"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val pinningSignals = ctx.occurrences("Lokhttp3/CertificatePinner;", "<init>") +
            ctx.occurrences("Lokhttp3/CertificatePinner;", "Builder")
        val nscPins = ctx.anyXml { it.contains("pin-set", ignoreCase = true) || it.contains("<pin ", ignoreCase = true) }
        if (pinningSignals > 0 || nscPins != null) return emptyList()

        return listOf(
            finding(
                ruleId = id,
                title = "No certificate pinning detected",
                severity = Severity.INFO,
                confidence = Confidence.MEDIUM,
                evidenceType = EvidenceType.INFERENCE,
                summary = "No pinning configuration was found. Traffic relies on the device trust store alone.",
                detail = "Pinning is defence in depth against a compromised or user-installed root CA. Its absence is not " +
                    "a vulnerability, and the analyser cannot see pinning implemented inside a bundled network SDK.",
                remediation = "For high-value endpoints, add pinning through a network security config pin-set or " +
                    "OkHttp CertificatePinner, and plan a pin rotation strategy before shipping.",
                evidence = listOf(
                    ev("CertificatePinner", "not referenced", "DEX method table"),
                    ev("network security config", "no pin-set found", "res/xml")
                ),
                references = listOf(References.masvsNetwork2),
                fpNote = "Absence of evidence in the DEX is not evidence of absence: SDK-internal pinning is invisible here.",
                masvs = listOf("MASVS-NETWORK-2")
            )
        )
    }
}

/** Cleartext URLs hardcoded in code or resources. */
object CleartextUrlRule : SecurityRule {
    override val id = "PF-NET-003"
    override val title = "Hardcoded cleartext URLs"

    private val httpUrl = Regex("""\bhttp://[A-Za-z0-9][A-Za-z0-9.\-]*\.[A-Za-z]{2,}[^\s"'<>]*""")

    override fun evaluate(ctx: RuleContext): List<Finding> {
        // Report only the matched URL, never the whole string that contains it: a surrounding
        // JSON blob or query string could carry a credential.
        val matches = ctx.strings.regexMatches(httpUrl, limit = 12)
            .mapNotNull { (source, value) -> httpUrl.find(value)?.value?.let { url -> source to url } }
            .filterNot { (_, url) ->
                url.contains("schemas.android.com") || url.contains("xmlns") ||
                    url.contains("w3.org") || url.contains("localhost") || url.contains("127.0.0.1")
            }
        if (matches.isEmpty()) return emptyList()

        return listOf(
            finding(
                ruleId = id,
                title = "Cleartext endpoint(s) in the app",
                severity = Severity.MEDIUM,
                confidence = Confidence.HIGH,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "The app contains ${matches.size} hardcoded http endpoint(s).",
                detail = "Plain-HTTP endpoints carry no confidentiality or integrity. Some may be documentation or " +
                    "unused constants, which is why each match is listed with its source.",
                remediation = "Move endpoints to https and, where a legacy host cannot be upgraded, document the " +
                    "exception in the network security config.",
                evidence = matches.map { (source, url) -> ev("http URL", Redactor.maskUrlSecrets(url.take(200)), source) },
                references = listOf(References.cweCleartext, References.masvsNetwork1),
                fpNote = "Some matches are namespaces or comments rather than live endpoints.",
                masvs = listOf("MASVS-NETWORK-1"),
                cwe = listOf("CWE-319")
            )
        )
    }
}
