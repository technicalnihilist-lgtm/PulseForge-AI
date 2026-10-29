package com.pulseforge.app

import android.content.Context
import com.pulseforge.ai.OfflineAssistant
import com.pulseforge.app.ai.AssetLocalModel
import com.pulseforge.data.ScanRepository
import com.pulseforge.data.db.PulseForgeDatabase
import com.pulseforge.data.apps.InstalledAppScanner
import com.pulseforge.data.apps.InstalledAppsRepository
import com.pulseforge.data.file.FileVault
import com.pulseforge.data.settings.SettingsRepository
import com.pulseforge.data.work.ScanScheduler
import com.pulseforge.performance.SourceReviewEngine
import com.pulseforge.report.ScanPipeline

/**
 * Manual dependency graph. Small enough that a DI framework would add more indirection than value,
 * and explicit enough that every dependency is visible in one place.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val database by lazy { PulseForgeDatabase.get(appContext) }
    val vault by lazy { FileVault(appContext) }
    val repository by lazy { ScanRepository(database.scanDao(), vault) }
    val settings by lazy { SettingsRepository(appContext) }
    val scheduler by lazy { ScanScheduler(appContext) }
    val pipeline by lazy { ScanPipeline(appVersion = BuildConfig.VERSION_NAME) }
    val assistant by lazy { OfflineAssistant(AssetLocalModel(appContext)) }
    val installedApps by lazy { InstalledAppsRepository(appContext) }
    val installedAppScanner by lazy { InstalledAppScanner(appContext, installedApps, pipeline) }
    val sourceReviewEngine get() = SourceReviewEngine
}
