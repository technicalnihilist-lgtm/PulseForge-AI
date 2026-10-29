package com.pulseforge.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pulseforge.ai.AssistantAnswer
import com.pulseforge.app.AppGraph
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.data.settings.AppSettings
import com.pulseforge.data.settings.ThemeMode
import com.pulseforge.model.InstalledApp
import com.pulseforge.model.ScanReport
import com.pulseforge.model.SourceReview
import com.pulseforge.performance.OptimizationPlanner
import com.pulseforge.performance.PlannedAction
import com.pulseforge.performance.SourceReviewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One view model for the whole app: the state is small and every screen needs the same graph. */
class AppViewModel(private val graph: AppGraph) : ViewModel() {

    val scans: StateFlow<List<ScanEntity>> = graph.repository.observeScans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun observeScan(id: String): StateFlow<ScanEntity?> = graph.repository.observeScan(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------------ import & scan

    fun importAndScan(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _importState.value = ImportState.Working("Copying the APK into private storage")
            try {
                val keepCopy = settings.value.keepApkCopy
                val (scanId, file) = withContext(Dispatchers.IO) {
                    graph.repository.importApk(uri, displayName, keepCopy)
                }
                _importState.value = ImportState.Working("Queueing the scan")
                graph.scheduler.enqueue(scanId, file, displayName, keepCopy)
                _importState.value = ImportState.Started(scanId)
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(e.message ?: "The APK could not be imported")
            }
        }
    }

    fun consumeImportResult() {
        _importState.value = ImportState.Idle
    }

    fun cancelScan(id: String) {
        graph.scheduler.cancel(id)
    }

    fun deleteScan(id: String) {
        viewModelScope.launch {
            graph.repository.delete(id)
            _message.value = "Scan deleted, including the cached APK"
        }
    }

    fun deleteAllScans() {
        viewModelScope.launch {
            graph.repository.deleteAll()
            _message.value = "All scans and cached APKs deleted"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------ installed apps

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    /** Set when a scan of an installed app has been registered, so the UI can navigate to it. */
    private val _startedScan = MutableStateFlow<String?>(null)
    val startedScan: StateFlow<String?> = _startedScan.asStateFlow()

    fun loadInstalledApps(includeSystem: Boolean = true) {
        viewModelScope.launch {
            _appsLoading.value = true
            runCatching { graph.installedApps.list(includeSystem) }
                .onSuccess { _installedApps.value = it }
                .onFailure { _message.value = "Could not list installed apps: ${it.message}" }
            _appsLoading.value = false
        }
    }

    fun clearStartedScan() {
        _startedScan.value = null
    }

    /**
     * Scans an app that is already installed.
     *
     * When the platform lets us read the APK, the full pipeline runs in the background worker.
     * Otherwise the manifest-level report is built here (it is fast, a few PackageManager queries)
     * and saved like any other scan.
     */
    fun scanInstalledApp(app: InstalledApp) {
        viewModelScope.launch {
            val scanId = withContext(Dispatchers.IO) { graph.repository.registerInstalledApp(app) }
            val apkFile = withContext(Dispatchers.IO) { graph.installedApps.apkFile(app) }
            if (apkFile != null) {
                graph.scheduler.enqueue(scanId, apkFile, "${app.displayTitle}.apk", keepCopy = false)
                _startedScan.value = scanId
            } else {
                runCatching {
                    val report = graph.installedAppScanner.scan(app, null)
                    graph.repository.markCompleted(scanId, report, null)
                }.onFailure {
                    graph.repository.markFailed(
                        scanId,
                        com.pulseforge.model.ScanStatus.FAILED,
                        com.pulseforge.model.ScanErrorCode.INTERNAL_ERROR.name,
                        it.message ?: "The installed app could not be analysed",
                        "This app's metadata could not be read. Try importing its APK as a file instead."
                    )
                }
                _startedScan.value = scanId
            }
        }
    }

    /** Opens the system App-info screen, where permissions can be revoked or the app uninstalled. */
    fun openAppSettings(context: Context, packageName: String) {
        runCatching { context.startActivity(graph.installedApps.appSettingsIntent(packageName)) }
            .onFailure { _message.value = "No system settings screen is available for $packageName" }
    }

    // ------------------------------------------------------------------ reports

    suspend fun loadReport(id: String): ScanReport? = withContext(Dispatchers.IO) {
        graph.repository.report(id)
    }

    fun planFor(report: ScanReport): List<PlannedAction> = OptimizationPlanner.plan(
        findings = report.findings,
        performance = report.performance,
        facts = report.apk,
        userOwnsSource = settings.value.userOwnsSource
    )

    fun ask(question: String, report: ScanReport): AssistantAnswer =
        graph.assistant.answer(question, report, planFor(report))

    fun modelNote(): String = graph.assistant.modelInfo().let { info ->
        if (info.available) "Local model: ${info.name} (${info.sizeBytes} bytes)"
        else "No local model bundled - deterministic engine only."
    }

    fun modelAvailable(): Boolean = graph.assistant.modelInfo().available

    // ------------------------------------------------------------------ source review

    suspend fun reviewSource(uris: List<Pair<String, Uri>>, context: Context): SourceReview =
        withContext(Dispatchers.IO) {
            val files = uris.mapNotNull { (name, uri) ->
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                    if (text != null) SourceReviewEngine.SourceFile(name, text) else null
                }.getOrNull()
            }
            SourceReviewEngine.review(files)
        }

    // ------------------------------------------------------------------ settings

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { graph.settings.setThemeMode(mode) }
    fun setDeepScan(enabled: Boolean) = viewModelScope.launch { graph.settings.setDeepScan(enabled) }
    fun setKeepCopy(enabled: Boolean) = viewModelScope.launch { graph.settings.setKeepApkCopy(enabled) }
    fun setUserOwnsSource(owns: Boolean) = viewModelScope.launch { graph.settings.setUserOwnsSource(owns) }

    fun cachedBytes(): Long = graph.vault.let { vault -> scans.value.sumOf { vault.sizeOf(it.id) } }

    fun apkFileFor(scanId: String): File = graph.vault.apkFileFor(scanId)

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}

sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val step: String) : ImportState
    data class Started(val scanId: String) : ImportState
    data class Failed(val message: String) : ImportState
}
