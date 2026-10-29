package com.pulseforge.data.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.pulseforge.model.ApkFacts
import com.pulseforge.model.ComponentInfo
import com.pulseforge.model.ComponentType
import com.pulseforge.model.IntentFilterInfo
import com.pulseforge.model.InstalledApp
import com.pulseforge.model.ManifestInfo
import com.pulseforge.model.ParseWarning
import com.pulseforge.model.PermissionUse

/**
 * Builds [ApkFacts] from what PackageManager exposes, for apps whose APK cannot be opened.
 *
 * This is deliberately a *narrower* view than parsing the file: no DEX, no resources, no
 * certificate. Everything it reports is still a direct observation from the platform, and the
 * report carries a METADATA_ONLY warning so a reader knows which checks did not run.
 */
object PackageFactsBuilder {

    fun build(
        app: InstalledApp,
        packageInfo: PackageInfo?,
        launcherComponents: Set<String>
    ): ApkFacts {
        val info = packageInfo
        val appInfo = info?.applicationInfo

        val components = buildList {
            info?.activities.orEmpty().forEach { add(component(it.name, ComponentType.ACTIVITY, it.exported, it.permission, launcherComponents)) }
            info?.services.orEmpty().forEach { add(component(it.name, ComponentType.SERVICE, it.exported, it.permission, launcherComponents)) }
            info?.receivers.orEmpty().forEach { add(component(it.name, ComponentType.RECEIVER, it.exported, it.permission, launcherComponents)) }
            info?.providers.orEmpty().forEach {
                add(
                    component(
                        name = it.name,
                        type = ComponentType.PROVIDER,
                        exported = it.exported,
                        permission = it.readPermission ?: it.writePermission,
                        launcherComponents = launcherComponents,
                        authorities = it.authority
                    )
                )
            }
        }

        val permissions = info?.requestedPermissions.orEmpty().map { PermissionUse(name = it) }

        val manifest = ManifestInfo(
            packageName = app.packageName,
            versionName = app.versionName,
            versionCode = app.versionCode,
            minSdk = appInfo?.minSdkVersion ?: app.minSdk,
            targetSdk = appInfo?.targetSdkVersion ?: app.targetSdk,
            applicationLabel = app.label,
            applicationClass = appInfo?.className,
            debuggable = appInfo?.let { (it.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: app.isDebuggable,
            allowBackup = appInfo?.let { (it.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0 },
            usesCleartextTraffic = appInfo?.let { (it.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0 },
            extractNativeLibs = appInfo?.let { (it.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0 },
            components = components,
            permissions = permissions
        )

        return ApkFacts(
            fileName = "${app.displayTitle}.apk",
            fileSizeBytes = app.apkSizeBytes,
            sha256 = "",
            inspectedAtEpochMs = System.currentTimeMillis(),
            manifest = manifest,
            manifestParseError = null,
            signing = null,
            warnings = listOf(
                ParseWarning(
                    "METADATA_ONLY",
                    "The APK file could not be opened, so this report comes from PackageManager metadata. " +
                        "DEX, resource, certificate and duplicate analysis did not run."
                )
            )
        )
    }

    private fun component(
        name: String,
        type: ComponentType,
        exported: Boolean,
        permission: String?,
        launcherComponents: Set<String>,
        authorities: String? = null
    ): ComponentInfo {
        val isLauncher = "$type:$name" in launcherComponents || "ACTIVITY:$name" in launcherComponents
        return ComponentInfo(
            type = type,
            name = name,
            exported = exported,
            permission = permission,
            authorities = authorities,
            // PackageManager does not expose intent filters. A launcher component is the one case
            // we can reconstruct, and it matters: the export rules skip launcher activities.
            hasIntentFilter = isLauncher,
            intentFilters = if (isLauncher) {
                listOf(IntentFilterInfo(categories = listOf("android.intent.category.LAUNCHER")))
            } else {
                emptyList()
            }
        )
    }
}
