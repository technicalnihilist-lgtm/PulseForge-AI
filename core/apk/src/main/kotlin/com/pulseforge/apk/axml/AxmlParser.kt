package com.pulseforge.apk.axml

import com.pulseforge.apk.ByteReader
import com.pulseforge.apk.ApkException
import com.pulseforge.model.ScanErrorCode

/**
 * Parser for Android's compiled binary XML (the format of AndroidManifest.xml inside an APK).
 *
 * The format is a sequence of chunks: a string pool, an optional resource-id map, and
 * start/end element records. Attribute names come from the string pool; when a packer strips a
 * name we fall back to the well-known android attribute id. Every read is bounds checked and the
 * tree depth is capped, so a hostile file cannot exhaust memory.
 */
object AxmlParser {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_NAMESPACE = 0x0100
    private const val RES_XML_END_NAMESPACE = 0x0101
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_XML_CDATA = 0x0104
    private const val RES_XML_RESOURCE_MAP = 0x0180

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val MAX_DEPTH = 128

    /**
     * Well-known framework attribute ids, used only when the string pool entry for an attribute
     * name has been blanked (some packers keep only the resource id).
     */
    private val KNOWN_ATTR_IDS: Map<Int, String> = mapOf(
        0x01010000 to "theme",
        0x01010001 to "label",
        0x01010002 to "icon",
        0x01010003 to "name",
        0x01010005 to "allowClearUserData",
        0x01010006 to "permission",
        0x01010007 to "readPermission",
        0x01010008 to "writePermission",
        0x01010009 to "protectionLevel",
        0x0101000e to "enabled",
        0x0101000f to "debuggable",
        0x01010010 to "exported",
        0x01010011 to "process",
        0x01010012 to "taskAffinity",
        0x0101001d to "launchMode",
        0x0101020c to "minSdkVersion",
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x01010270 to "targetSdkVersion",
        0x01010271 to "maxSdkVersion",
        0x01010280 to "allowBackup",
        0x010104ea to "extractNativeLibs",
        0x010104ec to "usesCleartextTraffic",
        0x01010527 to "networkSecurityConfig"
    )

    fun parse(bytes: ByteArray): AxmlNode {
        if (bytes.size < 8) throw corrupt("File is too small to be binary XML (${bytes.size} bytes)")
        val type = ByteReader.u16(bytes, 0)
        if (type != RES_XML_TYPE) throw corrupt("Not a binary XML document (chunk type 0x%04x)".format(type))
        val headerSize = ByteReader.u16(bytes, 2)
        val totalSize = ByteReader.u32(bytes, 4).toInt()
        val end = if (totalSize in 8..bytes.size) totalSize else bytes.size

        var strings = emptyList<String>()
        var resourceMap = IntArray(0)
        val stack = ArrayDeque<MutableNode>()
        var root: MutableNode? = null

        var pos = if (headerSize in 8..end) headerSize else 8
        while (pos + 8 <= end) {
            val chunkType = ByteReader.u16(bytes, pos)
            val chunkHeaderSize = ByteReader.u16(bytes, pos + 2)
            val chunkSize = ByteReader.u32(bytes, pos + 4).toInt()
            if (chunkSize <= 0 || pos + chunkSize > end) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> strings = parseStringPool(bytes, pos)
                RES_XML_RESOURCE_MAP -> resourceMap = parseResourceMap(bytes, pos, chunkHeaderSize, chunkSize)

                RES_XML_START_ELEMENT -> {
                    val node = parseStartElement(bytes, pos, chunkHeaderSize, strings, resourceMap)
                    if (stack.isEmpty()) {
                        if (root == null) root = node
                    } else if (stack.size < MAX_DEPTH) {
                        stack.last().children.add(node)
                    }
                    if (stack.size < MAX_DEPTH) stack.addLast(node)
                }

                RES_XML_END_ELEMENT -> if (stack.isNotEmpty()) stack.removeLast()
                RES_XML_CDATA, RES_XML_START_NAMESPACE, RES_XML_END_NAMESPACE -> Unit
            }
            pos += chunkSize
        }

        val r = root ?: throw corrupt("Binary XML contained no elements")
        return r.freeze()
    }

    private class MutableNode(
        val name: String,
        val namespace: String?,
        val attributes: List<AxmlAttribute>,
        val lineNumber: Int
    ) {
        val children = mutableListOf<MutableNode>()

        fun freeze(): AxmlNode = AxmlNode(
            name = name,
            namespace = namespace,
            attributes = attributes,
            children = children.map { it.freeze() },
            lineNumber = lineNumber
        )
    }

    internal fun parseStringPoolAt(b: ByteArray, start: Int): List<String> = parseStringPool(b, start)

    private fun parseStringPool(b: ByteArray, start: Int): List<String> {
        val headerSize = ByteReader.u16(b, start + 2)
        val chunkSize = ByteReader.u32(b, start + 4).toInt()
        val stringCount = ByteReader.u32(b, start + 8).toInt()
        val flags = ByteReader.u32(b, start + 16).toInt()
        val stringsStart = ByteReader.u32(b, start + 20).toInt()
        val utf8 = (flags and 0x100) != 0

        if (stringCount < 0 || stringCount > 1_000_000) {
            throw corrupt("String pool declares an implausible string count ($stringCount)")
        }
        val offsetsBase = start + headerSize
        val dataBase = start + stringsStart
        val poolEnd = start + chunkSize
        val out = ArrayList<String>(minOf(stringCount, 4096))

        for (i in 0 until stringCount) {
            val offPos = offsetsBase + i * 4
            if (offPos + 4 > b.size) break
            val strOff = ByteReader.u32(b, offPos).toInt()
            var p = dataBase + strOff
            if (p < 0 || p >= poolEnd) {
                out.add("")
                continue
            }
            try {
                if (utf8) {
                    // Two lengths: character count, then byte count (each 1 or 2 bytes).
                    var len = ByteReader.u8(b, p)
                    p += 1
                    if (len and 0x80 != 0) {
                        len = ((len and 0x7F) shl 8) or ByteReader.u8(b, p)
                        p += 1
                    }
                    var byteLen = ByteReader.u8(b, p)
                    p += 1
                    if (byteLen and 0x80 != 0) {
                        byteLen = ((byteLen and 0x7F) shl 8) or ByteReader.u8(b, p)
                        p += 1
                    }
                    val safeLen = byteLen.coerceAtMost(maxOf(0, minOf(poolEnd, b.size) - p))
                    out.add(String(b, p, safeLen, Charsets.UTF_8))
                } else {
                    var len = ByteReader.u16(b, p)
                    p += 2
                    if (len and 0x8000 != 0) {
                        len = ((len and 0x7FFF) shl 16) or ByteReader.u16(b, p)
                        p += 2
                    }
                    val byteLen = (len * 2).coerceAtMost(maxOf(0, minOf(poolEnd, b.size) - p))
                    out.add(String(b, p, byteLen, Charsets.UTF_16LE))
                }
            } catch (_: Exception) {
                out.add("")
            }
        }
        return out
    }

    private fun parseResourceMap(b: ByteArray, start: Int, headerSize: Int, chunkSize: Int): IntArray {
        val count = (chunkSize - headerSize) / 4
        if (count <= 0) return IntArray(0)
        val out = IntArray(count)
        for (i in 0 until count) out[i] = ByteReader.u32Int(b, start + headerSize + i * 4)
        return out
    }

    private fun parseStartElement(
        b: ByteArray,
        start: Int,
        headerSize: Int,
        strings: List<String>,
        resourceMap: IntArray
    ): MutableNode {
        val lineNumber = ByteReader.u32Int(b, start + 8)
        val body = start + headerSize
        val nsIdx = ByteReader.u32Int(b, body)
        val nameIdx = ByteReader.u32Int(b, body + 4)
        val attributeStart = ByteReader.u16(b, body + 8)
        val attributeSize = ByteReader.u16(b, body + 10)
        val attributeCount = ByteReader.u16(b, body + 12)

        val elementName = strings.getOrElse(nameIdx) { "" }
        val elementNs = if (nsIdx >= 0) strings.getOrElse(nsIdx) { "" }.ifEmpty { null } else null

        val attrs = ArrayList<AxmlAttribute>(attributeCount)
        var attrPos = body + attributeStart
        val stride = if (attributeSize >= 20) attributeSize else 20
        for (i in 0 until attributeCount) {
            if (attrPos + 20 > b.size) break
            val attrNsIdx = ByteReader.u32Int(b, attrPos)
            val attrNameIdx = ByteReader.u32Int(b, attrPos + 4)
            val rawIdx = ByteReader.u32Int(b, attrPos + 8)
            val dataType = ByteReader.u8(b, attrPos + 15)
            val data = ByteReader.u32Int(b, attrPos + 16)

            val resId = if (attrNameIdx in resourceMap.indices) resourceMap[attrNameIdx] else 0
            var attrName = strings.getOrElse(attrNameIdx) { "" }
            if (attrName.isEmpty() && resId != 0) attrName = KNOWN_ATTR_IDS[resId] ?: ""
            val attrNs = if (attrNsIdx >= 0) strings.getOrElse(attrNsIdx) { "" }.ifEmpty { null } else null
            val rawValue = if (rawIdx >= 0) strings.getOrElse(rawIdx) { "" } else null

            attrs.add(
                AxmlAttribute(
                    name = attrName,
                    namespace = attrNs,
                    resourceId = resId,
                    rawValue = rawValue,
                    typedValue = formatTypedValue(strings, dataType, data, rawValue),
                    dataType = dataType
                )
            )
            attrPos += stride
        }

        return MutableNode(elementName, elementNs, attrs, lineNumber)
    }

    private fun formatTypedValue(strings: List<String>, dataType: Int, data: Int, rawValue: String?): String {
        if (rawValue != null) return rawValue
        return when (dataType) {
            TYPE_STRING -> strings.getOrElse(data) { "" }
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
            TYPE_REFERENCE -> "@ref/0x" + Integer.toHexString(data)
            TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
            else -> data.toString()
        }
    }

    private fun corrupt(message: String) = ApkException(
        ScanErrorCode.CORRUPT_MANIFEST,
        "AndroidManifest.xml could not be parsed: $message",
        "The manifest is malformed or protected by a packer. PulseForge reports what it could read and marks the rest as unverified."
    )
}

/** Bridge so resources.arsc parsing can reuse the exact same string-pool implementation. */
object AxmlParserAccess {
    fun parseStringPoolAt(bytes: ByteArray, start: Int): List<String> = AxmlParser.parseStringPoolAt(bytes, start)
}
