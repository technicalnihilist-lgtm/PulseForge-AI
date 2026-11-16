package com.pulseforge.report

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared formatting helpers so JSON, Markdown and PDF agree on units and dates. */
object ReportFormat {

    /**
     * Every number here is formatted with [Locale.ROOT]. The default-locale form wrote "1,5 MB" in a
     * comma-decimal language, so the exported JSON, Markdown and PDF differed per device.
     */
    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", value / (1024.0 * 1024 * 1024))
    }

    /**
     * One formatter per thread instead of one per call. SimpleDateFormat is not thread-safe and
     * construction plus TimeZone lookup is the expensive part, so it is built once per thread.
     */
    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun date(epochMs: Long): String = dateFormat.get()!!.format(Date(epochMs)) + " UTC"

    fun duration(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        else -> String.format(Locale.ROOT, "%d m %d s", ms / 60_000, (ms % 60_000) / 1000)
    }

    /** Compiled once: the export path built a new pattern for every file name it sanitised. */
    private val unsafeFileNameChars = Regex("[^A-Za-z0-9._-]")

    fun fileBaseName(fileName: String): String {
        val cleaned = fileName.substringBeforeLast('.').replace(unsafeFileNameChars, "_")
        return cleaned.ifBlank { "scan" }
    }

    fun reportFileName(sourceFileName: String, extension: String, reportId: String): String =
        "${fileBaseName(sourceFileName)}-pulseforge-${reportId.take(8)}.$extension"
}
