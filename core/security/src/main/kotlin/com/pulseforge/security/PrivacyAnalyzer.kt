package com.pulseforge.security

import com.pulseforge.apk.ApkScanInput
import com.pulseforge.model.Confidence
import com.pulseforge.model.PermissionGroupSummary
import com.pulseforge.model.PrivacyAnalysis
import com.pulseforge.model.Severity
import com.pulseforge.model.TrackerSignal

/**
 * Builds the privacy section: permission groups, tracker-like SDK signals and the caveats that come
 * with reading them from an APK.
 */
object PrivacyAnalyzer {

    private val trackerSignals = mapOf(
        "Lcom/google/firebase/analytics/" to "Firebase Analytics",
        "Lcom/google/android/gms/analytics/" to "Google Analytics",
        "Lcom/google/android/gms/ads/" to "Google Mobile Ads",
        "Lcom/facebook/appevents/" to "Facebook App Events",
        "Lcom/appsflyer/" to "AppsFlyer attribution",
        "Lcom/adjust/sdk/" to "Adjust attribution",
        "Lcom/amplitude/" to "Amplitude analytics",
        "Lcom/mixpanel/" to "Mixpanel analytics",
        "Lcom/segment/" to "Segment",
        "Lcom/onesignal/" to "OneSignal push",
        "Lcom/braze/" to "Braze",
        "Lcom/flurry/" to "Flurry",
        "Lcom/crashlytics/" to "Crashlytics",
        "Lcom/sentry/" to "Sentry",
        "Lcom/mopub/" to "MoPub ads",
        "Lcom/unity3d/ads/" to "Unity Ads",
        "Lcom/bytedance/" to "ByteDance SDK"
    )

    fun analyse(input: ApkScanInput): PrivacyAnalysis {
        val manifest = input.facts.manifest
        val permissions = manifest?.requestedPermissions.orEmpty()

        val groups = PermissionCatalog.groups(permissions).map { g ->
            PermissionGroupSummary(
                group = g.group,
                permissions = g.permissions.map { it.substringAfterLast('.') },
                risk = g.risk,
                rationale = g.rationale
            )
        }

        val trackers = trackerSignals.entries
            .filter { (prefix, _) -> input.dex.methodRefs.keys.any { it.startsWith(prefix) } }
            .map { (prefix, name) ->
                TrackerSignal(
                    name = name,
                    evidence = "Classes under ${prefix.removePrefix("L")} are referenced",
                    confidence = Confidence.MEDIUM
                )
            }

        val notes = mutableListOf(
            "Permission and SDK signals describe capability, not observed behaviour. PulseForge does not run the app, " +
                "so it cannot see what data is actually collected or where it is sent.",
            "Tracker detection is prefix-based on class names. Obfuscated or shaded SDKs are not detected."
        )
        if (input.facts.resources?.hasResourcesArsc == false) {
            notes.add("resources.arsc was not readable, so resource-string based checks had less to work with.")
        }

        val exported = manifest?.components.orEmpty()
            .count { it.effectiveExported(manifest?.targetSdk) && it.enabled }

        return PrivacyAnalysis(
            permissionGroups = groups,
            trackerSignals = trackers,
            notes = notes,
            dangerousPermissionCount = permissions.count { PermissionCatalog.isDangerous(it) },
            exportedComponentCount = exported
        )
    }

    fun highestRisk(analysis: PrivacyAnalysis): Severity =
        analysis.permissionGroups.maxByOrNull { it.risk.rank }?.risk ?: Severity.INFO
}
