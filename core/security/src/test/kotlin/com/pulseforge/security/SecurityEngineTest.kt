package com.pulseforge.security

import com.pulseforge.apk.ApkAnalyzer
import com.pulseforge.apk.ApkScanInput
import com.pulseforge.model.EvidenceType
import com.pulseforge.model.Finding
import com.pulseforge.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Rule-level verification against the signed fixtures. */
class SecurityEngineTest {

    companion object {
        private lateinit var insecure: ApkScanInput
        private lateinit var insecureFindings: List<Finding>
        private lateinit var cleanFindings: List<Finding>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")
            val analyzer = ApkAnalyzer()
            insecure = runBlocking { analyzer.analyze(File(fixtures, "fixture-insecure.apk"), "fixture-insecure.apk") }
            val clean = runBlocking { analyzer.analyze(File(fixtures, "fixture-clean.apk"), "fixture-clean.apk") }
            insecureFindings = SecurityEngine().evaluate(insecure)
            cleanFindings = SecurityEngine().evaluate(clean)
        }
    }

    private fun rulesOf(findings: List<Finding>) = findings.map { it.ruleId }.toSet()

    @Test
    fun `insecure fixture triggers the expected rule families`() {
        val rules = rulesOf(insecureFindings)
        listOf(
            "PF-MAN-001", "PF-MAN-002", "PF-MAN-003", "PF-MAN-005", "PF-MAN-007", "PF-MAN-008",
            "PF-WEB-001", "PF-CRY-001", "PF-CRY-002", "PF-PRM-001", "PF-COD-001", "PF-NET-003", "PF-CMP-001"
        ).forEach { rule ->
            assertTrue("expected rule $rule; got $rules", rules.any { it == rule || it.startsWith("$rule-") })
        }
    }

    @Test
    fun `secrets are reported and redacted`() {
        val secretFindings = insecureFindings.filter { it.ruleId.startsWith("PF-SEC-001") }
        assertTrue(secretFindings.isNotEmpty())
        val aws = secretFindings.first { it.ruleId == "PF-SEC-001-AWS" }
        assertEquals(Severity.CRITICAL, aws.severity)

        val values = secretFindings.flatMap { it.evidence }.map { it.value }
        assertFalse(values.any { it.contains("AKIAIOSFODNN7EXAMPLE") })
        assertTrue(values.any { it.contains('*') })
    }

    @Test
    fun `debuggable finding is critical and observation-based`() {
        val debuggable = insecureFindings.first { it.ruleId == "PF-MAN-005" }
        assertEquals(Severity.CRITICAL, debuggable.severity)
        assertEquals(EvidenceType.DIRECT_OBSERVATION, debuggable.evidenceType)
        assertTrue(debuggable.cweIds.contains("CWE-489"))
        assertTrue(debuggable.masvsControls.isNotEmpty())
        assertNotNull(debuggable.falsePositiveNote)
    }

    @Test
    fun `exported components name the component and export source`() {
        val exported = insecureFindings.filter { it.ruleId == "PF-MAN-001" }
        assertTrue(exported.size >= 4)
        val provider = exported.first { it.affectedComponent?.contains("DataProvider") == true }
        assertTrue(provider.evidence.any { it.label == "Permission" && it.value == "none" })
    }

    @Test
    fun `clean fixture has no high or critical findings`() {
        val serious = cleanFindings.filter { it.severity.rank >= Severity.HIGH.rank }
        assertTrue(
            "clean fixture should not produce high/critical findings: " + serious.joinToString { "${it.ruleId}:${it.title}" },
            serious.isEmpty()
        )
        val rules = rulesOf(cleanFindings)
        assertFalse(rules.contains("PF-MAN-005"))
        assertFalse(rules.contains("PF-MAN-008"))
        assertFalse(rules.contains("PF-CRY-001"))
    }

    @Test
    fun `findings are ordered by severity with unique ids`() {
        val ranks = insecureFindings.map { it.severity.rank }
        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals(insecureFindings.size, insecureFindings.map { it.id }.distinct().size)
    }

    @Test
    fun `every finding carries remediation`() {
        insecureFindings.forEach {
            assertTrue(it.remediation.isNotBlank())
            assertTrue(it.summary.isNotBlank())
            assertTrue(it.technicalDetail.isNotBlank())
        }
    }

    @Test
    fun `privacy analysis groups permissions`() {
        val privacy = PrivacyAnalyzer.analyse(insecure)
        assertTrue(privacy.permissionGroups.any { it.group == "SMS" })
        assertTrue(privacy.dangerousPermissionCount >= 5)
        assertEquals(Severity.CRITICAL, PrivacyAnalyzer.highestRisk(privacy))
    }

    @Test
    fun `rule catalog is versioned`() {
        assertTrue(RuleCatalog.VERSION.isNotBlank())
        assertTrue(RuleCatalog.rules.size >= 20)
        assertEquals(RuleCatalog.rules.size, RuleCatalog.ids.distinct().size)
    }
}
