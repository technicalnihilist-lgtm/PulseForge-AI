package com.pulseforge.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pulseforge.apk.ApkException
import com.pulseforge.data.ScanRepository
import com.pulseforge.model.ScanErrorCode
import com.pulseforge.model.ScanStatus
import com.pulseforge.report.ScanPipeline
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Runs one scan in the background.
 *
 * Progress is written to the database (so the UI can observe it after a process restart) and to
 * WorkManager's own progress. Cancellation is cooperative: the analyser checks the coroutine
 * context between stages.
 */
class ScanWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: ScanRepository,
    private val pipeline: ScanPipeline
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scanId = inputData.getString(KEY_SCAN_ID) ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "imported.apk"
        val path = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val keepCopy = inputData.getBoolean(KEY_KEEP_COPY, true)

        val file = File(path)
        var lastWrite = 0L

        try {
            val report = pipeline.run(file, displayName) { stage, fraction, message ->
                val now = System.currentTimeMillis()
                if (now - lastWrite > 150 || fraction >= 1f) {
                    lastWrite = now
                    repository.updateProgress(scanId, stage.name, fraction, message)
                    setProgress(workDataOf(KEY_PROGRESS to fraction, KEY_STAGE to stage.name, KEY_MESSAGE to message))
                }
            }

            repository.markCompleted(scanId, report, if (keepCopy) file.absolutePath else null)
            if (!keepCopy) repository.deleteTemporaryCopy(scanId)
            return Result.success(workDataOf(KEY_SCAN_ID to scanId))
        } catch (cancelled: CancellationException) {
            repository.markFailed(
                scanId,
                ScanStatus.CANCELLED,
                ScanErrorCode.CANCELLED.name,
                "The scan was cancelled",
                "No partial report was saved. You can start the scan again at any time."
            )
            throw cancelled
        } catch (e: ApkException) {
            repository.markFailed(scanId, ScanStatus.FAILED, e.code.name, e.message, e.hint)
            return Result.failure(workDataOf(KEY_ERROR_CODE to e.code.name, KEY_ERROR_MESSAGE to e.message))
        } catch (e: OutOfMemoryError) {
            repository.markFailed(
                scanId,
                ScanStatus.FAILED,
                ScanErrorCode.OUT_OF_MEMORY.name,
                "The device ran out of memory while analysing this APK",
                "Close other apps and retry on a device with more RAM."
            )
            return Result.failure()
        } catch (e: Exception) {
            repository.markFailed(
                scanId,
                ScanStatus.FAILED,
                ScanErrorCode.INTERNAL_ERROR.name,
                e.message ?: e::class.simpleName,
                "This is unexpected. The report was not saved; please retry, and share the scan details if it repeats."
            )
            return Result.failure()
        }
    }

    companion object {
        const val KEY_SCAN_ID = "scanId"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_KEEP_COPY = "keepCopy"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR_CODE = "errorCode"
        const val KEY_ERROR_MESSAGE = "errorMessage"

        fun uniqueName(scanId: String) = "scan-$scanId"
    }
}
