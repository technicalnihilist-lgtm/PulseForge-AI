package com.pulseforge.apk

import com.pulseforge.model.ScanErrorCode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A single archive entry with the numbers the analysers need. */
data class ArchiveEntry(
    val path: String,
    val uncompressedBytes: Long,
    val compressedBytes: Long,
    val crc32: Long,
    val isDirectory: Boolean,
    val method: Int
) {
    /**
     * Computed once per entry. [EntryCategory.of] lower-cases the whole path, and the analyser asks
     * for the category several times per entry (size grouping, largest entries, summary rows).
     */
    val category: String by lazy(LazyThreadSafetyMode.NONE) { EntryCategory.of(path) }
}

/** Groups archive paths into the buckets shown in the size-composition chart. */
object EntryCategory {
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")
    private val AUDIO_EXT = setOf("mp3", "ogg", "wav", "m4a", "aac", "opus", "flac")
    private val FONT_EXT = setOf("ttf", "otf", "woff", "woff2")

    fun of(path: String): String {
        val lower = path.lowercase()
        val ext = lower.substringAfterLast('.', "")
        return when {
            lower.startsWith("classes") && lower.endsWith(".dex") -> "DEX bytecode"
            lower.startsWith("lib/") -> "Native libraries"
            (lower.startsWith("res/") || lower.startsWith("assets/")) && ext in IMAGE_EXT -> "Images"
            (lower.startsWith("res/") || lower.startsWith("assets/")) && ext in FONT_EXT -> "Fonts"
            (lower.startsWith("res/") || lower.startsWith("assets/")) && ext in AUDIO_EXT -> "Audio"
            lower.startsWith("res/") -> "Other resources"
            lower.startsWith("assets/") -> "Assets"
            lower.startsWith("meta-inf/") -> "Manifest & signature"
            lower == "androidmanifest.xml" || lower == "resources.arsc" -> "Manifest & signature"
            else -> "Other"
        }
    }

    fun isImage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_EXT
}

/**
 * Read-only wrapper around a ZIP/APK. Enforces limits so a hostile or huge file cannot exhaust
 * memory, and never extracts entries to disk.
 */
class ZipArchive private constructor(
    private val zip: ZipFile,
    val entries: List<ArchiveEntry>
) : AutoCloseable {

    fun readEntry(path: String, maxBytes: Long = 64L * 1024 * 1024): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > maxBytes) {
            throw ApkException(
                ScanErrorCode.TOO_LARGE,
                "Entry $path is ${entry.size} bytes, above the ${maxBytes / (1024 * 1024)} MB analysis cap",
                "Large entries are skipped to keep memory bounded. Nothing was executed."
            )
        }
        return zip.getInputStream(entry).use { input -> readExactly(input, entry.size) }
    }

    /**
     * Reads one entry into a single exactly-sized array.
     *
     * The central directory already declares the uncompressed size, so the buffer is allocated once
     * and filled in place. [java.io.InputStream.readBytes] instead grows a buffer and copies it on
     * every doubling, which doubled the peak memory and the garbage for a multi-MB `resources.arsc`.
     * A short or long entry (a header that disagrees with the data) still yields exactly the bytes
     * the stream produced, so callers see no difference.
     */
    private fun readExactly(input: java.io.InputStream, declared: Long): ByteArray {
        if (declared <= 0L || declared > Int.MAX_VALUE) return input.readBytes()
        val buffer = ByteArray(declared.toInt())
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        if (total == buffer.size) {
            val extra = input.read()
            if (extra < 0) return buffer
            val rest = input.readBytes()
            return ByteArray(buffer.size + 1 + rest.size).also { out ->
                System.arraycopy(buffer, 0, out, 0, buffer.size)
                out[buffer.size] = extra.toByte()
                System.arraycopy(rest, 0, out, buffer.size + 1, rest.size)
            }
        }
        val rest = input.readBytes()
        return ByteArray(total + rest.size).also { out ->
            System.arraycopy(buffer, 0, out, 0, total)
            System.arraycopy(rest, 0, out, total, rest.size)
        }
    }

    /** Reads only the first [maxBytes] of an entry, for header sniffing without loading the whole file. */
    fun readHead(path: String, maxBytes: Int): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory) return null
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, total, maxBytes - total)
                if (read <= 0) break
                total += read
            }
            return if (total == 0) null else buffer.copyOf(total)
        }
    }

    fun hasEntry(path: String): Boolean = zip.getEntry(path) != null

    fun entrySize(path: String): Long = zip.getEntry(path)?.size ?: -1L

    fun sha256OfEntry(path: String, byteLimit: Long): String? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size > byteLimit) return null
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    override fun close() = zip.close()

    companion object {
        const val MAX_ENTRIES = 200_000

        fun open(file: File): ZipArchive {
            if (!file.exists()) {
                throw ApkException(
                    ScanErrorCode.FILE_NOT_FOUND,
                    "The selected file is no longer available",
                    "Re-import the APK. Access to the original document may have been revoked."
                )
            }
            if (!file.canRead()) {
                throw ApkException(
                    ScanErrorCode.FILE_NOT_FOUND,
                    "The selected file cannot be read",
                    "Check the file permissions or copy it to local storage and import again."
                )
            }
            if (file.length() < 4) {
                throw ApkException(
                    ScanErrorCode.NOT_AN_APK,
                    "The file is empty",
                    "Choose a valid .apk file exported from a build or a trusted source."
                )
            }
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) }
            if (!(magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())) {
                throw ApkException(
                    ScanErrorCode.NOT_AN_APK,
                    "This file is not a ZIP/APK archive",
                    "PulseForge only analyses .apk files. Pick a file that starts with the ZIP signature."
                )
            }
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                throw ApkException(
                    ScanErrorCode.UNREADABLE_ARCHIVE,
                    "The archive is damaged: ${e.message ?: e::class.simpleName}",
                    "Re-download the APK; a truncated or re-zipped file cannot be analysed."
                )
            }
            // One pass over the central directory: the entry list is built once and both the safety
            // cap and the callers read it. It used to be enumerated twice (count, then list).
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { e -> toEntry(e) }
                .toList()
            if (entries.size > MAX_ENTRIES) {
                zip.close()
                throw ApkException(
                    ScanErrorCode.TOO_LARGE,
                    "The archive contains ${entries.size} entries, above the $MAX_ENTRIES safety cap",
                    "This does not look like a normal APK. PulseForge refuses to analyse it."
                )
            }
            return ZipArchive(zip, entries)
        }

        private fun toEntry(e: ZipEntry) = ArchiveEntry(
            path = e.name,
            uncompressedBytes = if (e.size < 0) 0 else e.size,
            compressedBytes = if (e.compressedSize < 0) 0 else e.compressedSize,
            crc32 = e.crc,
            isDirectory = e.isDirectory,
            method = e.method
        )
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Two characters per byte written straight into a char array. The previous version called
 * `"%02x".format(b)` per byte, which allocates a Formatter and a StringBuilder for every one of
 * the bytes in an APK or certificate digest. The output is identical.
 */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[value ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(out)
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}
