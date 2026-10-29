package com.pulseforge.app.ui

import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.ScanReport
import com.pulseforge.report.ScanPipeline
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Produces the report used by the UI tests.
 *
 * It runs the real pipeline over a synthetic fixture APK, so the screens are exercised with genuine
 * findings instead of hand-written mock data. Built once per JVM and cached.
 */
object TestReportFactory {

    private var cached: ScanReport? = null

    fun report(): ScanReport = cached ?: synchronized(this) {
        cached ?: runPipeline().also { cached = it }
    }

    private fun runPipeline(): ScanReport {
        val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")
        val apk = File(fixtures, "fixture-insecure.apk")
        require(apk.exists()) { "Fixture APK missing at ${apk.absolutePath}. Run tools/make-fixtures.py first." }
        return runBlocking { ScanPipeline(appVersion = "test").run(apk, "fixture-insecure.apk") }
    }

    fun scanEntity(report: ScanReport): ScanEntity = ScanEntity(
        id = report.reportId,
        displayName = report.apk.fileName,
        packageName = report.apk.manifest?.packageName,
        versionName = report.apk.manifest?.versionName,
        fileSizeBytes = report.apk.fileSizeBytes,
        sha256 = report.apk.sha256,
        status = "COMPLETED",
        stage = "COMPLETED",
        progress = 1f,
        securityScore = report.scoring.security,
        performanceScore = report.scoring.performance,
        criticalCount = report.counts.critical,
        highCount = report.counts.high,
        mediumCount = report.counts.medium,
        lowCount = report.counts.low,
        infoCount = report.counts.info,
        startedAtEpochMs = report.meta.startedAtEpochMs,
        finishedAtEpochMs = report.generatedAtEpochMs,
        durationMs = report.meta.durationMs
    )
}
