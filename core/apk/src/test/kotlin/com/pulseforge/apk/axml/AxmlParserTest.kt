package com.pulseforge.apk.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AxmlParserTest {

    private val fixtures = File(System.getProperty("pulseforge.fixtures") ?: "fixtures")

    private fun manifestBytes(apk: String): ByteArray {
        ZipFile(File(fixtures, apk)).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: error("no manifest in $apk")
            return zip.getInputStream(entry).readBytes()
        }
    }

    @Test
    fun `parses root element and attributes`() {
        val root = AxmlParser.parse(manifestBytes("fixture-insecure.apk"))
        assertEquals("manifest", root.name)
        assertEquals("com.pulseforge.fixture.insecure", root.attrValue("package"))
        assertEquals("7", root.attrValue("versionCode"))
        assertNotNull(root.child("application"))
    }

    @Test
    fun `resolves typed booleans and integers`() {
        val root = AxmlParser.parse(manifestBytes("fixture-insecure.apk"))
        val application = root.child("application")!!
        assertEquals(true, application.boolAttr("debuggable"))
        assertEquals(21, root.child("uses-sdk")!!.intAttr("minSdkVersion"))
        assertEquals(30, root.child("uses-sdk")!!.intAttr("targetSdkVersion"))
    }

    @Test
    fun `builds the component tree`() {
        val root = AxmlParser.parse(manifestBytes("fixture-insecure.apk"))
        val activities = root.child("application")!!.childrenNamed("activity")
        assertEquals(2, activities.size)
        val deepLink = activities.first { it.attrValue("name")!!.endsWith("DeepLinkActivity") }
        val filter = deepLink.child("intent-filter")!!
        assertTrue(filter.childrenNamed("data").any { it.attrValue("scheme") == "http" })
    }

    @Test
    fun `rejects garbage input`() {
        try {
            AxmlParser.parse(ByteArray(16) { 0x42 })
            error("expected failure")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("manifest", true) || e.message!!.contains("XML", true))
        }
    }
}
