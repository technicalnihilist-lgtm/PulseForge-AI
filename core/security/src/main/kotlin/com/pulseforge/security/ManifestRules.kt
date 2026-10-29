package com.pulseforge.security

import com.pulseforge.model.ComponentType
import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.FindingCategory
import com.pulseforge.model.Severity

private const val LAUNCHER = "android.intent.category.LAUNCHER"
private const val BROWSABLE = "android.intent.category.BROWSABLE"

/** Exported components that are not protected by a permission. */
object ExportedComponentsRule : SecurityRule {
    override val id = "PF-MAN-001"
    override val title = "Exported components"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        val target = manifest.targetSdk
        val findings = mutableListOf<Finding>()
        var suppressed = 0

        for (component in manifest.components) {
            if (!component.enabled) continue
            if (!component.effectiveExported(target)) continue
            if (component.permission != null) continue
            if (component.type == ComponentType.ACTIVITY &&
                component.intentFilters.any { filter -> filter.categories.contains(LAUNCHER) }
            ) continue

            if (findings.size >= 15) { suppressed++; continue }

            val implicit = component.exported == null
            val severity = when (component.type) {
                ComponentType.PROVIDER, ComponentType.SERVICE, ComponentType.RECEIVER -> Severity.HIGH
                ComponentType.ACTIVITY -> if (component.hasIntentFilter) Severity.HIGH else Severity.MEDIUM
            }
            val exportSource = if (implicit) {
                "implicit: an intent filter is present and targetSdk ${target ?: "<unknown>"} < 31"
            } else {
                "android:exported=\"true\""
            }

            findings.add(
                finding(
                    ruleId = id,
                    title = "Exported ${component.type.name.lowercase()}: ${component.shortName}",
                    severity = severity,
                    confidence = if (implicit) Confidence.HIGH else Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "${component.type.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                        "${component.name} can be started by any app on the device, and no permission guards it.",
                    detail = buildString {
                        append("The component is exported ($exportSource) and declares no android:permission. ")
                        append("Any installed app can send it intents")
                        if (component.type == ComponentType.PROVIDER) {
                            append(" or query its content provider at ${component.authorities ?: "its authority"}")
                        }
                        append(". Whether that is exploitable depends on what the component does with the data it receives; ")
                        append("PulseForge confirms the exposure, not the impact.")
                    },
                    remediation = "If external access is not required, set android:exported=\"false\". " +
                        "If it is required, protect the component with a signature-level custom permission " +
                        "and validate every field of the incoming Intent (action, data, extras).",
                    affected = component.name,
                    evidence = buildList {
                        add(ev("Component", component.name, "AndroidManifest.xml"))
                        add(ev("Type", component.type.name, "AndroidManifest.xml"))
                        add(ev("Exported", exportSource, "AndroidManifest.xml"))
                        add(ev("Permission", "none", "AndroidManifest.xml"))
                        component.intentFilters.firstOrNull()?.let { add(ev("Intent filter", it.summary, "AndroidManifest.xml")) }
                        component.authorities?.let { add(ev("Authorities", it, "AndroidManifest.xml")) }
                    },
                    references = listOf(References.cweExport, References.masvsPlatform1),
                    fpNote = "If the component validates its input strictly and returns no sensitive data, " +
                        "the exposure may be intentional and low risk.",
                    masvs = listOf("MASVS-PLATFORM-1"),
                    cwe = listOf("CWE-926")
                )
            )
        }

        if (suppressed > 0) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "$suppressed further exported components",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "There are $suppressed more exported components without a permission.",
                    detail = "The list was truncated at 15 entries. Review the manifest inspector for the full list.",
                    remediation = "Audit every exported component and set android:exported=\"false\" where external access is not needed.",
                    references = listOf(References.cweExport, References.masvsPlatform1),
                    idSuffix = "more"
                )
            )
        }
        return findings
    }
}

/** Deep links that another app can intercept or that expose an attack surface. */
object DeepLinkRule : SecurityRule {
    override val id = "PF-MAN-002"
    override val title = "Risky intent filters / deep links"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        val findings = mutableListOf<Finding>()
        val target = manifest.targetSdk

        for (component in manifest.components) {
            if (!component.effectiveExported(target)) continue
            for (filter in component.intentFilters) {
                val browsable = filter.categories.contains(BROWSABLE)
                val viewAction = filter.actions.contains("android.intent.action.VIEW")
                if (!browsable && !viewAction) continue

                val webScheme = filter.schemes.any { it.equals("http", true) || it.equals("https", true) }
                val wildcardHost = filter.hosts.any { it == "*" }
                val customScheme = filter.schemes.any { !it.equals("http", true) && !it.equals("https", true) }
                if (!webScheme && !customScheme) continue

                val severity = when {
                    webScheme && wildcardHost -> Severity.HIGH
                    webScheme && !filter.autoVerify -> Severity.HIGH
                    webScheme -> Severity.MEDIUM
                    else -> Severity.MEDIUM
                }

                val detail = buildString {
                    append("The filter accepts ${filter.schemes.joinToString(", ")}")
                    if (filter.hosts.isNotEmpty()) append(" for hosts ${filter.hosts.joinToString(", ")}")
                    append(". ")
                    if (webScheme && !filter.autoVerify) {
                        append("No android:autoVerify is declared, so any app can register the same http(s) link ")
                        append("and receive it first (link hijacking). ")
                    }
                    if (wildcardHost) append("A wildcard host accepts links for every domain. ")
                    if (customScheme) append("Custom schemes are reachable by any app that knows the scheme. ")
                    append("Every field of the incoming URI must be treated as untrusted input.")
                }

                findings.add(
                    finding(
                        ruleId = id,
                        title = "Deep link surface on ${component.shortName}",
                        severity = severity,
                        confidence = Confidence.CONFIRMED,
                        evidenceType = EvidenceType.DIRECT_OBSERVATION,
                        summary = "${component.shortName} handles external links (${filter.schemes.joinToString(", ")}) that other apps can trigger.",
                        detail = detail,
                        remediation = "Prefer App Links with android:autoVerify=\"true\" and a Digital Asset Links file, " +
                            "avoid wildcard hosts, and validate/allow-list every path parameter before use.",
                        affected = component.name,
                        evidence = buildList {
                            add(ev("Intent filter", filter.summary, "AndroidManifest.xml"))
                            add(ev("autoVerify", filter.autoVerify.toString(), "AndroidManifest.xml"))
                        },
                        references = listOf(References.cweIntentFilter, References.masvsPlatform1),
                        fpNote = "Deep links are a feature, not a defect. The finding flags missing verification, " +
                            "not the existence of the link itself.",
                        masvs = listOf("MASVS-PLATFORM-1"),
                        cwe = listOf("CWE-925")
                    )
                )
            }
        }
        return findings
    }
}

/** Content providers that anyone can query. */
object ExportedProviderRule : SecurityRule {
    override val id = "PF-MAN-003"
    override val title = "Unprotected content provider"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        return manifest.components
            .filter { it.type == ComponentType.PROVIDER && it.effectiveExported(manifest.targetSdk) }
            .filter { it.readPermission == null && it.writePermission == null && it.permission == null }
            .map { provider ->
                finding(
                    ruleId = id,
                    title = "Content provider ${provider.shortName} is open",
                    severity = Severity.HIGH,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "Any app can query or modify ${provider.authorities ?: provider.name} because no read/write permission is set.",
                    detail = "The provider is exported and declares no android:readPermission, android:writePermission " +
                        "or android:permission. On Android 11+ a provider still requires explicit exported=\"true\"; " +
                        "that requirement does not restrict who may query it. Path traversal in openFile() and " +
                        "unparameterised query() calls are the usual ways this becomes exploitable.",
                    remediation = "Set android:exported=\"false\" unless another app truly needs the provider. " +
                        "Otherwise require a signature permission, validate the URI path segments, and use " +
                        "parameterised queries instead of string concatenation.",
                    affected = provider.name,
                    evidence = listOf(
                        ev("Provider", provider.name, "AndroidManifest.xml"),
                        ev("Authorities", provider.authorities ?: "unset", "AndroidManifest.xml"),
                        ev("Read permission", "none", "AndroidManifest.xml"),
                        ev("Write permission", "none", "AndroidManifest.xml")
                    ),
                    references = listOf(References.cweExport, References.cwePathTraversal, References.masvsPlatform1),
                    fpNote = "Providers that only expose non-sensitive, read-only data may accept this risk deliberately.",
                    masvs = listOf("MASVS-PLATFORM-1"),
                    cwe = listOf("CWE-926", "CWE-22")
                )
            }
    }
}

/** Custom permissions declared with a weak protection level. */
object CustomPermissionRule : SecurityRule {
    override val id = "PF-MAN-004"
    override val title = "Weakly protected custom permission"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        return manifest.declaredPermissions.mapNotNull { permission ->
            val level = permission.protectionLevel?.lowercase()
            val weak = level == null || level.contains("normal") || level.contains("dangerous")
            if (!weak) return@mapNotNull null
            finding(
                ruleId = id,
                title = "Custom permission ${permission.name.substringAfterLast('.')} is not signature-level",
                severity = Severity.MEDIUM,
                confidence = Confidence.CONFIRMED,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = "The app defines ${permission.name} with protectionLevel ${level ?: "unset (normal)"}, so any app can request it.",
                detail = "A custom permission protects a component only if other apps cannot obtain it. " +
                    "With protectionLevel normal or dangerous, any app that declares a uses-permission for it " +
                    "receives it without user action.",
                remediation = "Declare the permission with android:protectionLevel=\"signature\" so only apps signed " +
                    "with the same key can hold it.",
                affected = permission.name,
                evidence = listOf(
                    ev("Permission", permission.name, "AndroidManifest.xml"),
                    ev("protectionLevel", level ?: "unset (defaults to normal)", "AndroidManifest.xml")
                ),
                references = listOf(References.cweExport, References.masvsPlatform1),
                masvs = listOf("MASVS-PLATFORM-1"),
                cwe = listOf("CWE-926")
            )
        }
    }
}

/** Debuggable release build. */
object DebuggableRule : SecurityRule {
    override val id = "PF-MAN-005"
    override val title = "Debuggable build"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        if (manifest.debuggable != true) return emptyList()
        return listOf(
            finding(
                ruleId = id,
                title = "The APK is debuggable",
                severity = Severity.CRITICAL,
                confidence = Confidence.CONFIRMED,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = "android:debuggable=\"true\" lets anyone with a USB connection attach a debugger, " +
                    "read app-private files and run code in the app's process.",
                detail = "With a debuggable build, run-as ${manifest.packageName} grants access to the app's " +
                    "private data directory, and JDWP allows arbitrary method invocation inside the process. " +
                    "This is normal for a debug variant and unacceptable for anything distributed to users.",
                remediation = "Build release variants with android:debuggable=\"false\" (AGP does this automatically for " +
                    "release builds). Verify with aapt2 dump xmltree app.apk --file AndroidManifest.xml.",
                evidence = listOf(
                    ev("android:debuggable", "true", "AndroidManifest.xml"),
                    ev("Package", manifest.packageName, "AndroidManifest.xml"),
                    ev("Build hint", "debuggable is set explicitly in the manifest", "AndroidManifest.xml")
                ),
                references = listOf(References.cweDebugCode, References.masvsCode4),
                fpNote = "If this APK is your own debug build, this is expected and not a vulnerability.",
                masvs = listOf("MASVS-CODE-4", "MASVS-RESILIENCE-1"),
                cwe = listOf("CWE-489")
            )
        )
    }
}

/** Test-only flag. */
object TestOnlyRule : SecurityRule {
    override val id = "PF-MAN-006"
    override val title = "Test-only build"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        if (manifest.testOnly != true) return emptyList()
        return listOf(
            finding(
                ruleId = id,
                title = "The APK is marked testOnly",
                severity = Severity.LOW,
                confidence = Confidence.CONFIRMED,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = "android:testOnly=\"true\" marks this as a build that should not be distributed.",
                detail = "Test-only APKs cannot be installed through the Play Store and are rejected by some device " +
                    "management policies. They are usually artefacts of adb install -t workflows.",
                remediation = "Ship a normal release build without the testOnly flag.",
                evidence = listOf(ev("android:testOnly", "true", "AndroidManifest.xml")),
                references = listOf(References.masvsCode4),
                category = FindingCategory.QUALITY,
                masvs = listOf("MASVS-CODE-4")
            )
        )
    }
}

/** Backup / data extraction configuration. */
object BackupRule : SecurityRule {
    override val id = "PF-MAN-007"
    override val title = "Backup configuration"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        val allowBackup = manifest.allowBackup
        val hasRules = manifest.dataExtractionRules != null || manifest.fullBackupContent != null
        if (allowBackup == false) return emptyList()

        return listOf(
            finding(
                ruleId = id,
                title = if (allowBackup == true) "App data can be backed up" else "App data is backed up by default",
                severity = if (allowBackup == true) Severity.HIGH else Severity.MEDIUM,
                confidence = if (allowBackup == true) Confidence.CONFIRMED else Confidence.MEDIUM,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = if (allowBackup == true) {
                    "android:allowBackup=\"true\" lets app-private data leave the device through adb backup or cloud backup."
                } else {
                    "android:allowBackup is not set; the platform default is true, so app data can be backed up."
                },
                detail = "Backups copy the app's private files, databases and shared preferences off the device. " +
                    "On Android 12+ adb backup is restricted, but cloud backup still applies. If the app stores " +
                    "tokens, keys or personal data without its own encryption, that data travels with the backup. " +
                    (if (hasRules) "Backup rules are declared; verify they exclude sensitive directories."
                    else "No backup rules are declared."),
                remediation = "Set android:allowBackup=\"false\" for apps that hold sensitive state, or provide " +
                    "android:dataExtractionRules (Android 12+) and android:fullBackupContent (older) that exclude " +
                    "credential stores, and encrypt sensitive values with a Keystore-backed key.",
                evidence = buildList {
                    add(ev("android:allowBackup", allowBackup?.toString() ?: "unset (default true)", "AndroidManifest.xml"))
                    manifest.dataExtractionRules?.let { add(ev("dataExtractionRules", it, "AndroidManifest.xml")) }
                    manifest.fullBackupContent?.let { add(ev("fullBackupContent", it, "AndroidManifest.xml")) }
                },
                references = listOf(References.cweCleartextStorage, References.masvsStorage1),
                fpNote = "An app that keeps no sensitive data in private storage gains little from disabling backup.",
                masvs = listOf("MASVS-STORAGE-1"),
                cwe = listOf("CWE-312")
            )
        )
    }
}

/** Cleartext HTTP traffic. */
object CleartextTrafficRule : SecurityRule {
    override val id = "PF-MAN-008"
    override val title = "Cleartext network traffic"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        val flag = manifest.usesCleartextTraffic
        val target = manifest.targetSdk ?: 1
        val nscRestricts = ctx.anyXml { it.contains("cleartextTrafficPermitted=\"false\"", ignoreCase = true) } != null
        val nscPermits = ctx.anyXml { it.contains("cleartextTrafficPermitted=\"true\"", ignoreCase = true) } != null

        val enabled = flag == true || (flag == null && target < 28)
        if (!enabled) return emptyList()

        val severity = when {
            nscRestricts && flag != true -> Severity.LOW
            nscPermits -> Severity.HIGH
            else -> Severity.HIGH
        }

        return listOf(
            finding(
                ruleId = id,
                title = "Cleartext (HTTP) traffic is allowed",
                severity = severity,
                confidence = if (flag == true) Confidence.CONFIRMED else Confidence.HIGH,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = "The app permits unencrypted HTTP connections, so traffic can be read or modified on the network.",
                detail = buildString {
                    if (flag == true) append("android:usesCleartextTraffic=\"true\" is declared. ")
                    else append("usesCleartextTraffic is unset and targetSdk $target predates the secure default (API 28), so cleartext is allowed. ")
                    if (nscPermits) append("A network security config explicitly permits cleartext for some domains. ")
                    if (nscRestricts) append("A network security config sets cleartextTrafficPermitted=\"false\"; the global flag may be overridden per domain. ")
                    append("Without TLS, credentials, tokens and personal data are visible to anyone on the same network.")
                },
                remediation = "Remove android:usesCleartextTraffic or set it to false, add a network security config with " +
                    "a base-config that sets cleartextTrafficPermitted=\"false\", and migrate remaining endpoints to HTTPS.",
                evidence = buildList {
                    add(ev("usesCleartextTraffic", flag?.toString() ?: "unset", "AndroidManifest.xml"))
                    add(ev("targetSdk", target.toString(), "AndroidManifest.xml"))
                    if (nscRestricts) add(ev("network security config", "cleartextTrafficPermitted=\"false\" found", "res/xml"))
                    if (nscPermits) add(ev("network security config", "cleartextTrafficPermitted=\"true\" found", "res/xml"))
                },
                references = listOf(References.cweCleartext, References.masvsNetwork1),
                fpNote = "A cleartext exception for a captive-portal or local-device flow can be a deliberate, scoped decision.",
                masvs = listOf("MASVS-NETWORK-1"),
                cwe = listOf("CWE-319")
            )
        )
    }
}

/** Legacy shared-storage access. */
object LegacyStorageRule : SecurityRule {
    override val id = "PF-MAN-009"
    override val title = "Legacy external storage"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        if (manifest.requestLegacyExternalStorage != true) return emptyList()
        return listOf(
            finding(
                ruleId = id,
                title = "requestLegacyExternalStorage is enabled",
                severity = Severity.MEDIUM,
                confidence = Confidence.CONFIRMED,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = "The app opts out of scoped storage, keeping broad access to shared storage.",
                detail = "requestLegacyExternalStorage only has an effect up to Android 10, but it signals the app writes " +
                    "to shared storage paths that other apps may read, and it blocks adoption of scoped storage APIs.",
                remediation = "Migrate to scoped storage (MediaStore, Storage Access Framework, app-specific directories) " +
                    "and remove requestLegacyExternalStorage.",
                evidence = listOf(ev("requestLegacyExternalStorage", "true", "AndroidManifest.xml")),
                references = listOf(References.cweCleartextStorage, References.masvsStorage2),
                masvs = listOf("MASVS-STORAGE-2"),
                cwe = listOf("CWE-312")
            )
        )
    }
}

/** Signature and certificate health. */
object SigningRules : SecurityRule {
    override val id = "PF-SIG-001"
    override val title = "Signing certificate health"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val signing = ctx.facts.signing ?: return emptyList()
        val cert = signing.certificate
        val findings = mutableListOf<Finding>()

        if (cert == null) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "No signing certificate found",
                    severity = Severity.HIGH,
                    confidence = Confidence.MEDIUM,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "PulseForge could not extract a signing certificate from this APK.",
                    detail = "The archive may be unsigned, use an unusual signing scheme, or have a damaged " +
                        "APK Signing Block. Unsigned APKs cannot be installed normally.",
                    remediation = "Re-sign the APK with apksigner and re-analyse it.",
                    evidence = signing.warnings.map { ev("Signature warning", it, "APK Signing Block") },
                    references = listOf(References.masvsCode1)
                )
            )
            return findings
        }

        if (cert.isKnownDebugKey) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "Signed with the Android debug key",
                    severity = Severity.CRITICAL,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "The APK is signed with the well-known debug certificate, so it cannot be a production release.",
                    detail = "The certificate subject is '${cert.subject}', the standard Android debug identity. " +
                        "Anyone can create this key, so the signature proves nothing about origin, and Play rejects it.",
                    remediation = "Sign release builds with your private release key stored outside the repository.",
                    evidence = listOf(
                        ev("Subject", cert.subject, "Signing certificate"),
                        ev("SHA-256", cert.sha256, "Signing certificate"),
                        ev("Schemes", signing.schemeSummary, "APK Signing Block")
                    ),
                    references = listOf(References.cweDebugCode, References.masvsCode1),
                    masvs = listOf("MASVS-CODE-1", "MASVS-RESILIENCE-1"),
                    cwe = listOf("CWE-489")
                )
            )
        }

        if (signing.schemes.isNotEmpty() && signing.schemes.all { it.startsWith("v1") }) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "Only the legacy v1 signature scheme is present",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "Without a v2/v3 signature block, the whole-archive digest is not protected.",
                    detail = "v1 (JAR) signing covers individual entries and is vulnerable to certain substitution attacks. " +
                        "v2 and v3 sign the entire archive. Android 11+ requires at least v2 for new installs.",
                    remediation = "Sign with --v2-signing-enabled true --v3-signing-enabled true in apksigner.",
                    evidence = listOf(ev("Schemes", signing.schemeSummary, "APK Signing Block")),
                    references = listOf(References.masvsCode1),
                    masvs = listOf("MASVS-CODE-1")
                )
            )
        }

        val now = System.currentTimeMillis()
        if (cert.notAfterEpochMs in 1..now) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "Signing certificate has expired",
                    severity = Severity.MEDIUM,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "The certificate expired on ${isoDate(cert.notAfterEpochMs)}.",
                    detail = "Android does not enforce certificate expiry for installed apps, but expired certificates " +
                        "break some distribution channels and look like an abandoned build.",
                    remediation = "Sign with a certificate that is valid for the remaining life of the app (25+ years is typical).",
                    evidence = listOf(ev("Not after", isoDate(cert.notAfterEpochMs), "Signing certificate")),
                    references = listOf(References.masvsCode1),
                    masvs = listOf("MASVS-CODE-1")
                )
            )
        }

        if (cert.keySizeBits in 1..2047 && cert.keyAlgorithm.equals("RSA", true)) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "Signing key is shorter than 2048 bits",
                    severity = Severity.HIGH,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "The ${cert.keyAlgorithm} signing key is ${cert.keySizeBits} bits.",
                    detail = "Keys below 2048 bits no longer meet current guidance and are rejected by newer tooling.",
                    remediation = "Rotate to a 2048-bit (or stronger) RSA key, or an EC P-256 key.",
                    evidence = listOf(ev("Key", "${cert.keyAlgorithm} ${cert.keySizeBits} bits", "Signing certificate")),
                    references = listOf(References.cweKeyLength, References.masvsCrypto1),
                    masvs = listOf("MASVS-CRYPTO-1"),
                    cwe = listOf("CWE-326")
                )
            )
        }

        return findings
    }

    private fun isoDate(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).toString().substringBefore('T')
}

/** Permission surface and privacy risk. */
object PermissionRules : SecurityRule {
    override val id = "PF-PRM-001"
    override val title = "Permission surface"

    private val sensitiveCombos = listOf(
        Triple("android.permission.READ_SMS", "android.permission.INTERNET",
            "Reading SMS together with network access allows one-time passcodes to be exfiltrated."),
        Triple("android.permission.READ_CALL_LOG", "android.permission.INTERNET",
            "Call history plus network access exposes the user's social graph."),
        Triple("android.permission.ACCESS_FINE_LOCATION", "android.permission.INTERNET",
            "Precise location plus network access enables continuous tracking."),
        Triple("android.permission.RECORD_AUDIO", "android.permission.INTERNET",
            "Microphone access plus network access is the classic surveillance combination.")
    )

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        val requested = manifest.requestedPermissions
        val findings = mutableListOf<Finding>()

        val dangerous = requested.filter { PermissionCatalog.isDangerous(it) }
        if (dangerous.size >= 5) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "Broad dangerous-permission surface",
                    severity = if (dangerous.size >= 10) Severity.HIGH else Severity.MEDIUM,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "The app requests ${dangerous.size} runtime (dangerous) permissions.",
                    detail = "Each dangerous permission must be granted by the user at runtime. A large set increases the " +
                        "chance of over-collection and reduces install conversion. Permissions: " +
                        dangerous.joinToString(", ") { it.substringAfterLast('.') },
                    remediation = "Remove permissions that no shipped feature needs, prefer scoped alternatives " +
                        "(photo picker instead of READ_MEDIA_IMAGES, approximate location instead of fine), " +
                        "and request the rest in context rather than at first launch.",
                    evidence = listOf(
                        ev("Dangerous permissions", dangerous.size.toString(), "AndroidManifest.xml"),
                        ev("All requested", requested.size.toString(), "AndroidManifest.xml")
                    ),
                    references = listOf(References.cweUnnecessaryPrivileges, References.masvsPrivacy1),
                    category = FindingCategory.PRIVACY,
                    masvs = listOf("MASVS-PRIVACY-1"),
                    cwe = listOf("CWE-250")
                )
            )
        }

        for (permission in requested) {
            if (!PermissionCatalog.isRestricted(permission)) continue
            val entry = PermissionCatalog.entry(permission) ?: continue
            findings.add(
                finding(
                    ruleId = "$id-RESTRICTED",
                    title = "Restricted permission: ${permission.substringAfterLast('.')}",
                    severity = entry.severity,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = entry.rationale,
                    detail = "Google Play restricts this permission to apps with a qualifying core use case; " +
                        "the app must file a declaration and may be rejected or removed without one.",
                    remediation = "Confirm the feature genuinely requires this permission. If not, remove it. " +
                        "If it does, prepare the Play Console declaration and in-app disclosure.",
                    affected = permission,
                    evidence = listOf(ev("Permission", permission, "AndroidManifest.xml")),
                    references = listOf(References.masvsPrivacy1, References.masvsPrivacy2),
                    category = FindingCategory.PRIVACY,
                    masvs = listOf("MASVS-PRIVACY-1"),
                    cwe = listOf("CWE-250")
                )
            )
        }

        for ((first, second, rationale) in sensitiveCombos) {
            if (requested.contains(first) && requested.contains(second)) {
                findings.add(
                    finding(
                        ruleId = "$id-COMBO",
                        title = "Sensitive permission combination",
                        severity = Severity.HIGH,
                        confidence = Confidence.MEDIUM,
                        evidenceType = EvidenceType.INFERENCE,
                        summary = rationale,
                        detail = "Both $first and $second are requested. The combination is what turns a data permission " +
                            "into an exfiltration path; either permission alone is far less risky.",
                        remediation = "Drop one side of the combination, or gate the network path behind explicit consent " +
                            "and minimise what is sent.",
                        affected = first,
                        evidence = listOf(
                            ev("Permission", first, "AndroidManifest.xml"),
                            ev("Permission", second, "AndroidManifest.xml")
                        ),
                        references = listOf(References.masvsPrivacy2, References.masvsNetwork1),
                        category = FindingCategory.PRIVACY,
                        fpNote = "The app may handle this data entirely locally; the finding flags the capability, not observed behaviour.",
                        masvs = listOf("MASVS-PRIVACY-2"),
                        cwe = listOf("CWE-250"),
                        idSuffix = first.substringAfterLast('.')
                    )
                )
            }
        }
        return findings
    }
}

/** Platform-level compatibility issues that affect security defaults. */
object CompatibilityRules : SecurityRule {
    override val id = "PF-CMP-001"
    override val title = "Platform compatibility"

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val manifest = ctx.manifest ?: return emptyList()
        val findings = mutableListOf<Finding>()
        val target = manifest.targetSdk

        if (target != null && target < 31) {
            findings.add(
                finding(
                    ruleId = id,
                    title = "targetSdk $target is below Android 12",
                    severity = if (target < 28) Severity.HIGH else Severity.MEDIUM,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "Targeting API $target keeps older, less secure platform defaults in force.",
                    detail = buildString {
                        append("targetSdk $target means the platform applies legacy behaviour: ")
                        if (target < 28) append("cleartext HTTP is allowed by default, ")
                        if (target < 29) append("scoped storage is not enforced, ")
                        if (target < 30) append("package visibility is unrestricted, ")
                        if (target < 31) append("components with intent filters stay implicitly exported and PendingIntent mutability is unchecked, ")
                        append("and Google Play requires a recent targetSdk for new uploads.")
                    },
                    remediation = "Raise targetSdk to 34, test the migration (exported flags, exact alarms, storage, " +
                        "PendingIntent flags) and ship it.",
                    evidence = listOf(
                        ev("targetSdkVersion", target.toString(), "AndroidManifest.xml"),
                        ev("minSdkVersion", (manifest.minSdk ?: 0).toString(), "AndroidManifest.xml")
                    ),
                    references = listOf(References.masvsCode1),
                    category = FindingCategory.COMPATIBILITY,
                    masvs = listOf("MASVS-CODE-1"),
                    cwe = listOf("CWE-1104")
                )
            )
        }

        val abis = ctx.facts.resources?.abis.orEmpty()
        val has64 = abis.any { it == "arm64-v8a" || it == "x86_64" }
        if (abis.isNotEmpty() && !has64) {
            findings.add(
                finding(
                    ruleId = "$id-64",
                    title = "No 64-bit native library",
                    severity = Severity.HIGH,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "The APK ships native code for ${abis.joinToString(", ")} only.",
                    detail = "Google Play requires a 64-bit variant for apps with native code, and 32-bit-only " +
                        "devices are increasingly rare. The app cannot install on 64-bit-only hardware.",
                    remediation = "Build the native libraries for arm64-v8a (and x86_64 for emulators) and ship an App Bundle.",
                    evidence = abis.map { ev("ABI", it, "lib/") },
                    references = listOf(References.masvsCode1),
                    category = FindingCategory.COMPATIBILITY,
                    masvs = listOf("MASVS-CODE-1")
                )
            )
        }

        if (abis.size > 1) {
            findings.add(
                finding(
                    ruleId = "$id-ABI",
                    title = "Multiple ABIs bundled in one APK",
                    severity = Severity.LOW,
                    confidence = Confidence.CONFIRMED,
                    evidenceType = EvidenceType.DIRECT_OBSERVATION,
                    summary = "The APK contains native libraries for ${abis.joinToString(", ")}.",
                    detail = "Every device downloads all ABIs but uses one. Splitting by ABI (App Bundle) typically " +
                        "removes 30-60% of the download for native-heavy apps.",
                    remediation = "Publish an Android App Bundle, or build ABI splits with splits { abi { enable true } }.",
                    evidence = abis.map { ev("ABI", it, "lib/") },
                    references = listOf(References.masvsCode1),
                    category = FindingCategory.PERFORMANCE,
                    masvs = listOf("MASVS-CODE-1")
                )
            )
        }
        return findings
    }
}
