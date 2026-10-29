package com.pulseforge.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display formatting shared by every screen. */
object UiFormat {

    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
        else -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
    }

    fun count(value: Int): String = if (value >= 10_000) "%,d".format(value) else value.toString()

    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("dd MMM yyyy - HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun relative(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMs
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} h ago"
            diff < 604_800_000 -> "${diff / 86_400_000} d ago"
            else -> dateTime(epochMs)
        }
    }

    fun duration(ms: Long): String = when {
        ms < 1000 -> "$ms ms"
        ms < 60_000 -> "%.1f s".format(ms / 1000.0)
        else -> "%d m %d s".format(ms / 60_000, (ms % 60_000) / 1000)
    }

    fun percent(fraction: Float): String = "%.0f%%".format(fraction * 100)
}
