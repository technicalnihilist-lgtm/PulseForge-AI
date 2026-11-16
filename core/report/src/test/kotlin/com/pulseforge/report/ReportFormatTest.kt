package com.pulseforge.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The unit and file-name helpers every export goes through, plus the final redaction pass.
 *
 * The locale test is the important one: the numbers are pinned to Locale.ROOT so the same APK
 * produces the same JSON, Markdown and PDF on a phone set to a comma-decimal language.
 */
class ReportFormatTest {

    @Test
    fun `sizes and durations keep a dot separator whatever the default locale is`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("900 B", ReportFormat.bytes(900))
            assertEquals("1.5 KB", ReportFormat.bytes(1536))
            assertEquals("1.5 MB", ReportFormat.bytes(1_572_864))
            assertEquals("1.50 GB", ReportFormat.bytes(1024L * 1024 * 1024 * 3 / 2))
            assertEquals("1.5 s", ReportFormat.duration(1500))
            assertEquals("1 m 5 s", ReportFormat.duration(65_000))
            assertEquals("42 ms", ReportFormat.duration(42))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `dates are rendered in UTC`() {
        assertEquals("01 Jan 1970, 00:00 UTC", ReportFormat.date(0))
    }

    @Test
    fun `file names drop the extension and unsafe characters`() {
        assertEquals("My_App__1_", ReportFormat.fileBaseName("My App (1).apk"))
        assertEquals("scan", ReportFormat.fileBaseName(".apk"))
        assertEquals("app-pulseforge-01234567.json", ReportFormat.reportFileName("app.apk", "json", "0123456789abcdef"))
    }

    @Test
    fun `redaction masks credential shapes and url user info`() {
        val masked = ReportRedactor.redact("key AKIAABCDEFGHIJKLMNOP and https://user:pw@example.test/x")
        assertFalse(masked.contains("ABCDEFGHIJKLMNOP"))
        assertTrue(masked.contains("https://***:***@example.test/x"))
    }

    @Test
    fun `redaction masks a credential carried in a query parameter`() {
        val masked = ReportRedactor.redact("https://example.test/cb?token=SUPERSECRETVALUE&next=/home")
        assertFalse(masked.contains("SUPERSECRETVALUE"))
        assertTrue(masked.contains("next=/home"))
    }

    @Test
    fun `ordinary text is left alone`() {
        val text = "This APK requests the CAMERA permission."
        assertEquals(text, ReportRedactor.redact(text))
    }
}
