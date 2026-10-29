package com.pulseforge.security

/**
 * The complete, ordered rule set. Bumping [VERSION] must be reflected in the report metadata so a
 * saved report can be traced back to the rules that produced it.
 */
object RuleCatalog {

    const val VERSION = "2024.10-r1"

    val rules: List<SecurityRule> = listOf(
        // Manifest / IPC
        ExportedComponentsRule,
        DeepLinkRule,
        ExportedProviderRule,
        CustomPermissionRule,
        DebuggableRule,
        TestOnlyRule,
        BackupRule,
        CleartextTrafficRule,
        LegacyStorageRule,
        // WebView / TLS
        WebViewRules,
        TlsValidationRules,
        PinningRule,
        CleartextUrlRule,
        // Crypto
        WeakHashRule,
        WeakCipherRule,
        InsecureRandomRule,
        // Secrets
        SecretsRule,
        // Permissions
        PermissionRules,
        // Signing
        SigningRules,
        // Code
        RuntimeExecRule,
        DynamicCodeRule,
        SensitiveLoggingRule,
        ReflectionRule,
        // Dependencies & compatibility
        DependencyRules,
        CompatibilityRules
    )

    val ids: List<String> = rules.map { it.id }
}
