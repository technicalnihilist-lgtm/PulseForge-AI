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

    private companion object {
        const val LAUNCHER_CACHE_TTL_MS = 60_000L
    }


    private val packageManager: PackageManager get() = context.packageManager

    /** Populated by [list]; lets callers ask "is this installed?" without touching PackageManager. */
    @Volatile
    private var installedPackages: Set<String> = emptySet()

    /** In-memory check. Never hits Binder, so it is safe on the main thread. */
    fun isInstalled(packageName: String): Boolean = packageName in installedPackages

    /** Launcher components are queried repeatedly; a short-lived memo keeps it off the Binder path. */
    private var launcherCache: Pair<Long, Set<String>>? = null

    @Suppress("DEPRECATION")
    private fun queryFlags(): Int =
        PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS

    suspend fun list(includeSystemApps: Boolean = true): List<InstalledApp> = withContext(Dispatchers.IO) {
        val launchers = launcherComponents()
        val packages = runCatching {
            packageManager.getInstalledPackages(queryFlags())
        }.getOrDefault(emptyList())

        // One pass over the package list: the previous version built a HashSet, then mapped to a
        // Result per app (allocating one object each) and filtered in a second pass.
        val installed = HashSet<String>(packages.size)
        val apps = ArrayList<InstalledApp>(packages.size)
        for (info in packages) {
            installed.add(info.packageName)
            val app = try {
                toInstalledApp(info, launchers)
            } catch (e: Exception) {
                null
            } ?: continue
            if (includeSystemApps || !app.isSystemApp) apps.add(app)
        }
        installedPackages = installed
        // `sortedBy { it.displayTitle.lowercase() }` still ran the lower-case inside the comparator,
        // so it allocated on every comparison. Both sort keys are now computed once per app, and
        // the two stable sorts give the same order: worst risk first, then title (case-insensitive).
        apps.map { app -> Triple(app.displayTitle.lowercase(), app.quickRisk.rank, app) }
            .sortedBy { it.first }
            .sortedByDescending { it.second }
            .map { it.third }
    }

    /** Package metadata for one app, used when building the metadata-only report. */
    fun packageInfo(packageName: String): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, queryFlags())
    }.getOrNull()

    fun launcherComponents(): Set<String> {
        val cached = launcherCache
        if (cached != null && System.currentTimeMillis() - cached.first < LAUNCHER_CACHE_TTL_MS) {
            return cached.second
        }
        val fresh = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.name }
                .map { "ACTIVITY:$it" }
                .toSet()
        }.getOrDefault(emptySet())
        launcherCache = System.currentTimeMillis() to fresh
        return fresh
    }

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
        // One stat() for both the size and the readability answer; it used to ask twice.
        val apkExists = apkFile?.exists() == true

        // No copy of the permission array: it is only counted, never mutated or stored.
        val permissions = info.requestedPermissions
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
            apkSizeBytes = if (apkFile != null && apkExists) apkFile.length() else 0L,
            apkReadable = apkFile != null && apkExists && apkFile.canRead(),
            permissionCount = permissions?.size ?: 0,
            dangerousPermissionCount = permissions?.count { PermissionCatalog.isDangerous(it) } ?: 0,
            exportedComponentCount = exported,
            lastUpdateTime = info.lastUpdateTime
        )
    }
}
