package com.pulseforge.model

import kotlinx.serialization.Serializable

/**
 * An app installed on this device, as reported by PackageManager.
 *
 * [apkReadable] tells the analyser whether the APK itself can be opened. When it can, the full
 * static pipeline runs; when it cannot (some OEM builds restrict /data/app), PulseForge falls back
 * to a manifest-level analysis and says so in the report.
 */
@Serializable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val isSystemApp: Boolean = false,
    val isDebuggable: Boolean = false,
    val installer: String? = null,
    val apkPath: String? = null,
    val apkSizeBytes: Long = 0,
    val apkReadable: Boolean = false,
    val permissionCount: Int = 0,
    val dangerousPermissionCount: Int = 0,
    val exportedComponentCount: Int = 0,
    val lastUpdateTime: Long = 0
) {
    val displayTitle: String get() = label.ifBlank { packageName }

    /** A coarse, documented badge for the list: worst static signal we can see without parsing. */
    val quickRisk: Severity
        get() = when {
            isDebuggable -> Severity.CRITICAL
            dangerousPermissionCount >= 8 -> Severity.HIGH
            exportedComponentCount >= 5 -> Severity.MEDIUM
            dangerousPermissionCount >= 4 -> Severity.MEDIUM
            else -> Severity.INFO
        }
}
