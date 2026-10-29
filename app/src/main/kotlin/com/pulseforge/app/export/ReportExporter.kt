package com.pulseforge.app.export

import android.content.Context
import com.pulseforge.model.ScanReport
import com.pulseforge.report.JsonReportWriter
import com.pulseforge.report.MarkdownReportWriter
import com.pulseforge.report.PdfReportWriter
import com.pulseforge.report.ReportFormat
import java.io.ByteArrayOutputStream

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF document"),
    JSON("json", "application/json", "JSON (machine readable)"),
    MARKDOWN("md", "text/markdown", "Markdown (readable text)")
}

/** Turns a report into bytes for the Storage Access Framework. */
object ReportExporter {

    fun fileName(report: ScanReport, format: ExportFormat): String =
        ReportFormat.reportFileName(report.apk.fileName, format.extension, report.reportId)

    fun bytes(context: Context, report: ScanReport, format: ExportFormat): ByteArray = when (format) {
        ExportFormat.JSON -> JsonReportWriter.write(report).toByteArray()
        ExportFormat.MARKDOWN -> MarkdownReportWriter.write(report).toByteArray()
        ExportFormat.PDF -> ByteArrayOutputStream().use { out ->
            PdfReportWriter().write(report, out)
            out.toByteArray()
        }
    }
}
