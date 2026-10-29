package com.pulseforge.report

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared formatting helpers so JSON, Markdown and PDF agree on units and dates. */
object ReportFormat {

    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
        else -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
    }

    fun date(epochMs: Long): String {
        val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMs)) + " UTC"
    }

    fun duration(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> "%.1f s".format(ms / 1000.0)
        else -> "%d m %d s".format(ms / 60_000, (ms % 60_000) / 1000)
    }

    fun fileBaseName(fileName: String): String {
        val cleaned = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "scan" }
    }

    fun reportFileName(sourceFileName: String, extension: String, reportId: String): String =
        "${fileBaseName(sourceFileName)}-pulseforge-${reportId.take(8)}.$extension"
}
