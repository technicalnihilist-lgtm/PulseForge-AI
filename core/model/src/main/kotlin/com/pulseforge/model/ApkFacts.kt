package com.pulseforge.model

import kotlinx.serialization.Serializable

@Serializable
data class IntentFilterInfo(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val autoVerify: Boolean = false
) {
    val summary: String
        get() {
            val parts = buildList {
                if (actions.isNotEmpty()) add("actions=${actions.joinToString(",")}")
                if (categories.isNotEmpty()) add("categories=${categories.joinToString(",")}")
                if (schemes.isNotEmpty()) add("schemes=${schemes.joinToString(",")}")
                if (hosts.isNotEmpty()) add("hosts=${hosts.joinToString(",")}")
                if (mimeTypes.isNotEmpty()) add("mimeTypes=${mimeTypes.joinToString(",")}")
                if (paths.isNotEmpty()) add("paths=${paths.joinToString(",")}")
            }
            return if (parts.isEmpty()) "empty filter" else parts.joinToString("; ")
        }
}

@Serializable
data class ComponentInfo(
    val type: ComponentType,
    val name: String,
    /** null when the manifest omits android:exported (pre-Android 12 semantics apply). */
    val exported: Boolean? = null,
    val enabled: Boolean = true,
    val hasIntentFilter: Boolean = false,
    val permission: String? = null,
    val readPermission: String? = null,
    val writePermission: String? = null,
    val authorities: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val launchMode: String? = null,
    val intentFilters: List<IntentFilterInfo> = emptyList()
) {
    val shortName: String get() = name.substringAfterLast('.')

    /** Mirrors platform behaviour: before API 31 a component with an intent filter is implicitly exported. */
    fun effectiveExported(targetSdk: Int?): Boolean = exported ?: (hasIntentFilter && (targetSdk ?: 1) < 31)
}

@Serializable
data class PermissionUse(
    val name: String,
    val maxSdkVersion: Int? = null,
    val protectionLevel: String? = null,
    val declaredByApp: Boolean = false,
    val granted: Boolean = true
)

@Serializable
data class FeatureUse(val name: String, val required: Boolean = true, val glEsVersion: String? = null)

@Serializable
data class ManifestInfo(
    val packageName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val compileSdkHint: Int? = null,
    val applicationLabel: String? = null,
    val applicationClass: String? = null,
    val debuggable: Boolean? = null,
    val allowBackup: Boolean? = null,
    val usesCleartextTraffic: Boolean? = null,
    val networkSecurityConfig: String? = null,
    val extractNativeLibs: Boolean? = null,
    val testOnly: Boolean? = null,
    val fullBackupContent: String? = null,
    val dataExtractionRules: String? = null,
    val requestLegacyExternalStorage: Boolean? = null,
    val components: List<ComponentInfo> = emptyList(),
    val permissions: List<PermissionUse> = emptyList(),
    val declaredPermissions: List<PermissionUse> = emptyList(),
    val features: List<FeatureUse> = emptyList(),
    val rawAttributeCount: Int = 0
) {
    val requestedPermissions: List<String>
        get() = permissions.filter { !it.declaredByApp }.map { it.name }

    fun componentsOf(type: ComponentType): List<ComponentInfo> = components.filter { it.type == type }
}

@Serializable
data class CertInfo(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val sha256: String,
    val signatureAlgorithm: String,
    val keyAlgorithm: String,
    val keySizeBits: Int,
    val notBeforeEpochMs: Long,
    val notAfterEpochMs: Long,
    val isSelfSigned: Boolean,
    val isKnownDebugKey: Boolean = false
)

@Serializable
data class SigningInfo(
    val schemes: List<String> = emptyList(),
    val certificate: CertInfo? = null,
    val additionalCertificates: List<CertInfo> = emptyList(),
    val verified: Boolean = false,
    val verificationMessage: String? = null,
    val v1SignatureFiles: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val schemeSummary: String get() = if (schemes.isEmpty()) "none detected" else schemes.joinToString(" + ")
}

@Serializable
data class ApiUsage(val owner: String, val member: String, val occurrences: Int) {
    val display: String get() = "$owner->$member"
}

@Serializable
data class PackageRoot(val prefix: String, val classCount: Int)

@Serializable
data class DexFacts(
    val dexFiles: Int = 0,
    val totalReferencedMethods: Int = 0,
    val definedClasses: Int = 0,
    val stringConstantCount: Int = 0,
    val apiUsages: List<ApiUsage> = emptyList(),
    val packageRoots: List<PackageRoot> = emptyList(),
    val multidex: Boolean = false,
    val parseWarnings: List<String> = emptyList()
) {
    fun uses(owner: String, member: String): ApiUsage? = apiUsages.firstOrNull { it.owner == owner && it.member == member }
    fun usageCount(owner: String, member: String): Int = uses(owner, member)?.occurrences ?: 0
}

@Serializable
data class ZipEntrySummary(val path: String, val bytes: Long, val compressedBytes: Long, val category: String)

@Serializable
data class CategorySize(val category: String, val bytes: Long, val count: Int)

@Serializable
data class DuplicateGroup(val sha256: String, val paths: List<String>, val bytesEach: Long, val wastedBytes: Long)

@Serializable
data class ImageAsset(val path: String, val bytes: Long, val width: Int, val height: Int, val format: String) {
    val megapixels: Double get() = (width.toLong() * height.toLong()) / 1_000_000.0
}

@Serializable
data class NativeLibInfo(val path: String, val abi: String, val bytes: Long)

@Serializable
data class ResourceFacts(
    val entryCount: Int = 0,
    val totalUncompressedBytes: Long = 0,
    val byCategory: List<CategorySize> = emptyList(),
    val imageCount: Int = 0,
    val imageBytes: Long = 0,
    val largestEntries: List<ZipEntrySummary> = emptyList(),
    val duplicates: List<DuplicateGroup> = emptyList(),
    val oversizedImages: List<ImageAsset> = emptyList(),
    val unusedAssetCandidates: List<String> = emptyList(),
    val nativeLibs: List<NativeLibInfo> = emptyList(),
    val abis: List<String> = emptyList(),
    val hasResourcesArsc: Boolean = false,
    val resourceStringPoolEntries: Int = 0
)

@Serializable
data class ParseWarning(val code: String, val message: String, val fatal: Boolean = false)

/** Everything the parsers read out of one APK. Rule engines consume only this. */
@Serializable
data class ApkFacts(
    val fileName: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val inspectedAtEpochMs: Long,
    val manifest: ManifestInfo? = null,
    val manifestParseError: String? = null,
    val signing: SigningInfo? = null,
    val dex: DexFacts = DexFacts(),
    val resources: ResourceFacts? = null,
    val entryCount: Int = 0,
    val warnings: List<ParseWarning> = emptyList()
) {
    val hasManifest: Boolean get() = manifest != null
    val isMultidex: Boolean get() = dex.dexFiles > 1
}
