package com.pulseforge.apk.image

/**
 * Reads pixel dimensions from image headers without decoding the image, so a 4000x4000 PNG costs a
 * few dozen bytes of work. Supports PNG, JPEG, WebP, GIF and BMP.
 */
object ImageHeaderParser {

    data class Dimensions(val width: Int, val height: Int, val format: String)

    fun read(bytes: ByteArray): Dimensions? {
        if (bytes.size < 16) return null
        return when {
            isPng(bytes) -> png(bytes)
            isJpeg(bytes) -> jpeg(bytes)
            isWebp(bytes) -> webp(bytes)
            isGif(bytes) -> gif(bytes)
            isBmp(bytes) -> bmp(bytes)
            else -> null
        }
    }

    private fun isPng(b: ByteArray) = b.size > 8 &&
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private fun png(b: ByteArray): Dimensions? {
        if (b.size < 24) return null
        val width = be32(b, 16)
        val height = be32(b, 20)
        return if (width > 0 && height > 0) Dimensions(width, height, "PNG") else null
    }

    private fun isJpeg(b: ByteArray) = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun jpeg(b: ByteArray): Dimensions? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i].toInt() and 0xFF != 0xFF) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) { i += 2; continue }
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val height = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val width = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) Dimensions(width, height, "JPEG") else null
            }
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }

    private fun isWebp(b: ByteArray) = b.size > 12 &&
        String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun webp(b: ByteArray): Dimensions? {
        if (b.size < 30) return null
        return when (String(b, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> {
                val w = 1 + le24(b, 24)
                val h = 1 + le24(b, 27)
                if (w > 0 && h > 0) Dimensions(w, h, "WebP") else null
            }
            "VP8 " -> {
                val w = le16(b, 26) and 0x3FFF
                val h = le16(b, 28) and 0x3FFF
                if (w > 0 && h > 0) Dimensions(w, h, "WebP") else null
            }
            "VP8L" -> {
                if (b.size < 25) return null
                val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or
                    ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                val w = (bits and 0x3FFF) + 1
                val h = ((bits shr 14) and 0x3FFF) + 1
                if (w > 0 && h > 0) Dimensions(w, h, "WebP") else null
            }
            else -> null
        }
    }

    private fun isGif(b: ByteArray) = b.size > 10 && String(b, 0, 3, Charsets.US_ASCII) == "GIF"

    private fun gif(b: ByteArray): Dimensions? {
        val w = le16(b, 6)
        val h = le16(b, 8)
        return if (w > 0 && h > 0) Dimensions(w, h, "GIF") else null
    }

    private fun isBmp(b: ByteArray) = b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()

    private fun bmp(b: ByteArray): Dimensions? {
        if (b.size < 26) return null
        val w = le32(b, 18)
        val h = le32(b, 22)
        return if (w > 0 && h > 0) Dimensions(w, h, "BMP") else null
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le24(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
