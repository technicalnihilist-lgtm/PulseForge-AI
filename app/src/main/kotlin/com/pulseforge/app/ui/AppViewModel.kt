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
