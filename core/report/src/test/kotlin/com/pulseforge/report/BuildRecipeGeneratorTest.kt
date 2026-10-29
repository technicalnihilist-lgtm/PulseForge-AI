package com.pulseforge.report

import com.pulseforge.model.Impact
import com.pulseforge.model.ScanReport
import com.pulseforge.performance.BuildRecipeGenerator
import com.pulseforge.performance.OptimizationPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The recipe lives in :core:performance but needs a full report, so its test lives here. */
class BuildRecipeGeneratorTest {

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
    fun `always includes the shrinker and baseline profile sections`() {
        val ids = BuildRecipeGenerator.generate(insecure).sections.map { it.id }
        assertTrue(ids.contains("r8"))
        assertTrue(ids.contains("res-shrink"))
        assertTrue(ids.contains("baseline"))
        assertTrue(ids.contains("deps"))
    }

    @Test
    fun `duplicate and oversized assets drive their own sections with estimates`() {
        val recipe = BuildRecipeGenerator.generate(insecure)
        val dedupe = recipe.sections.first { it.id == "dedupe" }
        assertTrue((dedupe.estimatedSavingBytes ?: 0) > 0)
        assertTrue(dedupe.rationale.contains("byte-identical"))

        val images = recipe.sections.first { it.id == "images" }
        assertTrue((images.estimatedSavingBytes ?: 0) > 0)
        assertTrue("the saving must be labelled an estimate", images.rationale.contains("estimate"))
    }

    @Test
    fun `multi-abi apk gets an abi split section`() {
        val abis = BuildRecipeGenerator.generate(insecure).sections.first { it.id == "abis" }
        assertTrue(abis.snippet.contains("splits"))
        assertTrue((abis.estimatedSavingBytes ?: 0) > 0)
    }

    @Test
    fun `clean fixture has no duplicate or image sections`() {
        val ids = BuildRecipeGenerator.generate(clean).sections.map { it.id }
        assertFalse(ids.contains("dedupe"))
        assertFalse(ids.contains("images"))
    }

    @Test
    fun `total saving is the sum of section estimates`() {
        val recipe = BuildRecipeGenerator.generate(insecure)
        assertEquals(recipe.sections.sumOf { it.estimatedSavingBytes ?: 0L }, recipe.estimatedSavingBytes)
        assertTrue(recipe.estimatedSavingBytes > 0)
    }

    @Test
    fun `recipe never claims to rebuild the apk`() {
        val recipe = BuildRecipeGenerator.generate(insecure, userOwnsSource = false)
        assertTrue(recipe.notes.any { it.contains("only helps an app you can rebuild") })
        assertTrue(recipe.notes.any { it.contains("informational") })
        assertTrue(recipe.sections.all { it.snippet.isNotBlank() && it.target.isNotBlank() })
    }

    @Test
    fun `planner orders actions by impact`() {
        val plan = OptimizationPlanner.plan(insecure.findings, insecure.performance, insecure.apk, userOwnsSource = true)
        assertTrue(plan.isNotEmpty())
        assertEquals(Impact.HIGH, plan.first().impact)
    }
}
