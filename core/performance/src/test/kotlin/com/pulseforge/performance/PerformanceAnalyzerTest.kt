package com.pulseforge.performance

import com.pulseforge.apk.ApkAnalyzer
import com.pulseforge.apk.ApkScanInput
import com.pulseforge.model.Confidence
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.FindingCategory
import com.pulseforge.model.Impact
import com.pulseforge.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class PerformanceAnalyzerTest {

    companion object {
        private lateinit var insecure: ApkScanInput
        private lateinit var clean: ApkScanInput

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")
            val analyzer = ApkAnalyzer()
            insecure = runBlocking { analyzer.analyze(File(fixtures, "fixture-insecure.apk"), "fixture-insecure.apk") }
            clean = runBlocking { analyzer.analyze(File(fixtures, "fixture-clean.apk"), "fixture-clean.apk") }
        }
    }

    @Test
    fun `measured metrics come from the file`() {
        val perf = PerformanceAnalyzer.analyse(insecure)
        val labels = perf.measured.map { it.label }
        assertTrue(labels.contains("APK size"))
        assertTrue(labels.contains("DEX files"))
        assertTrue(perf.measured.first { it.label == "APK size" }.value.contains("MB"))
    }

    @Test
    fun `unavailable metrics are explicit`() {
        val perf = PerformanceAnalyzer.analyse(insecure)
        val labels = perf.unavailable.map { it.label }
        assertTrue(labels.any { it.contains("startup", true) })
        assertTrue(labels.any { it.contains("Frame rate", true) })
        perf.unavailable.forEach {
            assertTrue(it.reason.isNotBlank())
            assertTrue(it.howToMeasure.isNotBlank())
        }
        assertTrue(perf.runtimeProfilingNote.contains("test device"))
    }

    @Test
    fun `bitmap memory estimate is derived and labelled`() {
        val perf = PerformanceAnalyzer.analyse(insecure)
        val bitmap = perf.estimates.first { it.label == "Largest bitmap in memory" }
        // 1200 x 1200 x 4 = 5,760,000 bytes = 5.5 MB
        assertTrue("estimate was ${bitmap.estimate}", bitmap.estimate.startsWith("5.5"))
        assertTrue(bitmap.basis.contains("width x height x 4"))
    }

    @Test
    fun `duplicate and image actions carry savings`() {
        val perf = PerformanceAnalyzer.analyse(insecure)
        val ids = perf.actions.map { it.id }
        assertTrue(ids.contains("dedupe-assets"))
        assertTrue(ids.contains("compress-images"))
        assertTrue(ids.contains("split-abi"))
        assertTrue((perf.actions.first { it.id == "dedupe-assets" }.estimatedSavingBytes ?: 0) > 0)
        assertTrue(perf.actions.first { it.id == "compress-images" }.caveats?.contains("Estimate") == true)
    }

    @Test
    fun `clean fixture has no duplicate or oversized assets`() {
        val perf = PerformanceAnalyzer.analyse(clean)
        assertTrue(perf.duplicates.isEmpty())
        assertTrue(perf.oversizedImages.isEmpty())
    }

    @Test
    fun `planner merges security and performance deterministically`() {
        val perf = PerformanceAnalyzer.analyse(insecure)
        val findings = listOf(
            Finding(
                id = "PF-MAN-005",
                ruleId = "PF-MAN-005",
                title = "The APK is debuggable",
                category = FindingCategory.SECURITY,
                severity = Severity.CRITICAL,
                confidence = Confidence.CONFIRMED,
                evidenceType = EvidenceType.DIRECT_OBSERVATION,
                summary = "debuggable",
                technicalDetail = "detail",
                remediation = "set debuggable=false"
            )
        )
        val plan = OptimizationPlanner.plan(findings, perf, insecure.facts, userOwnsSource = true)
        assertEquals(1, plan.first().priority)
        assertEquals(Impact.HIGH, plan.first().impact)
        assertTrue(plan.zipWithNext().all { (a, b) -> a.impact.ordinal <= b.impact.ordinal })
        assertTrue(plan.any { it.origin.startsWith("Security") })
        assertTrue(plan.any { it.origin == "Performance" })
    }

    @Test
    fun `third party apk actions are marked as not editable`() {
        val perf = PerformanceAnalyzer.analyse(insecure)
        val plan = OptimizationPlanner.plan(emptyList(), perf, insecure.facts, userOwnsSource = false)
        assertTrue(plan.all { !it.requiresSourceAccess })
    }

    @Test
    fun `source review proposes concrete changes with tests`() {
        val source = SourceReviewEngine.SourceFile(
            path = "app/src/main/java/com/example/MainActivity.kt",
            content = """
                class MainActivity : Activity() {
                    private val apiKey = "abcd1234secretvalue"
                    override fun onCreate(state: Bundle?) {
                        webView.settings.setJavaScriptEnabled(true)
                        webView.settings.setAllowUniversalAccessFromFileURLs(true)
                        webView.addJavascriptInterface(bridge, "Bridge")
                        val digest = MessageDigest.getInstance("MD5")
                        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                        val random = Random()
                        Log.d("Auth", "token=" + apiKey)
                    }
                }
            """.trimIndent()
        )
        val review = SourceReviewEngine.review(listOf(source))
        val titles = review.suggestions.map { it.title }
        assertTrue(titles.any { it.contains("JavaScript") })
        assertTrue(titles.any { it.contains("cipher", true) })
        assertTrue(titles.any { it.contains("hash", true) })
        assertTrue(titles.any { it.contains("credential", true) })

        val js = review.suggestions.first { it.title.contains("JavaScript") }
        assertEquals(4, js.line)
        assertTrue(js.proposedSnippet.contains("javaScriptEnabled = false"))
        assertTrue(review.suggestions.any { it.testSnippet != null })
        assertTrue(review.notes.any { it.contains("never modifies") })
    }

    @Test
    fun `source review with no files explains itself`() {
        val review = SourceReviewEngine.review(emptyList())
        assertTrue(review.suggestions.isEmpty())
        assertTrue(review.notes.any { it.contains("No source files") })
    }

    @Test
    fun `huge files are skipped without crashing`() {
        val review = SourceReviewEngine.review(listOf(SourceReviewEngine.SourceFile("huge.kt", "x".repeat(2_100_000))))
        assertTrue(review.notes.any { it.contains("skipped") })
        assertFalse(review.suggestions.isNotEmpty())
    }
}
