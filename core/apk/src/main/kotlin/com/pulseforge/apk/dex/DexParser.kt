package com.pulseforge.apk.dex

import com.pulseforge.model.ApiUsage
import com.pulseforge.model.DexFacts
import com.pulseforge.model.PackageRoot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Result of reading DEX files: reported facts plus the transient indexes the rule engines need.
 * Only [facts] is serialised.
 */
data class DexIndex(
    val facts: DexFacts,
    val strings: List<String> = emptyList(),
    /** "Lowner;->member" to occurrence count, for every method reference. */
    val methodRefs: Map<String, Int> = emptyMap(),
    val definedClasses: List<String> = emptyList()
) {
    fun occurrences(owner: String, member: String): Int = methodRefs["$owner->$member"] ?: 0
    fun anyUsage(vararg keys: String): Int = keys.sumOf { methodRefs[it] ?: 0 }
}

/**
 * Minimal, defensive DEX reader: header, string pool, type ids, method ids and class defs — the
 * tables needed to describe what the app references. Bytecode is deliberately not decoded; pattern
 * matching over the referenced-method table and string pool is enough for these rules and keeps
 * the analyser fast and safe.
 */
object DexParser {

    private const val HEADER_SIZE = 112
    private const val MAX_STRINGS = 400_000
    private const val MAX_METHOD_REFS = 120_000
    private const val MAX_DEFINED_CLASSES = 60_000

    fun parse(bytes: ByteArray, fileName: String): DexIndex {
        if (bytes.size < HEADER_SIZE) {
            return DexIndex(DexFacts(dexFiles = 1, parseWarnings = listOf("$fileName is shorter than a DEX header")))
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != "dex\n") {
            return DexIndex(DexFacts(dexFiles = 1, parseWarnings = listOf("$fileName has an unknown magic: ${magic.trim()}")))
        }

        val warnings = mutableListOf<String>()
        fun i32(off: Int): Int = if (off + 4 <= bytes.size) buf.getInt(off) else -1

        val stringIdsSize = i32(56)
        val stringIdsOff = i32(60)
        val typeIdsSize = i32(64)
        val typeIdsOff = i32(68)
        val methodIdsSize = i32(88)
        val methodIdsOff = i32(92)
        val classDefsSize = i32(96)
        val classDefsOff = i32(100)

        val strings = ArrayList<String>(minOf(stringIdsSize.coerceAtLeast(0), MAX_STRINGS))
        if (stringIdsSize in 0..MAX_STRINGS && stringIdsOff > 0) {
            for (i in 0 until stringIdsSize) {
                val offPos = stringIdsOff + i * 4
                if (offPos + 4 > bytes.size) { warnings.add("string id table truncated"); break }
                strings.add(readStringData(bytes, buf.getInt(offPos)))
            }
        } else if (stringIdsSize > MAX_STRINGS) {
            warnings.add("$fileName declares $stringIdsSize strings, above the analysis cap")
        }

        val types = ArrayList<String>(minOf(typeIdsSize.coerceAtLeast(0), 200_000))
        if (typeIdsSize in 0..200_000 && typeIdsOff > 0) {
            for (i in 0 until typeIdsSize) {
                val offPos = typeIdsOff + i * 4
                if (offPos + 4 > bytes.size) { warnings.add("type id table truncated"); break }
                types.add(strings.getOrElse(buf.getInt(offPos)) { "" })
            }
        }

        val methodRefs = HashMap<String, Int>(minOf(methodIdsSize.coerceAtLeast(0), MAX_METHOD_REFS))
        if (methodIdsSize in 0..MAX_METHOD_REFS && methodIdsOff > 0) {
            for (i in 0 until methodIdsSize) {
                val offPos = methodIdsOff + i * 8
                if (offPos + 8 > bytes.size) { warnings.add("method id table truncated"); break }
                val classIdx = buf.getShort(offPos).toInt() and 0xFFFF
                val nameIdx = buf.getInt(offPos + 4)
                val owner = types.getOrElse(classIdx) { "" }
                val name = strings.getOrElse(nameIdx) { "" }
                if (owner.isNotEmpty() && name.isNotEmpty()) {
                    val key = "$owner->$name"
                    methodRefs[key] = (methodRefs[key] ?: 0) + 1
                }
            }
        }

        val definedClasses = ArrayList<String>(minOf(classDefsSize.coerceAtLeast(0), MAX_DEFINED_CLASSES))
        if (classDefsSize in 0..MAX_DEFINED_CLASSES && classDefsOff > 0) {
            for (i in 0 until classDefsSize) {
                val offPos = classDefsOff + i * 32
                if (offPos + 4 > bytes.size) { warnings.add("class def table truncated"); break }
                val descriptor = types.getOrElse(buf.getInt(offPos)) { "" }
                if (descriptor.isNotEmpty()) definedClasses.add(descriptor)
            }
        }

        val facts = DexFacts(
            dexFiles = 1,
            totalReferencedMethods = methodIdsSize.coerceAtLeast(0),
            definedClasses = definedClasses.size,
            stringConstantCount = strings.size,
            packageRoots = packageRoots(definedClasses),
            parseWarnings = warnings
        )

        return DexIndex(facts = facts, strings = strings, methodRefs = methodRefs, definedClasses = definedClasses)
    }

    /** Merges several DEX files (multidex) into one index. */
    fun merge(indexes: List<DexIndex>): DexIndex {
        if (indexes.isEmpty()) return DexIndex(DexFacts())
        if (indexes.size == 1) return indexes.first()

        val strings = ArrayList<String>(indexes.sumOf { it.strings.size })
        val refs = HashMap<String, Int>(indexes.sumOf { it.methodRefs.size })
        val classes = ArrayList<String>(indexes.sumOf { it.definedClasses.size })
        val warnings = ArrayList<String>()

        for (idx in indexes) {
            strings.addAll(idx.strings)
            idx.methodRefs.forEach { (k, v) -> refs[k] = (refs[k] ?: 0) + v }
            classes.addAll(idx.definedClasses)
            warnings.addAll(idx.facts.parseWarnings)
        }

        val mergedFacts = DexFacts(
            dexFiles = indexes.size,
            totalReferencedMethods = indexes.sumOf { it.facts.totalReferencedMethods },
            definedClasses = classes.size,
            stringConstantCount = strings.size,
            packageRoots = packageRoots(classes),
            multidex = indexes.size > 1,
            parseWarnings = warnings.distinct()
        )

        return DexIndex(mergedFacts, strings, refs, classes)
    }

    /** A stable, curated subset of API usages kept in the serialised report. */
    fun summariseApiUsages(index: DexIndex, limit: Int = 120): List<ApiUsage> {
        val interesting = listOf(
            "Landroid/webkit/", "Ljavax/crypto/", "Ljava/security/", "Ljavax/net/ssl/", "Ljava/net/",
            "Ldalvik/", "Ljava/lang/Runtime;", "Ljava/lang/reflect/", "Ljava/util/Random;",
            "Lorg/apache/http/", "Lokhttp3/", "Landroid/telephony/", "Landroid/location/",
            "Landroid/accounts/", "Landroid/hardware/", "Landroid/util/Log;",
            "Landroid/content/pm/PackageManager;"
        )
        return index.methodRefs.entries
            .filter { entry -> interesting.any { prefix -> entry.key.startsWith(prefix) } }
            .sortedByDescending { it.value }
            .take(limit)
            .map { (key, count) ->
                ApiUsage(owner = key.substringBefore("->"), member = key.substringAfter("->"), occurrences = count)
            }
    }

    private fun packageRoots(classes: List<String>): List<PackageRoot> = classes
        .map { packageOf(it) }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(15)
        .map { PackageRoot(prefix = it.key, classCount = it.value) }

    private fun packageOf(descriptor: String): String {
        val body = descriptor.removePrefix("L")
        val slash = body.lastIndexOf('/')
        return if (slash > 0) body.substring(0, slash).replace('/', '.') else ""
    }

    /** Reads a string_data_item: ULEB128 UTF-16 length followed by MUTF-8 bytes. */
    private fun readStringData(bytes: ByteArray, offset: Int): String {
        if (offset <= 0 || offset >= bytes.size) return ""
        var pos = offset
        var shift = 0
        while (pos < bytes.size && shift < 35) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            shift += 7
            if (b and 0x80 == 0) break
        }
        val start = pos
        var end = start
        while (end < bytes.size && bytes[end].toInt() != 0) end++
        if (end <= start) return ""
        return decodeMutf8(bytes, start, end - start)
    }

    /** Decodes Modified UTF-8 (surrogate pairs stored as two 3-byte sequences). */
    private fun decodeMutf8(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        var i = offset
        val end = offset + length
        while (i < end) {
            val a = bytes[i].toInt() and 0xFF
            when {
                a < 0x80 -> { sb.append(a.toChar()); i += 1 }
                a and 0xE0 == 0xC0 && i + 1 < end -> {
                    val b = bytes[i + 1].toInt() and 0x3F
                    sb.append((((a and 0x1F) shl 6) or b).toChar())
                    i += 2
                }
                a and 0xF0 == 0xE0 && i + 2 < end -> {
                    val b = bytes[i + 1].toInt() and 0x3F
                    val c = bytes[i + 2].toInt() and 0x3F
                    sb.append((((a and 0x0F) shl 12) or (b shl 6) or c).toChar())
                    i += 3
                }
                else -> { sb.append('?'); i += 1 }
            }
        }
        return sb.toString()
    }
}
