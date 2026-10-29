package com.pulseforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scan: ScanEntity)

    @Query("SELECT * FROM scans ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE id = :id")
    fun observeById(id: String): Flow<ScanEntity?>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun getById(id: String): ScanEntity?

    @Query("SELECT * FROM scans WHERE status = 'COMPLETED' ORDER BY finishedAtEpochMs DESC LIMIT :limit")
    suspend fun completed(limit: Int): List<ScanEntity>

    @Query("UPDATE scans SET stage = :stage, progress = :progress, message = :message WHERE id = :id")
    suspend fun updateProgress(id: String, stage: String, progress: Float, message: String?)

    @Query(
        """
        UPDATE scans SET status = 'COMPLETED', progress = 1.0, stage = 'COMPLETED',
            packageName = :packageName, versionName = :versionName, fileSizeBytes = :fileSize,
            sha256 = :sha256, securityScore = :securityScore, performanceScore = :performanceScore,
            criticalCount = :critical, highCount = :high, mediumCount = :medium, lowCount = :low,
            infoCount = :info, finishedAtEpochMs = :finishedAt, durationMs = :duration,
            reportJson = :reportJson, keptApkPath = :keptApkPath
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: String,
        packageName: String?,
        versionName: String?,
        fileSize: Long,
        sha256: String,
        securityScore: Int?,
        performanceScore: Int?,
        critical: Int,
        high: Int,
        medium: Int,
        low: Int,
        info: Int,
        finishedAt: Long,
        duration: Long,
        reportJson: String,
        keptApkPath: String?
    )

    @Query(
        """
        UPDATE scans SET status = :status, errorCode = :code, errorMessage = :message,
            errorHint = :hint, finishedAtEpochMs = :finishedAt
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: String, status: String, code: String?, message: String?, hint: String?, finishedAt: Long)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scans")
    suspend fun deleteAll()

    @Query("SELECT id FROM scans")
    suspend fun allIds(): List<String>

    @Query("SELECT COUNT(*) FROM scans")
    suspend fun count(): Int
}
