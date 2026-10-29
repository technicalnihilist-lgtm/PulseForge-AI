package com.pulseforge.security

import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.FindingCategory
import com.pulseforge.model.Severity

private const val MESSAGE_DIGEST = "Ljava/security/MessageDigest;"
private const val CIPHER = "Ljavax/crypto/Cipher;"
private const val SECURE_RANDOM = "Ljava/security/SecureRandom;"
private const val RANDOM = "Ljava/util/Random;"

/** MD5 / SHA-1 used as a security primitive. */
object WeakHashRule : SecurityRule {
    override val id = "PF-CRY-001"
    override val title = "Weak hash algorithm"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val digestUsed = ctx.occurrences(MESSAGE_DIGEST, "getInstance")
        if (digestUsed == 0) return emptyList()
        val weak = ctx.strings.hasExact("MD5", "SHA-1", "SHA1", "MD2", "MD4")
        if (weak.isEmpty()) return emptyList()
        val hasMd5 = weak.any { it.equals("MD5", true) }

        return listOf(
            finding(
                ruleId = id,
                title = if (hasMd5) "MD5 is used" else "SHA-1 is used",
                severity = if (hasMd5) Severity.HIGH else Severity.MEDIUM,
                confidence = Confidence.MEDIUM,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "MessageDigest.getInstance is called with ${weak.joinToString(", ")}.",
                detail = "Both MD5 and SHA-1 are broken for collision resistance and unsuitable for signatures, " +
                    "integrity checks or password storage. They remain acceptable only for non-security uses such as " +
                    "cache keys. Static analysis sees the algorithm name in the string pool and the getInstance call, " +
                    "but not the call path.",
                remediation = "Use SHA-256 (or SHA-3) for integrity, and a dedicated password KDF " +
                    "(PBKDF2 with 210k or more iterations, scrypt or Argon2) for passwords. Never hash passwords with a " +
                    "single fast digest.",
                evidence = buildList {
                    add(ev("MessageDigest.getInstance", "$digestUsed reference(s)", "DEX method table"))
                    weak.forEach { add(ev("Algorithm string", it, "DEX string pool")) }
                },
                references = listOf(References.cweWeakHash, References.masvsCrypto1),
                fpNote = "A string constant alone does not prove the algorithm is used for security; " +
                    "check the call site before treating this as confirmed.",
                masvs = listOf("MASVS-CRYPTO-1"),
                cwe = listOf("CWE-328")
            )
        )
    }
}

/** Broken or misconfigured ciphers. */
object WeakCipherRule : SecurityRule {
    override val id = "PF-CRY-002"
    override val title = "Weak cipher configuration"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val cipherUsed = ctx.occurrences(CIPHER, "getInstance")
        if (cipherUsed == 0) return emptyList()

        val weak = ctx.strings.hasExact(
            "AES/ECB/PKCS5Padding", "AES/ECB/NoPadding", "ECB", "DES", "DESede", "RC4", "Blowfish", "ARCFOUR"
        )
        if (weak.isEmpty()) return emptyList()

        val legacy = weak.filter {
            it.equals("DES", true) || it.equals("DESede", true) || it.equals("RC4", true) ||
                it.equals("ARCFOUR", true) || it.equals("Blowfish", true)
        }

        return listOf(
            finding(
                ruleId = id,
                title = if (legacy.isNotEmpty()) "Legacy cipher ${legacy.joinToString(", ")}" else "ECB block mode in use",
                severity = Severity.HIGH,
                confidence = Confidence.MEDIUM,
                evidenceType = EvidenceType.PATTERN_MATCH,
                summary = "Cipher.getInstance is called with ${weak.joinToString(", ")}.",
                detail = "ECB encrypts identical blocks identically, leaking structure (the classic ECB penguin). " +
                    "DES/3DES and RC4/ARCFOUR are deprecated, and Blowfish has a 64-bit block that is too small for " +
                    "large payloads. Static analysis sees the transformation string, not the key management around it.",
                remediation = "Use AES/GCM/NoPadding with a random 96-bit IV per message and a Keystore-backed key; " +
                    "for bulk data consider a vetted library (Tink) rather than raw JCE calls.",
                evidence = buildList {
                    add(ev("Cipher.getInstance", "$cipherUsed reference(s)", "DEX method table"))
                    weak.forEach { add(ev("Transformation", it, "DEX string pool")) }
                },
                references = listOf(References.cweWeakCrypto, References.masvsCrypto1),
                fpNote = "Some libraries declare transformation constants they never use.",
                masvs = listOf("MASVS-CRYPTO-1"),
                cwe = listOf("CWE-327")
            )
        )
    }
}

/** java.util.Random used where unpredictability matters. */
object InsecureRandomRule : SecurityRule {
    override val id = "PF-CRY-003"
    override val title = "Non-cryptographic random source"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val randomUsed = ctx.occurrences(RANDOM, "nextInt") + ctx.occurrences(RANDOM, "nextBytes") +
            ctx.occurrences(RANDOM, "nextLong") + ctx.occurrences(RANDOM, "<init>")
        val secureUsed = ctx.occurrences(SECURE_RANDOM, "nextBytes") + ctx.occurrences(SECURE_RANDOM, "<init>")
        if (randomUsed == 0 || secureUsed > 0) return emptyList()

        return listOf(
            finding(
                ruleId = id,
                title = "java.util.Random used without SecureRandom",
                severity = Severity.LOW,
                confidence = Confidence.LOW,
                evidenceType = EvidenceType.INFERENCE,
                summary = "The app references java.util.Random and never SecureRandom.",
                detail = "java.util.Random is a linear congruential generator; its output is predictable from a few " +
                    "samples. That is harmless for animations and test data, and serious for tokens, IVs, salts or " +
                    "one-time codes. Without bytecode analysis PulseForge cannot tell which use applies here.",
                remediation = "Use java.security.SecureRandom for anything security-relevant; keep Random for UI jitter.",
                evidence = buildList {
                    add(ev("java.util.Random", "$randomUsed reference(s)", "DEX method table"))
                    add(ev("SecureRandom", "not referenced", "DEX method table"))
                },
                references = listOf(References.cweWeakRandom, References.masvsCrypto1),
                fpNote = "Very likely a false positive if the only use is UI or simulation.",
                masvs = listOf("MASVS-CRYPTO-1"),
                cwe = listOf("CWE-330")
            )
        )
    }
}

/** Hardcoded credentials and API keys in code, resources or assets. */
object SecretsRule : SecurityRule {
    override val id = "PF-SEC-001"
    override val title = "Hardcoded secrets"

    private data class Pattern(
        val id: String,
        val label: String,
        val severity: Severity,
        val confidence: Confidence,
        val regex: Regex,
        val explanation: String,
        val remediation: String,
        val cwe: String
    )

    private val patterns = listOf(
        Pattern(
            id = "AWS",
            label = "AWS access key id",
            severity = Severity.CRITICAL,
            confidence = Confidence.HIGH,
            regex = Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b"""),
            explanation = "An AWS access key id was found. Paired with its secret it grants programmatic access to the account.",
            remediation = "Revoke the key in IAM, move credentials to a backend service, and never ship them in an APK. " +
                "An APK can always be decompiled, so any embedded credential is public.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "GOOGLE",
            label = "Google API key",
            severity = Severity.HIGH,
            confidence = Confidence.MEDIUM,
            regex = Regex("""\bAIza[0-9A-Za-z_\-]{30,45}\b"""),
            explanation = "A Google API key was found. Android API keys are often restricted by package name and " +
                "certificate, but an unrestricted key can be abused for billable quota.",
            remediation = "Restrict the key in Google Cloud Console to this package and signing certificate, " +
                "or proxy the call through your backend.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "STRIPE",
            label = "Stripe secret key",
            severity = Severity.CRITICAL,
            confidence = Confidence.HIGH,
            regex = Regex("""\bsk_live_[0-9A-Za-z]{16,}\b"""),
            explanation = "A live Stripe secret key was found; it allows charges and refunds on the account.",
            remediation = "Roll the key immediately and move payment operations to a server.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "PRIVATE_KEY",
            label = "Private key material",
            severity = Severity.CRITICAL,
            confidence = Confidence.HIGH,
            regex = Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"""),
            explanation = "A PEM private key block was found inside the APK.",
            remediation = "Treat the key as compromised, rotate it, and keep private keys server-side or in a " +
                "hardware-backed Keystore.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "SLACK",
            label = "Slack token",
            severity = Severity.HIGH,
            confidence = Confidence.HIGH,
            regex = Regex("""\bxox[baprs]-[0-9A-Za-z\-]{10,}\b"""),
            explanation = "A Slack API token was found.",
            remediation = "Revoke the token in Slack admin settings and move integration calls to a backend.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "BASIC_AUTH",
            label = "Credentials embedded in a URL",
            severity = Severity.HIGH,
            confidence = Confidence.MEDIUM,
            regex = Regex("""\b[a-z][a-z0-9+.\-]{2,10}://[^/\s:@]{1,64}:[^/\s:@]{1,64}@[A-Za-z0-9.\-]+"""),
            explanation = "A URL contains userinfo (user:password@host). Credentials in URLs are logged by proxies, " +
                "servers and crash reports.",
            remediation = "Use an Authorization header with a token obtained at runtime.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "JWT",
            label = "JSON Web Token",
            severity = Severity.MEDIUM,
            confidence = Confidence.MEDIUM,
            regex = Regex("""\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"""),
            explanation = "A JWT was found hardcoded. Even a long-lived token in an APK is a credential leak.",
            remediation = "Issue short-lived tokens at runtime and keep refresh tokens in Keystore-backed storage.",
            cwe = "CWE-798"
        ),
        Pattern(
            id = "GENERIC",
            label = "Assignment that looks like a credential",
            severity = Severity.MEDIUM,
            confidence = Confidence.LOW,
            regex = Regex(
                """(?i)\b(api[_-]?key|apikey|client[_-]?secret|secret[_-]?key|access[_-]?key|auth[_-]?token|password|passwd)\b\s*[:=]\s*["']?[A-Za-z0-9_\-+/=]{10,}["']?"""
            ),
            explanation = "A key/value pair that looks like a credential was found in the app's strings or assets.",
            remediation = "Confirm whether the value is a real secret. If it is, remove it from the client and " +
                "rotate it. If it is a placeholder or a public identifier, ignore this finding.",
            cwe = "CWE-798"
        )
    )

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (pattern in patterns) {
            val matches = ctx.strings.regexMatches(pattern.regex, limit = 6)
            if (matches.isEmpty()) continue

            val evidence = matches.map { (source, value) ->
                val masked = if (pattern.id == "BASIC_AUTH") Redactor.maskUrlSecrets(value.take(200))
                else Redactor.mask(value.take(200))
                ev(pattern.label, masked, source)
            }

            findings.add(
                finding(
                    ruleId = "${id}-${pattern.id}",
                    title = "${pattern.label} found",
                    severity = pattern.severity,
                    confidence = pattern.confidence,
                    evidenceType = EvidenceType.PATTERN_MATCH,
                    summary = "${matches.size} potential ${pattern.label.lowercase()} value(s) were found in the app.",
                    detail = pattern.explanation + " Values are masked in this report; open the app on your own " +
                        "machine if you need to confirm the exact string. PulseForge never uploads these values.",
                    remediation = pattern.remediation,
                    category = FindingCategory.PRIVACY,
                    evidence = evidence,
                    references = listOf(References.cweHardcoded, References.masvsStorage1),
                    fpNote = if (pattern.id == "GENERIC")
                        "Pattern matches on names are noisy: test fixtures, empty placeholders and public ids all match."
                    else "A random-looking string can coincidentally match the pattern; verify before rotating.",
                    masvs = listOf("MASVS-STORAGE-1", "MASVS-CRYPTO-1"),
                    cwe = listOf(pattern.cwe)
                )
            )
        }
        return findings
    }
}
