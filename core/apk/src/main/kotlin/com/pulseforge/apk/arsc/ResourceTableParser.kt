package com.pulseforge.apk.arsc

import com.pulseforge.apk.ByteReader
import com.pulseforge.apk.axml.AxmlParserAccess

/**
 * Reads the global string pool of resources.arsc.
 *
 * The full table is a deep structure of packages, type specs and entries; for the privacy and
 * secret checks the global value pool is what matters (string values, resource file paths).
 * Values are used for pattern matching only and are never uploaded.
 */
object ResourceTableParser {

    private const val RES_TABLE_TYPE = 0x0002
    private const val MAX_STRINGS = 200_000

    data class Result(val strings: List<String>, val parsed: Boolean, val warning: String? = null)

    fun parse(bytes: ByteArray): Result {
        if (bytes.size < 12) return Result(emptyList(), false, "resources.arsc is too small to parse")
        val type = ByteReader.u16(bytes, 0)
        if (type != RES_TABLE_TYPE) {
            return Result(emptyList(), false, "resources.arsc has an unexpected chunk type 0x%04x".format(type))
        }
        val headerSize = ByteReader.u16(bytes, 2)
        if (headerSize < 12 || headerSize >= bytes.size) {
            return Result(emptyList(), false, "resources.arsc header size $headerSize is out of range")
        }
        return try {
            val strings = AxmlParserAccess.parseStringPoolAt(bytes, headerSize)
            if (strings.size > MAX_STRINGS) {
                Result(strings.take(MAX_STRINGS), true, "String pool truncated to $MAX_STRINGS entries")
            } else {
                Result(strings, true, null)
            }
        } catch (e: Exception) {
            Result(emptyList(), false, "String pool could not be read: ${e.message}")
        }
    }
}
