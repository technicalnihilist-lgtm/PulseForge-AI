package com.pulseforge.apk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the ZIP read paths the analyser leans on for every archive: the capped whole-entry read,
 * the header sniff used for each image, the per-entry digest used to confirm duplicates, and the
 * category helpers the size chart is built from.
 *
 * The archive is built here so the test needs no fixture and stays fast.
 */
class ZipArchiveTest {

    private val shortBody = "PK-header-probe".toByteArray()
    private val longBody = ByteArray(5_000) { index -> (index % 251).toByte() }

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("pulseforge-zip", ".apk")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/short.bin"))
            zip.write(shortBody)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("res/drawable/icon.png"))
            zip.write(longBody)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("empty.txt"))
            zip.closeEntry()
        }
    }

    @Test
    fun `readEntry returns the declared bytes`() {
        ZipArchive.open(file).use { archive ->
            assertArrayEquals(longBody, archive.readEntry("res/drawable/icon.png"))
            assertArrayEquals(shortBody, archive.readEntry("assets/short.bin"))
        }
    }

    @Test
    fun `readEntry refuses an entry above the cap`() {
        ZipArchive.open(file).use { archive ->
            assertThrows(ApkException::class.java) { archive.readEntry("res/drawable/icon.png", 16) }
        }
    }

    @Test
    fun `readHead returns a whole entry that is smaller than the cap`() {
        ZipArchive.open(file).use { archive ->
            assertArrayEquals(shortBody, archive.readHead("assets/short.bin", 4096))
        }
    }

    @Test
    fun `readHead stops at the cap`() {
        ZipArchive.open(file).use { archive ->
            val head = archive.readHead("res/drawable/icon.png", 16)
            assertEquals(16, head?.size)
            assertArrayEquals(longBody.copyOf(16), head)
        }
    }

    @Test
    fun `readHead returns null for a missing or empty entry`() {
        ZipArchive.open(file).use { archive ->
            assertNull(archive.readHead("nope.bin", 16))
            assertNull(archive.readHead("empty.txt", 16))
        }
    }

    @Test
    fun `sha256OfEntry matches the content digest and respects the limit`() {
        ZipArchive.open(file).use { archive ->
            val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(longBody))
            assertEquals(expected, archive.sha256OfEntry("res/drawable/icon.png", 1024L * 1024))
            assertNull(archive.sha256OfEntry("res/drawable/icon.png", 100))
            assertNull(archive.sha256OfEntry("nope.bin", 1024L * 1024))
        }
    }

    @Test
    fun `directories are not listed as entries`() {
        ZipArchive.open(file).use { archive ->
            assertEquals(3, archive.entries.size)
            assertTrue(archive.entries.none { it.isDirectory })
        }
    }

    @Test
    fun `isImage ignores the case of the extension`() {
        assertTrue(EntryCategory.isImage("res/drawable/ICON.PNG"))
        assertTrue(EntryCategory.isImage("assets/logo.webp"))
        assertFalse(EntryCategory.isImage("res/raw/readme.txt"))
        assertFalse(EntryCategory.isImage("assets/noextension"))
        assertFalse(EntryCategory.isImage(""))
    }

    @Test
    fun `categories match the size chart buckets`() {
        assertEquals("DEX bytecode", EntryCategory.of("classes.dex"))
        assertEquals("Native libraries", EntryCategory.of("lib/arm64-v8a/libnative.so"))
        assertEquals("Images", EntryCategory.of("res/drawable/icon.png"))
        assertEquals("Manifest & signature", EntryCategory.of("META-INF/CERT.RSA"))
        assertEquals("Other", EntryCategory.of("unknown.bin"))
    }
}
