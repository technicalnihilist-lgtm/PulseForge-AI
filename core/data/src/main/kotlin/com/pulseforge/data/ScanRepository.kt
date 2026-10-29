package com.pulseforge.data

import android.net.Uri
import com.pulseforge.data.db.ScanDao
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.data.file.FileVault
import com.pulseforge.model.ScanReport
import com.pulseforge.model.ScanStatus
import com.pulseforge.report.JsonReportWriter
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/** Coordinates the database, the vault and report (de)serialisation. */
class ScanRepository(private val dao: ScanDao, private val vault: FileVault) {

    fun observeScans(): Flow<List<ScanEntity>> = dao.observeAll()

    fun observeRecent(limit: Int = 5): Flow<List<ScanEntity>> = dao.observeRecent(limit)

    fun observeScan(id: String): Flow<ScanEntity?> = dao.observeById(id)

    suspend fun get(id: String): ScanEntity? = dao.getById(id)

    suspend fun count(): Int = dao.count()

    /**
     * Copies the picked document into the vault and creates the history row.
     * Returns the scan id and the private file that will be analysed.
     */
    suspend fun importApk(uri: Uri, displayName: String, keepCopy: Boolean): Pair<String, File> {
        val scanId = UUID.randomUUID().toString()
        val file = vault.import(uri, scanId)
        dao.insert(
            ScanEntity(
                id = scanId,
                displayName = displayName,
                status = ScanStatus.RUNNING.name,
                stage = "QUEUED",
                progress = 0f,
                message = "Queued",
                startedAtEpochMs = System.currentTimeMillis(),
                fileSizeBytes = file.length(),
                keptApkPath = if (keepCopy) file.absolutePath else null
            )
        )
        return scanId to file
    }

    /**
     * Creates the history row for an installed app. The APK is not copied: it belongs to the other
     * app and must not be duplicated, so [keptApkPath] stays null and nothing is deleted on removal.
     */
    suspend fun registerInstalledApp(app: com.pulseforge.model.InstalledApp): String {
        val scanId = UUID.randomUUID().toString()
        dao.insert(
            ScanEntity(
                id = scanId,
                displayName = app.displayTitle,
                packageName = app.packageName,
                versionName = app.versionName,
                status = ScanStatus.RUNNING.name,
                stage = "QUEUED",
                progress = 0f,
                message = "Queued",
                startedAtEpochMs = System.currentTimeMillis(),
                fileSizeBytes = app.apkSizeBytes,
                keptApkPath = null
            )
        )
        return scanId
    }

    suspend fun markCompleted(id: String, report: ScanReport, keptApkPath: String?) {
        val counts = report.counts
        dao.markCompleted(
            id = id,
            packageName = report.apk.manifest?.packageName,
            versionName = report.apk.manifest?.versionName,
            fileSize = report.apk.fileSizeBytes,
            sha256 = report.apk.sha256,
            securityScore = report.scoring.security,
            performanceScore = report.scoring.performance,
            critical = counts.critical,
            high = counts.high,
            medium = counts.medium,
            low = counts.low,
            info = counts.info,
            finishedAt = report.generatedAtEpochMs,
            duration = report.meta.durationMs,
            reportJson = JsonReportWriter.write(report),
            keptApkPath = keptApkPath
        )
    }

    suspend fun markFailed(id: String, status: ScanStatus, code: String?, message: String?, hint: String?) {
        dao.markFailed(id, status.name, code, message, hint, System.currentTimeMillis())
    }

    suspend fun updateProgress(id: String, stage: String, progress: Float, message: String?) {
        dao.updateProgress(id, stage, progress, message)
    }

    suspend fun report(id: String): ScanReport? {
        val json = dao.getById(id)?.reportJson ?: return null
        return runCatching { JsonReportWriter.read(json) }.getOrNull()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        vault.delete(id)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        vault.deleteAll()
    }

    suspend fun deleteTemporaryCopy(id: String) {
        vault.delete(id)
    }

    /** Removes vault directories left behind by deleted scans. */
    suspend fun cleanOrphans() {
        vault.cleanOrphans(dao.allIds().toSet())
    }

    suspend fun completedReports(limit: Int = 10): List<Pair<ScanEntity, ScanReport>> =
        dao.completed(limit).mapNotNull { entity ->
            val json = entity.reportJson ?: return@mapNotNull null
            runCatching { JsonReportWriter.read(json) }.getOrNull()?.let { entity to it }
        }
}
