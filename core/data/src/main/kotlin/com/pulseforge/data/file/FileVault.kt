package com.pulseforge.data.file

import android.content.Context
import android.net.Uri
import com.pulseforge.apk.ApkException
import com.pulseforge.model.ScanErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the copies of imported APKs.
 *
 * A file is copied from the Storage Access Framework into app-private storage so the analyser can
 * read it repeatedly and so a report can be reproduced. Copies are deleted when the user deletes
 * the scan, and orphaned directories are cleaned on startup.
 */
class FileVault(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "scans").apply { mkdirs() }

    fun directoryFor(scanId: String): File = File(root, scanId)

    fun apkFileFor(scanId: String): File = File(directoryFor(scanId), "input.apk")

    /** Copies the picked document into the vault. Enforces the size cap and the ZIP signature. */
    suspend fun import(uri: Uri, scanId: String, maxBytes: Long = 2L * 1024 * 1024 * 1024): File =
        withContext(Dispatchers.IO) {
            val target = apkFileFor(scanId)
            target.parentFile?.mkdirs()
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw ApkException(
                        ScanErrorCode.FILE_NOT_FOUND,
                        "The selected file could not be opened",
                        "The document provider refused access. Try picking the file again."
                    )
                input.use { source ->
                    target.outputStream().use { sink ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) {
                                target.delete()
                                throw ApkException(
                                    ScanErrorCode.TOO_LARGE,
                                    "The file is larger than the ${maxBytes / (1024 * 1024)} MB limit",
                                    "Analyse a smaller build or split the APK by ABI/DPI."
                                )
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                verifyZipMagic(target)
                target
            } catch (e: ApkException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                target.delete()
                throw ApkException(
                    ScanErrorCode.UNREADABLE_ARCHIVE,
                    "The file could not be copied: ${e.message ?: e::class.simpleName}",
                    "Check that the file is readable and that there is free storage space.",
                    cause = e
                )
            }
        }

    fun delete(scanId: String) {
        directoryFor(scanId).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
        root.mkdirs()
    }

    /** Removes vault directories that no longer have a matching scan row. */
    fun cleanOrphans(keepIds: Set<String>) {
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in keepIds) dir.deleteRecursively()
        }
    }

    fun sizeOf(scanId: String): Long = apkFileFor(scanId).let { if (it.exists()) it.length() else 0L }

    private fun verifyZipMagic(file: File) {
        if (file.length() < 4) {
            throw ApkException(ScanErrorCode.NOT_AN_APK, "The selected file is empty", "Pick a valid .apk file.")
        }
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        if (!(magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())) {
            throw ApkException(
                ScanErrorCode.NOT_AN_APK,
                "This file is not an APK",
                "PulseForge only analyses .apk archives. Choose a file produced by a build."
            )
        }
    }
}
