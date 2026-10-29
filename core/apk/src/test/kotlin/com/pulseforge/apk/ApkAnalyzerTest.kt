package com.pulseforge.apk

import com.pulseforge.model.ComponentType
import com.pulseforge.model.ScanErrorCode
import com.pulseforge.model.ScanStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Integration tests for the parsing pipeline, run against real APKs built by
 * tools/make-fixtures.py (aapt2 + d8 + zipalign + apksigner).
 */
class ApkAnalyzerTest {

    private val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")
    private val analyzer = ApkAnalyzer()

    private fun analyse(name: String) = runBlocking {
        val file = File(fixtures, name)
        assertTrue("fixture $name must exist - run tools/make-fixtures.py", file.exists())
        analyzer.analyze(file, name)
    }

    @Test
    fun `insecure fixture exposes manifest facts`() {
        val input = analyse("fixture-insecure.apk")
        val manifest = input.facts.manifest
        assertNotNull("manifest should parse", manifest)
        manifest!!

        assertEquals("com.pulseforge.fixture.insecure", manifest.packageName)
        assertEquals("1.4.2", manifest.versionName)
        assertEquals(7L, manifest.versionCode)
        assertEquals(21, manifest.minSdk)
        assertEquals(30, manifest.targetSdk)
        assertEquals(true, manifest.debuggable)
        assertEquals(true, manifest.allowBackup)
        assertEquals(true, manifest.usesCleartextTraffic)
        assertNull(input.facts.manifestParseError)
    }

    @Test
    fun `insecure fixture reports components and permissions`() {
        val manifest = analyse("fixture-insecure.apk").facts.manifest!!
        assertEquals(2, manifest.componentsOf(ComponentType.ACTIVITY).size)
        assertEquals(1, manifest.componentsOf(ComponentType.SERVICE).size)
        assertEquals(1, manifest.componentsOf(ComponentType.RECEIVER).size)
        assertEquals(1, manifest.componentsOf(ComponentType.PROVIDER).size)

        val deepLink = manifest.components.first { it.shortName == "DeepLinkActivity" }
        assertTrue(deepLink.exported == true)
        assertTrue(deepLink.intentFilters.first().schemes.contains("http"))

        val permissions = manifest.requestedPermissions
        assertTrue(permissions.contains("android.permission.READ_SMS"))
        assertEquals(8, permissions.size)
    }

    @Test
    fun `insecure fixture reports abis and native libraries`() {
        val resources = analyse("fixture-insecure.apk").facts.resources!!
        assertEquals(listOf("arm64-v8a", "x86_64"), resources.abis)
        assertEquals(2, resources.nativeLibs.size)
    }

    @Test
    fun `dex parsing finds classes strings and api references`() {
        val input = analyse("fixture-insecure.apk")
        assertTrue(input.dex.definedClasses.any { it.contains("InsecureActivity") })
        // setJavaScriptEnabled lives on WebSettings, not WebView - the rule engine must know that.
        assertTrue(input.dex.occurrences("Landroid/webkit/WebSettings;", "setJavaScriptEnabled") > 0)
        assertTrue(input.dex.occurrences("Landroid/webkit/WebView;", "addJavascriptInterface") > 0)
        assertTrue(input.dex.strings.any { it == "MD5" })
        assertTrue(input.dex.strings.any { it.contains("AKIAIOSFODNN7EXAMPLE") })
    }

    @Test
    fun `signature inspection reads the certificate`() {
        val signing = analyse("fixture-insecure.apk").facts.signing!!
        assertTrue("v2 scheme expected, got ${signing.schemes}", signing.schemes.any { it.startsWith("v2") })
        assertTrue("signature must verify", signing.verified)
        val cert = signing.certificate!!
        assertTrue(cert.subject.contains("PulseForge"))
        assertTrue(cert.keySizeBits >= 2048)
        assertTrue(cert.sha256.count { it == ':' } == 31)
    }

    @Test
    fun `resource analysis finds duplicates and oversized images`() {
        val resources = analyse("fixture-insecure.apk").facts.resources!!
        val duplicate = resources.duplicates.first()
        assertTrue(duplicate.paths.contains("assets/large_hero.png"))
        assertTrue(duplicate.wastedBytes > 0)

        val hero = resources.oversizedImages.first { it.path == "assets/large_hero.png" }
        assertEquals(1200, hero.width)
        assertEquals("PNG", hero.format)
    }

    @Test
    fun `clean fixture has hardened defaults`() {
        val manifest = analyse("fixture-clean.apk").facts.manifest!!
        assertEquals(false, manifest.debuggable)
        assertEquals(false, manifest.allowBackup)
        assertEquals(false, manifest.usesCleartextTraffic)
        assertEquals(34, manifest.targetSdk)
        assertNotNull(manifest.networkSecurityConfig)
        assertEquals(false, manifest.components.first { it.shortName == "SettingsActivity" }.exported)
    }

    @Test
    fun `progress stages are reported`() {
        val stages = mutableListOf<ScanStage>()
        runBlocking {
            analyzer.analyze(File(fixtures, "fixture-clean.apk"), "clean") { stage, _, _ -> stages.add(stage) }
        }
        assertEquals(ScanStage.VALIDATING, stages.first())
        assertTrue(stages.contains(ScanStage.PARSING_MANIFEST))
        assertTrue(stages.contains(ScanStage.VERIFYING_SIGNATURE))
    }

    @Test
    fun `non-apk file is rejected with a structured error`() {
        try {
            runBlocking { analyzer.analyze(File(fixtures, "fixture-not-an-apk.bin"), "bad") }
            fail("expected ApkException")
        } catch (e: ApkException) {
            assertEquals(ScanErrorCode.NOT_AN_APK, e.code)
            assertTrue(e.hint.isNotBlank())
        }
    }

    @Test
    fun `truncated apk fails cleanly`() {
        try {
            runBlocking { analyzer.analyze(File(fixtures, "fixture-truncated.apk"), "truncated") }
            fail("expected ApkException for a truncated archive")
        } catch (e: ApkException) {
            assertTrue(e.code == ScanErrorCode.UNREADABLE_ARCHIVE || e.code == ScanErrorCode.CORRUPT_MANIFEST)
        }
    }
}
