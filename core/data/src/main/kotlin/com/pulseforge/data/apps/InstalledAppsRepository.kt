package com.pulseforge.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.pulseforge.model.InstalledApp
import com.pulseforge.security.PermissionCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads the list of installed apps.
 *
 * Listing needs the QUERY_ALL_PACKAGES permission on Android 11+, which PulseForge declares and
 * documents; it grants visibility of package metadata only, no data access and no network.
 */
class InstalledAppsRepository(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    @Suppress("DEPRECATION")
    private fun queryFlags(): Int =
        PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS

    suspend fun list(includeSystemApps: Boolean = true): List<InstalledApp> = withContext(Dispatchers.IO) {
        val launchers = launcherComponents()
        val packages = runCatching {
            packageManager.getInstalledPackages(queryFlags())
        }.getOrDefault(emptyList())

        packages.mapNotNull { info -> runCatching { toInstalledApp(info, launchers) }.getOrNull() }
            .filter { includeSystemApps || !it.isSystemApp }
            .sortedWith(compareByDescending<InstalledApp> { it.quickRisk.rank }.thenBy { it.displayTitle.lowercase() })
    }

    /** Package metadata for one app, used when building the metadata-only report. */
    fun packageInfo(packageName: String): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, queryFlags())
    }.getOrNull()

    fun launcherComponents(): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.name }
            .map { "ACTIVITY:$it" }
            .toSet()
    }.getOrDefault(emptySet())

    /** The APK file, if this build of Android lets us read another app's package. */
    fun apkFile(app: InstalledApp): File? {
        val path = app.apkPath ?: return null
        val file = File(path)
        return if (file.exists() && file.canRead()) file else null
    }

    /** System screen where the user can revoke permissions, clear data or uninstall. */
    fun appSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    private fun toInstalledApp(info: PackageInfo, launchers: Set<String>): InstalledApp {
        val appInfo = info.applicationInfo ?: error("no applicationInfo for ${info.packageName}")
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val apkPath = appInfo.sourceDir
        val apkFile = apkPath?.let { File(it) }

        val permissions = info.requestedPermissions.orEmpty().toList()
        val exported = info.activities.orEmpty().count { it.exported } +
            info.services.orEmpty().count { it.exported } +
            info.receivers.orEmpty().count { it.exported } +
            info.providers.orEmpty().count { it.exported }

        return InstalledApp(
            packageName = info.packageName,
            label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault(info.packageName),
            versionName = info.versionName,
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            isSystemApp = isSystem,
            isDebuggable = debuggable,
            installer = runCatching { packageManager.getInstallerPackageName(info.packageName) }.getOrNull(),
            apkPath = apkPath,
            apkSizeBytes = apkFile?.let { if (it.exists()) it.length() else 0L } ?: 0L,
            apkReadable = apkFile?.let { it.exists() && it.canRead() } == true,
            permissionCount = permissions.size,
            dangerousPermissionCount = permissions.count { PermissionCatalog.isDangerous(it) },
            exportedComponentCount = exported,
            lastUpdateTime = info.lastUpdateTime
        )
    }
}
