package com.pulseforge.data.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.pulseforge.data.ScanRepository
import com.pulseforge.report.ScanPipeline

/** Supplies [ScanWorker] with its dependencies (WorkManager cannot construct it otherwise). */
class PulseWorkerFactory(
    private val repository: ScanRepository,
    private val pipeline: ScanPipeline
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        ScanWorker::class.java.name -> ScanWorker(appContext, workerParameters, repository, pipeline)
        else -> null
    }
}
