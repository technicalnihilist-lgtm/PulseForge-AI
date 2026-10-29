package com.pulseforge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pulseforge.model.ScanStatus

/**
 * One scan in the local history. The full report is stored as JSON in [reportJson]; the scalar
 * columns exist so the history list and dashboard can be queried without parsing every report.
 */
@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val packageName: String? = null,
    val versionName: String? = null,
    val fileSizeBytes: Long = 0,
    val sha256: String = "",
    val status: String = ScanStatus.RUNNING.name,
    val stage: String = "",
    val progress: Float = 0f,
    val message: String? = null,
    val securityScore: Int? = null,
    val performanceScore: Int? = null,
    val criticalCount: Int = 0,
    val highCount: Int = 0,
    val mediumCount: Int = 0,
    val lowCount: Int = 0,
    val infoCount: Int = 0,
    val startedAtEpochMs: Long = 0,
    val finishedAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val errorHint: String? = null,
    val reportJson: String? = null,
    val isSampleData: Boolean = false,
    val keptApkPath: String? = null
) {
    val totalFindings: Int get() = criticalCount + highCount + mediumCount + lowCount + infoCount
    val isFinished: Boolean get() = status != ScanStatus.RUNNING.name
}
