package com.pulseforge.model

import kotlinx.serialization.Serializable

/** One line of the device score, so the number can always be recomputed by hand. */
@Serializable
data class DeviceScoreItem(
    val label: String,
    val points: Int,
    val explanation: String
)

/** A device-wide grouping, mirroring the cards a phone cleaner shows. */
@Serializable
data class DeviceCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val severity: Severity,
    val count: Int,
    val actionHint: String
)

/**
 * A device-level summary built from the metadata of every installed app.
 *
 * This is deliberately built from PackageManager data only: scanning every APK in full would take
 * minutes and hundreds of megabytes, so the device view is fast and says so. Tap through to an app
 * for the full static report.
 */
@Serializable
data class DeviceOverview(
    val scannedApps: Int = 0,
    val systemApps: Int = 0,
    val score: Int = 100,
    val breakdown: List<DeviceScoreItem> = emptyList(),
    val categories: List<DeviceCategory> = emptyList(),
    val offenders: List<InstalledApp> = emptyList(),
    val reclaimableBytes: Long = 0,
    val generatedAtEpochMs: Long = 0,
    val limitations: List<String> = emptyList()
) {
    val hasData: Boolean get() = scannedApps > 0
}
