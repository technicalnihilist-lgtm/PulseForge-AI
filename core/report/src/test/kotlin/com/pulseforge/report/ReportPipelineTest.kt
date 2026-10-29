package com.pulseforge.report

import com.pulseforge.model.ScanReport
import com.pulseforge.model.ScanStage
import com.pulseforge.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class ReportPipelineTest {

    companion object {
        private lateinit var insecure: ScanReport
        private lateinit var clean: ScanReport

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")
            val pipeline = ScanPipeline(appVersion = "test")
            insecure = runBlocking { pipeline.run(File(fixtures, "fixture-insecure.apk"), "fixture-insecure.apk") }
            clean = runBlocking { pipeline.run(File(fixtures, "fixture-clean.apk"), "fixture-clean.apk") }
        }
    }

    @Test
    fun `pipeline produces a complete report`() {
        assertEquals(1, insecure.schemaVersion)
        assertTrue(insecure.findings.isNotEmpty())
        assertNotNull(insecure.apk.signing)
        assertTrue(insecure.meta.limitations.size >= 5)
        assertFalse(insecure.meta.isSampleData)
        assertEquals("Static analysis, on device, no execution", insecure.meta.analysisMode)
    }

    @Test
    fun `progress reaches completion and is monotonic`() {
        val stages = mutableListOf<ScanStage>()
        val fractions = mutableListOf<Float>()
        runBlocking {
            ScanPipeline(appVersion = "test").run(
                File(System.getProperty("pulseforge.fixtures") ?: "fixtures", "fixture-clean.apk"),
                "clean"
            ) { stage, fraction, _ ->
                stages.add(stage)
                fractions.add(fraction)
            }
        }
        assertEquals(ScanStage.COMPLETED, stages.last())
        assertEquals(1f, fractions.last())
        assertEquals(fractions.sorted(), fractions)
    }

    @Test
    fun `scores are bounded and explained`() {
        val score = insecure.scoring.security!!
        assertTrue(score in 0..100)
        val deducted = insecure.scoring.securityBreakdown.sumOf { it.points }
        assertEquals((100 - deducted).coerceIn(0, 100), score)
        assertTrue(insecure.scoring.disclaimer.contains("not a certification"))
        assertTrue(insecure.scoring.coverage.isNotEmpty())
    }

    @Test
    fun `clean fixture scores higher than insecure`() {
        assertTrue(clean.scoring.security!! > insecure.scoring.security!!)
    }

    @Test
    fun `json round trip preserves the report`() {
        val json = JsonReportWriter.write(insecure)
        val restored = JsonReportWriter.read(json)
        assertEquals(insecure.reportId, restored.reportId)
        assertEquals(insecure.findings.size, restored.findings.size)
        assertEquals(insecure.scoring.security, restored.scoring.security)
        assertEquals(insecure.apk.sha256, restored.apk.sha256)
        assertTrue(json.contains("\n  "))
    }

    @Test
    fun `json never contains a raw secret`() {
        val json = JsonReportWriter.write(insecure)
        assertFalse(json.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(json.contains("AIzaSyDummyKeyForTesting1234567890abcd"))
    }

    @Test
    fun `markdown contains the expected sections`() {
        val md = MarkdownReportWriter.write(insecure)
        listOf(
            "# PulseForge AI", "## Summary", "## Security findings", "## Performance",
            "## Optimization plan", "## Privacy", "## Manifest", "## Signing", "## Limitations"
        ).forEach { assertTrue("missing section: $section", md.contains(it)) }
        assertFalse(md.contains("AKIAIOSFODNN7EXAMPLE"))
        assertTrue(md.contains("How the security score was computed"))
    }

    @Test
    fun `diff reports improvements between two builds`() {
        val delta = ReportDiff.compare(insecure, clean)
        assertTrue(delta.sizeDeltaBytes < 0)
        assertTrue(delta.findingsResolved.isNotEmpty())
        assertTrue((delta.scoreDelta ?: 0) > 0)
    }

    @Test
    fun `severity counts match the findings`() {
        assertEquals(insecure.findings.size, insecure.counts.total)
        assertEquals(insecure.findings.count { it.severity == Severity.CRITICAL }, insecure.counts.critical)
    }

    @Test
    fun `file naming is stable and safe`() {
        val name = ReportFormat.reportFileName("My App (v2).apk", "json", "abcdef1234567890")
        assertTrue(name.startsWith("My_App__v2_"))
        assertFalse(name.contains(" "))
    }
}
