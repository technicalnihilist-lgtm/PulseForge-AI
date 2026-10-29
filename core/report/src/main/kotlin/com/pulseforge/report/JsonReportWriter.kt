package com.pulseforge.report

import com.pulseforge.model.ScanReport
import kotlinx.serialization.json.Json

/** Serialises a report to JSON. The schema is versioned via [ScanReport.schemaVersion]. */
object JsonReportWriter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun write(report: ScanReport): String = json.encodeToString(ScanReport.serializer(), report)

    fun read(text: String): ScanReport = json.decodeFromString(ScanReport.serializer(), text)
}
