package com.pulseforge.data.apps

import com.pulseforge.model.DeviceCategory
import com.pulseforge.model.DeviceOverview
import com.pulseforge.model.DeviceScoreItem
import com.pulseforge.model.InstalledApp
import com.pulseforge.model.Severity

/**
 * Builds the device-level view from installed-app metadata.
 *
 * The score is a fixed, documented deduction model - the same shape as the per-app score, so the
 * two are not inconsistent. Every deduction is returned in [DeviceOverview.breakdown].
 */
object DeviceOverviewBuilder {

    fun build(apps: List<InstalledApp>, reclaimableBytes: Long): DeviceOverview {
        val userApps = apps.filterNot { it.isSystemApp }
        val debuggable = apps.filter { it.isDebuggable }
        val riskyPermissions = apps.filter { it.dangerousPermissionCount >= 8 }
        val wideSurface = apps.filter { it.exportedComponentCount >= 5 }
        val outdated = userApps.filter { (it.targetSdk ?: 0) in 1..29 }
        val installers = userApps.count { it.installer == null }

        val breakdown = buildList {
            if (debuggable.isNotEmpty()) {
                add(
                    DeviceScoreItem(
                        "Debuggable builds installed",
                        -(debuggable.size * 20).coerceAtMost(40),
                        "${debuggable.size} app(s) ship android:debuggable=true: ${names(debuggable)}"
                    )
                )
            }
            if (riskyPermissions.isNotEmpty()) {
                add(
                    DeviceScoreItem(
                        "Broad permission profiles",
                        -(riskyPermissions.size * 6).coerceAtMost(30),
                        "${riskyPermissions.size} app(s) request 8+ dangerous permissions: ${names(riskyPermissions)}"
                    )
                )
            }
            if (wideSurface.isNotEmpty()) {
                add(
                    DeviceScoreItem(
                        "Wide exported surface",
                        -(wideSurface.size * 4).coerceAtMost(20),
                        "${wideSurface.size} app(s) export 5+ components: ${names(wideSurface)}"
                    )
                )
            }
            if (outdated.isNotEmpty()) {
                add(
                    DeviceScoreItem(
                        "Outdated platform targeting",
                        -(outdated.size * 3).coerceAtMost(15),
                        "${outdated.size} user app(s) target API 29 or lower: ${names(outdated)}"
                    )
                )
            }
            if (reclaimableBytes > 500L * 1024 * 1024) {
                add(DeviceScoreItem("PulseForge cached APKs", -10, "More than 500 MB of cached APKs can be freed"))
            } else if (reclaimableBytes > 100L * 1024 * 1024) {
                add(DeviceScoreItem("PulseForge cached APKs", -5, "More than 100 MB of cached APKs can be freed"))
            }
        }

        val score = (100 - breakdown.sumOf { -it.points }).coerceIn(0, 100)

        val categories = listOf(
            DeviceCategory(
                id = "debug",
                title = "Debuggable apps",
                subtitle = if (debuggable.isEmpty()) "None found" else names(debuggable, 3),
                severity = if (debuggable.isEmpty()) Severity.INFO else Severity.CRITICAL,
                count = debuggable.size,
                actionHint = "Debug builds let a connected host read app data. Replace with a release build."
            ),
            DeviceCategory(
                id = "permissions",
                title = "Heavy permission users",
                subtitle = if (riskyPermissions.isEmpty()) "None found" else names(riskyPermissions, 3),
                severity = if (riskyPermissions.isEmpty()) Severity.INFO else Severity.HIGH,
                count = riskyPermissions.size,
                actionHint = "Open each app's settings and revoke the permissions it does not need."
            ),
            DeviceCategory(
                id = "exported",
                title = "Wide attack surface",
                subtitle = if (wideSurface.isEmpty()) "None found" else names(wideSurface, 3),
                severity = if (wideSurface.isEmpty()) Severity.INFO else Severity.MEDIUM,
                count = wideSurface.size,
                actionHint = "Exported components are reachable by other apps. Check the per-app report."
            ),
            DeviceCategory(
                id = "outdated",
                title = "Old target SDK",
                subtitle = if (outdated.isEmpty()) "None found" else names(outdated, 3),
                severity = if (outdated.isEmpty()) Severity.INFO else Severity.LOW,
                count = outdated.size,
                actionHint = "Old targets miss platform hardening. Prefer apps that update."
            ),
            DeviceCategory(
                id = "sideloaded",
                title = "Not from an app store",
                subtitle = "$installers app(s) have no installer package",
                severity = if (installers == 0) Severity.INFO else Severity.LOW,
                count = installers,
                actionHint = "Sideloaded apps bypass store review. Uninstall any you do not recognise."
            ),
            DeviceCategory(
                id = "storage",
                title = "Reclaimable storage",
                subtitle = formatBytes(reclaimableBytes),
                severity = when {
                    reclaimableBytes > 500L * 1024 * 1024 -> Severity.MEDIUM
                    reclaimableBytes > 100L * 1024 * 1024 -> Severity.LOW
                    else -> Severity.INFO
                },
                count = if (reclaimableBytes > 0) 1 else 0,
                actionHint = "PulseForge can delete its own cached APK copies. Other apps' caches need the system Settings."
            )
        )

        val offenders = apps
            .filter { it.quickRisk.rank >= Severity.MEDIUM.rank }
            .sortedWith(
                compareByDescending<InstalledApp> { it.quickRisk.rank }
                    .thenByDescending { it.dangerousPermissionCount }
                    .thenBy { it.displayTitle.lowercase() }
            )
            .take(10)

        return DeviceOverview(
            scannedApps = apps.size,
            systemApps = apps.count { it.isSystemApp },
            score = score,
            breakdown = breakdown,
            categories = categories,
            offenders = offenders,
            reclaimableBytes = reclaimableBytes,
            generatedAtEpochMs = System.currentTimeMillis(),
            limitations = listOf(
                "Built from package metadata for ${apps.size} apps. Tap an app for a full static report.",
                "PulseForge cannot free other apps' memory or clear their caches: Android does not allow one app " +
                    "to do that. It can measure, and it opens the system screens that can.",
                "Nothing is measured at runtime here, so CPU, battery and frame rates are out of scope."
            )
        )
    }

    private fun names(apps: List<InstalledApp>, limit: Int = 4): String =
        apps.take(limit).joinToString(", ") { it.displayTitle } +
            if (apps.size > limit) " +${apps.size - limit} more" else ""

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }
}
