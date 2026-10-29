package com.pulseforge.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/** Enqueues and cancels scans. One scan id maps to exactly one unique work request. */
class ScanScheduler(private val context: Context) {

    fun enqueue(scanId: String, file: File, displayName: String, keepCopy: Boolean) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(
                workDataOf(
                    ScanWorker.KEY_SCAN_ID to scanId,
                    ScanWorker.KEY_FILE_PATH to file.absolutePath,
                    ScanWorker.KEY_DISPLAY_NAME to displayName,
                    ScanWorker.KEY_KEEP_COPY to keepCopy
                )
            )
            .setConstraints(Constraints.Builder().build())
            .addTag(TAG_SCAN)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ScanWorker.uniqueName(scanId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(scanId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(ScanWorker.uniqueName(scanId))
    }

    companion object {
        const val TAG_SCAN = "pulseforge-scan"
    }
}
