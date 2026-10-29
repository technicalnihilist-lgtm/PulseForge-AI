package com.pulseforge.apk

import com.pulseforge.model.CertInfo
import com.pulseforge.model.SigningInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Reads signing information from an APK.
 *
 * Certificates come from the APK Signing Block (v2/v3/v3.1) and from META-INF (v1). Integrity
 * verification is delegated to the official `apksig` library; if the verifier cannot run we say so
 * instead of pretending the signature was checked.
 */
object SignatureInspector {

    private const val APK_SIG_BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_BLOCK_ID: Int = 0x7109871a
    // 0xF05368C0 does not fit in a positive Int, so it is narrowed explicitly.
    private val V3_BLOCK_ID: Int = 0xf05368c0.toInt()
    private const val V31_BLOCK_ID: Int = 0x1b93ad61

    fun inspect(file: File, archive: ZipArchive): SigningInfo {
        val warnings = mutableListOf<String>()
        val v1Files = archive.entries.map { it.path }.filter { path ->
            val lower = path.lowercase()
            lower.startsWith("meta-inf/") && (lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec"))
        }

        val blockCertificates = mutableListOf<X509Certificate>()
        val schemes = mutableListOf<String>()
        if (v1Files.isNotEmpty()) schemes.add("v1 (JAR)")

        try {
            val block: Map<Int, ByteArray> = readSigningBlock(file) ?: emptyMap()
            if (block.containsKey(V2_BLOCK_ID)) {
                schemes.add("v2")
                blockCertificates += certificatesFromBlock(block.getValue(V2_BLOCK_ID), warnings)
            }
            if (block.containsKey(V3_BLOCK_ID)) {
                schemes.add("v3")
                blockCertificates += certificatesFromBlock(block.getValue(V3_BLOCK_ID), warnings)
            }
            if (block.containsKey(V31_BLOCK_ID)) {
                schemes.add("v3.1")
                blockCertificates += certificatesFromBlock(block.getValue(V31_BLOCK_ID), warnings)
            }
        } catch (e: Exception) {
            warnings.add("APK Signing Block could not be read: ${e.message}")
        }

        if (blockCertificates.isEmpty() && v1Files.isNotEmpty()) {
            blockCertificates += certificatesFromV1(archive, v1Files, warnings)
        }

        val verified = runApksigVerification(file, warnings)

        val distinct = blockCertificates.distinctBy { it.encoded.contentHashCode() }
        val primary = distinct.firstOrNull()?.let { toCertInfo(it) }
        val additional = distinct.drop(1).map { toCertInfo(it) }

        if (primary == null) {
            warnings.add("No signing certificate could be extracted. The APK may be unsigned or use an unsupported scheme.")
        }
        if (schemes.isEmpty()) warnings.add("No signature scheme was detected in the archive.")

        return SigningInfo(
            schemes = schemes.distinct(),
            certificate = primary,
            additionalCertificates = additional,
            verified = verified == true,
            verificationMessage = when (verified) {
                true -> "Signature verified by apksig"
                false -> "apksig reported verification errors"
                null -> "Integrity not verified (verifier unavailable in this build)"
            },
            v1SignatureFiles = v1Files,
            warnings = warnings.distinct()
        )
    }

    /** @return true/false from apksig, or null when the verifier could not run. */
    private fun runApksigVerification(file: File, warnings: MutableList<String>): Boolean? = try {
        val result = com.android.apksig.ApkVerifier.Builder(file).build().verify()
        if (!result.isVerified) {
            val issues = result.errors.take(5).joinToString("; ") { it.toString() }
            warnings.add("Signature verification issue: $issues")
        }
        result.isVerified
    } catch (e: Throwable) {
        warnings.add("apksig verifier could not run: ${e::class.simpleName}: ${e.message}")
        null
    }

    private fun certificatesFromV1(
        archive: ZipArchive,
        v1Files: List<String>,
        warnings: MutableList<String>
    ): List<X509Certificate> = try {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        v1Files.flatMap { path ->
            val bytes = archive.readEntry(path, 8L * 1024 * 1024) ?: return@flatMap emptyList()
            factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        }
    } catch (e: Exception) {
        warnings.add("v1 certificates could not be decoded: ${e.message}")
        emptyList()
    }

    /** Reads the APK Signing Block: id/value pairs immediately before the central directory. */
    private fun readSigningBlock(file: File): Map<Int, ByteArray>? {
        val length = file.length()
        if (length < 32) return null
        RandomAccessFile(file, "r").use { raf ->
            val tailLength = minOf(length, 66_560L).toInt()
            val tail = ByteArray(tailLength)
            raf.seek(length - tailLength)
            raf.readFully(tail)

            var eocd = -1
            for (i in tailLength - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) { eocd = i; break }
            }
            if (eocd < 0) return null

            val cdOffset = le32(tail, eocd + 16).toLong()
            if (cdOffset < 32 || cdOffset > length) return null

            raf.seek(cdOffset - 24)
            val footer = ByteArray(24)
            raf.readFully(footer)
            val blockSize = le64(footer, 0)
            if (String(footer, 8, 16, Charsets.US_ASCII) != APK_SIG_BLOCK_MAGIC) return null

            val blockStart = cdOffset - blockSize - 8
            if (blockStart < 0 || blockSize > 512L * 1024 * 1024) return null

            raf.seek(blockStart)
            val block = ByteArray((cdOffset - blockStart).toInt())
            raf.readFully(block)

            val result = LinkedHashMap<Int, ByteArray>()
            var pos = 8
            val end = block.size - 24
            while (pos + 12 <= end) {
                val pairSize = le64(block, pos)
                if (pairSize < 4 || pairSize > end - pos) break
                val id = le32(block, pos + 8)
                val valueStart = pos + 12
                val valueEnd = pos + 8 + pairSize.toInt()
                if (valueEnd > block.size) break
                result[id] = block.copyOfRange(valueStart, valueEnd)
                pos = valueEnd
            }
            return result
        }
    }

    /**
     * Extracts X.509 certificates from a v2/v3 signer block. Both layouts start with
     * signed data = (digests, certificates, ...), so one parser handles both.
     */
    private fun certificatesFromBlock(value: ByteArray, warnings: MutableList<String>): List<X509Certificate> {
        return try {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            var pos = 0

            fun readLen(at: Int): Int {
                if (at + 4 > value.size) throw IllegalStateException("length prefix out of range")
                val len = le32(value, at)
                if (len < 0) throw IllegalStateException("negative length")
                return len
            }

            val signersLen = readLen(pos); pos += 4
            if (signersLen <= 0 || pos + signersLen > value.size) return emptyList()

            val signerLen = readLen(pos); pos += 4
            if (signerLen <= 0 || pos + signerLen > value.size) return emptyList()

            val signedDataLen = readLen(pos); pos += 4
            if (signedDataLen <= 0 || pos + signedDataLen > value.size) return emptyList()

            val digestsLen = readLen(pos); pos += 4
            pos += digestsLen
            val certsLen = readLen(pos); pos += 4
            val certsEnd = pos + certsLen
            if (certsLen <= 0 || certsEnd > value.size) return emptyList()

            val certs = mutableListOf<X509Certificate>()
            while (pos + 4 <= certsEnd) {
                val certLen = readLen(pos); pos += 4
                if (certLen <= 0 || pos + certLen > value.size) break
                val der = value.copyOfRange(pos, pos + certLen)
                pos += certLen
                val cert = factory.generateCertificate(ByteArrayInputStream(der))
                if (cert is X509Certificate) certs.add(cert)
            }
            certs
        } catch (e: Exception) {
            warnings.add("Signer block could not be decoded: ${e.message}")
            emptyList()
        }
    }

    private fun toCertInfo(cert: X509Certificate): CertInfo {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        val keySize = when (val key = cert.publicKey) {
            is RSAPublicKey -> key.modulus.bitLength()
            is ECPublicKey -> key.params.order.bitLength()
            else -> 0
        }
        val subject = cert.subjectX500Principal.name
        return CertInfo(
            subject = subject,
            issuer = cert.issuerX500Principal.name,
            serialNumber = cert.serialNumber.toString(16),
            sha256 = sha256,
            signatureAlgorithm = cert.sigAlgName,
            keyAlgorithm = cert.publicKey.algorithm,
            keySizeBits = keySize,
            notBeforeEpochMs = cert.notBefore.time,
            notAfterEpochMs = cert.notAfter.time,
            isSelfSigned = subject == cert.issuerX500Principal.name,
            isKnownDebugKey = subject.contains("CN=Android Debug", ignoreCase = true)
        )
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
