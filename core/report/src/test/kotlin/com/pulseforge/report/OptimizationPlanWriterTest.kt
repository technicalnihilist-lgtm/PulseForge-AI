package com.pulseforge.report

import com.pulseforge.performance.BuildRecipeGenerator
import com.pulseforge.performance.OptimizationPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class OptimizationPlanWriterTest {

    companion object {
        private lateinit var report: com.pulseforge.model.ScanReport

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")
            report = runBlocking {
                ScanPipeline(appVersion = "test").run(File(fixtures, "fixture-insecure.apk"), "fixture-insecure.apk")
            }
        }
    }

    @Test
    fun `plan markdown has the expected structure`() {
        val plan = OptimizationPlanner.plan(report.findings, report.performance, report.apk, userOwnsSource = true)
        val recipe = BuildRecipeGenerator.generate(report)
        val md = OptimizationPlanWriter.write(report, plan, recipe)

        listOf("# Optimization plan", "## Summary", "## Actions, in order", "## Build recipe", "## Notes",
            "## What this plan cannot do").forEach {
            assertTrue("missing: $it", md.contains(it))
        }
        assertTrue(md.contains("com.pulseforge.fixture.insecure"))
        assertTrue(md.contains("```kotlin"))
        assertTrue(md.contains("does not rebuild or re-sign"))
    }

    @Test
    fun `plan markdown leaks no secret values`() {
        val plan = OptimizationPlanner.plan(report.findings, report.performance, report.apk, userOwnsSource = true)
        val md = OptimizationPlanWriter.write(report, plan, BuildRecipeGenerator.generate(report))
        assertFalse(md.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(md.contains("AIzaSyDummyKeyForTesting1234567890abcd"))
    }
}
