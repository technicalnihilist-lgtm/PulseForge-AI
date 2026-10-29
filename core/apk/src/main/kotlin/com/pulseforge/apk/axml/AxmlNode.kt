package com.pulseforge.apk.axml

/** One attribute of a binary-XML element, with raw and typed values resolved. */
data class AxmlAttribute(
    val name: String,
    val namespace: String?,
    val resourceId: Int,
    val rawValue: String?,
    val typedValue: String,
    val dataType: Int
)

/** A parsed element of AndroidManifest.xml (or any compiled binary XML). */
data class AxmlNode(
    val name: String,
    val namespace: String? = null,
    val attributes: List<AxmlAttribute> = emptyList(),
    val children: List<AxmlNode> = emptyList(),
    val lineNumber: Int = 0
) {
    fun attr(name: String): AxmlAttribute? = attributes.firstOrNull { it.name == name }

    fun attrValue(name: String): String? = attr(name)?.let { it.rawValue ?: it.typedValue }

    fun boolAttr(name: String): Boolean? = attrValue(name)?.let {
        when (it.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    fun intAttr(name: String): Int? = attrValue(name)?.let { raw ->
        raw.toIntOrNull() ?: raw.removePrefix("0x").toIntOrNull(16)
    }

    fun longAttr(name: String): Long? = attrValue(name)?.toLongOrNull()

    fun child(name: String): AxmlNode? = children.firstOrNull { it.name == name }

    fun childrenNamed(name: String): List<AxmlNode> = children.filter { it.name == name }

    fun walk(visitor: (AxmlNode) -> Unit) {
        visitor(this)
        children.forEach { it.walk(visitor) }
    }
}
