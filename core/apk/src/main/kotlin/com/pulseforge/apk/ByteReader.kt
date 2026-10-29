package com.pulseforge.apk

import com.pulseforge.model.ScanErrorCode

/** Little-endian helpers for the binary Android formats (all of them are little-endian). */
internal object ByteReader {

    fun u8(b: ByteArray, off: Int): Int {
        requireRange(b, off, 1)
        return b[off].toInt() and 0xFF
    }

    fun u16(b: ByteArray, off: Int): Int {
        requireRange(b, off, 2)
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(b: ByteArray, off: Int): Long {
        requireRange(b, off, 4)
        return ((b[off].toInt() and 0xFF).toLong()) or
            ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[off + 3].toInt() and 0xFF).toLong() shl 24)
    }

    fun u32Int(b: ByteArray, off: Int): Int = u32(b, off).toInt()

    private fun requireRange(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || off + len > b.size) {
            throw ApkException(
                ScanErrorCode.CORRUPT_MANIFEST,
                "Binary chunk is truncated (offset $off, length $len, buffer ${b.size})",
                "The file is probably corrupt or not a real APK. Re-download it and try again."
            )
        }
    }
}
