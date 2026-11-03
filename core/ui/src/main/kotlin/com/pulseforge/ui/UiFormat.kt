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

    fun dateTime(epochMs: Long): String {
        val locale = Locale.getDefault()
        val cached = formatter.get()!!
        val format = if (cached.first == locale) {
            cached.second
        } else {
            newFormat(locale).also { formatter.set(locale to it) }
        }
        return format.format(Date(epochMs))
    }

    /**
     * Built once per thread and locale. Constructing a SimpleDateFormat parses the pattern and
     * resolves the locale's date symbols, and this is called for every row of the dashboard and
     * history lists, so building one per call showed up while scrolling.
     */
    private val formatter = object : ThreadLocal<Pair<Locale, SimpleDateFormat>>() {
        override fun initialValue(): Pair<Locale, SimpleDateFormat> {
            val locale = Locale.getDefault()
            return locale to newFormat(locale)
        }
    }

    private fun newFormat(locale: Locale): SimpleDateFormat =
        SimpleDateFormat("dd MMM yyyy - HH:mm", locale)

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
