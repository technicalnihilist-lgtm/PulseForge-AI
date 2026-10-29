package com.pulseforge.apk

import com.pulseforge.apk.arsc.ResourceTableParser
import com.pulseforge.apk.axml.AxmlParser
import com.pulseforge.apk.dex.DexIndex
import com.pulseforge.apk.dex.DexParser
import com.pulseforge.apk.image.ImageHeaderParser
import com.pulseforge.model.ApkFacts
import com.pulseforge.model.CategorySize
import com.pulseforge.model.DuplicateGroup
import com.pulseforge.model.ImageAsset
import com.pulseforge.model.ManifestInfo
import com.pulseforge.model.NativeLibInfo
import com.pulseforge.model.ParseWarning
import com.pulseforge.model.ResourceFacts
import com.pulseforge.model.ScanErrorCode
import com.pulseforge.model.ScanStage
import com.pulseforge.model.ZipEntrySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Tunables for a scan. Defaults let a mid-range phone analyse a 300 MB APK. */
data class AnalysisConfig(
    val maxFileSizeBytes: Long = 2L * 1024 * 1024 * 1024,
    val warnFileSizeBytes: Long = 300L * 1024 * 1024,
    val maxDexBytesPerFile: Long = 80L * 1024 * 1024,
    val maxDexFiles: Int = 20,
    val maxImageHeaderReads: Int = 3000,
    val maxAssetTextFiles: Int = 120,
    val maxAssetTextBytes: Long = 192L * 1024,
    val duplicateDetectionMinBytes: Long = 4096,
    val maxDuplicateHashChecks: Int = 400,
    val deepScan: Boolean = true
)

/**
 * Reads everything PulseForge can learn from an APK without executing it.
 *
 * Platform-agnostic (plain JVM APIs) so the same code runs in unit tests, on device and in CI.
 * It never extracts entries to disk and never loads a DEX into a class loader.
 */
class ApkAnalyzer(private val config: AnalysisConfig = AnalysisConfig()) {

    suspend fun analyze(
        file: File,
        displayName: String,
        onProgress: suspend (ScanStage, Float, String) -> Unit = { _, _, _ -> }
    ): ApkScanInput = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<ParseWarning>()

        onProgress(ScanStage.VALIDATING, 0.03f, "Checking file")
        if (file.length() > config.maxFileSizeBytes) {
            throw ApkException(
                ScanErrorCode.TOO_LARGE,
                "The APK is ${formatBytes(file.length())}, above the ${formatBytes(config.maxFileSizeBytes)} analysis limit",
                "Analyse a smaller build, or split the APK by ABI/DPI with an app bundle."
            )
        }
        if (file.length() > config.warnFileSizeBytes) {
            warnings.add(
                ParseWarning(
                    "LARGE_FILE",
                    "The APK is ${formatBytes(file.length())}; resource analysis is capped and some entries were skipped."
                )
            )
        }

        val sha256 = try {
            file.sha256()
        } catch (e: Exception) {
            throw ApkException(
                ScanErrorCode.FILE_NOT_FOUND,
                "The file could not be hashed: ${e.message}",
                "Re-import the APK; the document may have been removed by the provider."
            )
        }

        onProgress(ScanStage.READING_ZIP, 0.10f, "Reading archive")
        val archive = ZipArchive.open(file)
        try {
            val entries = archive.entries
            coroutineContext.ensureActive()

            onProgress(ScanStage.PARSING_MANIFEST, 0.25f, "Parsing AndroidManifest.xml")
            val manifestResult = readManifest(archive, warnings)

            onProgress(ScanStage.PARSING_DEX, 0.42f, "Reading DEX bytecode")
            val dexIndex = if (config.deepScan) readDex(archive, entries, warnings) else DexIndex(com.pulseforge.model.DexFacts())

            onProgress(ScanStage.ANALYSING_RESOURCES, 0.62f, "Analysing resources")
            val arsc = readResourceStrings(archive, warnings)
            val resources = analyseResources(archive, entries, dexIndex, arsc.strings.size, warnings)

            onProgress(ScanStage.VERIFYING_SIGNATURE, 0.85f, "Inspecting signature")
            val signing = try {
                SignatureInspector.inspect(file, archive)
            } catch (e: Exception) {
                warnings.add(ParseWarning("SIGNATURE_UNREADABLE", "Signature could not be inspected: ${e.message}"))
                null
            }

            val assetSamples = readAssetSamples(archive, entries)
            val xmlSummaries = readXmlResourceSummaries(archive, entries)

            onProgress(ScanStage.RUNNING_SECURITY_RULES, 0.95f, "Preparing findings")
            val facts = ApkFacts(
                fileName = displayName,
                fileSizeBytes = file.length(),
                sha256 = sha256,
                inspectedAtEpochMs = startedAt,
                manifest = manifestResult.first,
                manifestParseError = manifestResult.second,
                signing = signing,
                dex = dexIndex.facts.copy(apiUsages = DexParser.summariseApiUsages(dexIndex)),
                resources = resources,
                entryCount = entries.size,
                warnings = warnings.toList()
            )

            ApkScanInput(
                facts = facts,
                dex = dexIndex,
                resourceStrings = arsc.strings,
                assetTextSamples = assetSamples,
                entries = entries,
                xmlResourceSummaries = xmlSummaries
            )
        } catch (oom: OutOfMemoryError) {
            throw ApkException(
                ScanErrorCode.OUT_OF_MEMORY,
                "The device ran out of memory while analysing this APK",
                "Close other apps and retry, or scan on a device with more RAM. Nothing was executed."
            )
        } finally {
            archive.close()
        }
    }

    private fun readManifest(archive: ZipArchive, warnings: MutableList<ParseWarning>): Pair<ManifestInfo?, String?> {
        val bytes = try {
            archive.readEntry("AndroidManifest.xml", 32L * 1024 * 1024)
        } catch (e: ApkException) {
            warnings.add(ParseWarning("MANIFEST_TOO_LARGE", e.message ?: "Manifest too large"))
            null
        }
        if (bytes == null) {
            warnings.add(
                ParseWarning("MANIFEST_MISSING", "AndroidManifest.xml is missing; the archive is not a valid APK.", fatal = true)
            )
            return null to "AndroidManifest.xml is missing from the archive"
        }
        return try {
            ManifestReader.read(AxmlParser.parse(bytes)) to null
        } catch (e: Exception) {
            warnings.add(ParseWarning("MANIFEST_PARSE_FAILED", e.message ?: "Manifest parse failed"))
            null to (e.message ?: "Manifest could not be parsed")
        }
    }

    private fun readDex(
        archive: ZipArchive,
        entries: List<ArchiveEntry>,
        warnings: MutableList<ParseWarning>
    ): DexIndex {
        val dexEntries = entries
            .filter { it.path.startsWith("classes") && it.path.endsWith(".dex") }
            .sortedBy { it.path }
            .take(config.maxDexFiles)
        if (dexEntries.isEmpty()) {
            warnings.add(ParseWarning("DEX_MISSING", "No classes*.dex found; the app may be split or obfuscated by a packer."))
            return DexIndex(com.pulseforge.model.DexFacts())
        }
        val indexes = mutableListOf<DexIndex>()
        for (entry in dexEntries) {
            if (entry.uncompressedBytes > config.maxDexBytesPerFile) {
                warnings.add(
                    ParseWarning(
                        "DEX_SKIPPED",
                        "${entry.path} is ${formatBytes(entry.uncompressedBytes)} and was skipped (limit ${formatBytes(config.maxDexBytesPerFile)})."
                    )
                )
                continue
            }
            try {
                val bytes = archive.readEntry(entry.path, config.maxDexBytesPerFile) ?: continue
                indexes.add(DexParser.parse(bytes, entry.path))
            } catch (e: ApkException) {
                warnings.add(ParseWarning("DEX_READ_FAILED", "${entry.path}: ${e.message}"))
            } catch (e: Exception) {
                warnings.add(ParseWarning("DEX_PARSE_FAILED", "${entry.path}: ${e.message}"))
            }
        }
        return DexParser.merge(indexes)
    }

    private fun readResourceStrings(archive: ZipArchive, warnings: MutableList<ParseWarning>): ResourceTableParser.Result {
        if (!archive.hasEntry("resources.arsc")) return ResourceTableParser.Result(emptyList(), false, null)
        return try {
            val bytes = archive.readEntry("resources.arsc", 256L * 1024 * 1024)
            if (bytes == null) ResourceTableParser.Result(emptyList(), false, "resources.arsc could not be read")
            else ResourceTableParser.parse(bytes).also { if (it.warning != null) warnings.add(ParseWarning("ARSC", it.warning)) }
        } catch (e: ApkException) {
            warnings.add(ParseWarning("ARSC_TOO_LARGE", e.message ?: "resources.arsc too large"))
            ResourceTableParser.Result(emptyList(), false, "resources.arsc too large")
        }
    }

    private fun analyseResources(
        archive: ZipArchive,
        entries: List<ArchiveEntry>,
        dex: DexIndex,
        arscStringCount: Int,
        warnings: MutableList<ParseWarning>
    ): ResourceFacts {
        val byCategory = entries
            .groupBy { it.category }
            .map { (category, list) -> CategorySize(category, list.sumOf { it.uncompressedBytes }, list.size) }
            .sortedByDescending { it.bytes }

        val images = entries.filter { EntryCategory.isImage(it.path) }
        val imageBytes = images.sumOf { it.uncompressedBytes }

        val largest = entries.sortedByDescending { it.uncompressedBytes }.take(25)
            .map { ZipEntrySummary(it.path, it.uncompressedBytes, it.compressedBytes, it.category) }

        val oversized = mutableListOf<ImageAsset>()
        // Dimensions are read for every image (header-only, a few dozen bytes each) so a heavily
        // compressed but huge bitmap is still caught. The list is bounded by config.
        for (entry in images.sortedByDescending { it.uncompressedBytes }.take(config.maxImageHeaderReads)) {
            val head = try { archive.readHead(entry.path, 4096) } catch (e: Exception) { null }
            val dims = head?.let { ImageHeaderParser.read(it) }
            if (dims != null) {
                val megapixels = (dims.width.toLong() * dims.height.toLong()) / 1_000_000.0
                if (entry.uncompressedBytes > 200L * 1024 || megapixels > 1.5) {
                    oversized.add(ImageAsset(entry.path, entry.uncompressedBytes, dims.width, dims.height, dims.format))
                }
            } else if (entry.uncompressedBytes > 400L * 1024) {
                oversized.add(ImageAsset(entry.path, entry.uncompressedBytes, 0, 0, "unknown"))
            }
            if (oversized.size >= 40) break
        }

        val duplicates = findDuplicates(archive, entries)

        val nativeLibs = entries.filter { it.path.startsWith("lib/") && it.path.endsWith(".so") }
            .map { NativeLibInfo(it.path, abiOf(it.path), it.uncompressedBytes) }
        val abis = nativeLibs.map { it.abi }.distinct().sorted()

        val unusedAssets = entries
            .filter { it.path.startsWith("assets/") && it.uncompressedBytes > 8 * 1024 }
            .filter { entry ->
                val name = entry.path.substringAfterLast('/')
                dex.strings.none { s -> s.contains(name) }
            }
            .sortedByDescending { it.uncompressedBytes }
            .take(30)
            .map { it.path }

        if (abis.size > 2) {
            warnings.add(
                ParseWarning(
                    "MANY_ABIS",
                    "The APK ships ${abis.size} ABIs (${abis.joinToString(", ")}); an app bundle would deliver only the one a device needs."
                )
            )
        }

        return ResourceFacts(
            entryCount = entries.size,
            totalUncompressedBytes = entries.sumOf { it.uncompressedBytes },
            byCategory = byCategory,
            imageCount = images.size,
            imageBytes = imageBytes,
            largestEntries = largest,
            duplicates = duplicates,
            oversizedImages = oversized.sortedByDescending { it.bytes },
            unusedAssetCandidates = unusedAssets,
            nativeLibs = nativeLibs.sortedByDescending { it.bytes }.take(40),
            abis = abis,
            hasResourcesArsc = archive.hasEntry("resources.arsc"),
            resourceStringPoolEntries = arscStringCount
        )
    }

    /** Confirms duplicates by CRC+size first, then by SHA-256 so the report can be trusted. */
    private fun findDuplicates(archive: ZipArchive, entries: List<ArchiveEntry>): List<DuplicateGroup> {
        val candidates = entries
            .filter { it.uncompressedBytes >= config.duplicateDetectionMinBytes }
            .groupBy { it.crc32 to it.uncompressedBytes }
            .filter { it.value.size > 1 }

        var hashesUsed = 0
        val groups = mutableListOf<DuplicateGroup>()
        for ((_, group) in candidates) {
            if (hashesUsed >= config.maxDuplicateHashChecks) break
            val byHash = mutableMapOf<String, MutableList<ArchiveEntry>>()
            for (entry in group) {
                if (hashesUsed >= config.maxDuplicateHashChecks) break
                val hash = try { archive.sha256OfEntry(entry.path, 16L * 1024 * 1024) } catch (e: Exception) { null } ?: continue
                hashesUsed++
                byHash.getOrPut(hash) { mutableListOf() }.add(entry)
            }
            byHash.forEach { (hash, list) ->
                if (list.size > 1) {
                    val each = list.first().uncompressedBytes
                    groups.add(DuplicateGroup(hash, list.map { it.path }, each, each * (list.size - 1)))
                }
            }
        }
        return groups.sortedByDescending { it.wastedBytes }.take(20)
    }

    private fun readAssetSamples(archive: ZipArchive, entries: List<ArchiveEntry>): Map<String, String> {
        val textExtensions = setOf(
            "json", "xml", "txt", "properties", "cfg", "conf", "yaml", "yml",
            "js", "html", "htm", "csv", "pem", "env", "ini", "kt", "java"
        )
        val candidates = entries
            .filter { it.path.startsWith("assets/") || it.path.startsWith("res/raw/") }
            .filter { it.path.substringAfterLast('.', "").lowercase() in textExtensions }
            .filter { it.uncompressedBytes in 1..config.maxAssetTextBytes }
            .take(config.maxAssetTextFiles)

        val out = LinkedHashMap<String, String>()
        for (entry in candidates) {
            try {
                val bytes = archive.readEntry(entry.path, config.maxAssetTextBytes) ?: continue
                if (bytes.any { it == 0.toByte() }) continue
                out[entry.path] = String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                // A single unreadable asset must not fail the scan.
            }
        }
        return out
    }

    /**
     * Flattens compiled res/xml and res/layout documents into readable text so rules can inspect
     * the network security config, backup rules and layout depth without a second parse.
     */
    private fun readXmlResourceSummaries(archive: ZipArchive, entries: List<ArchiveEntry>): Map<String, String> {
        val xmlEntries = entries
            .filter { (it.path.startsWith("res/xml/") || it.path.startsWith("res/layout/")) && it.path.endsWith(".xml") }
            .take(80)
        val out = LinkedHashMap<String, String>()
        for (entry in xmlEntries) {
            try {
                val bytes = archive.readEntry(entry.path, 4L * 1024 * 1024) ?: continue
                out[entry.path] = flatten(AxmlParser.parse(bytes), 0)
            } catch (e: Exception) {
                // A malformed or obfuscated XML resource must not break the scan.
            }
        }
        return out
    }

    private fun flatten(node: com.pulseforge.apk.axml.AxmlNode, depth: Int): String {
        if (depth > 8) return ""
        val sb = StringBuilder()
        sb.append(" ".repeat(depth * 2)).append('<').append(node.name)
        node.attributes.forEach { a ->
            sb.append(' ').append(a.name).append("=\"").append(a.rawValue ?: a.typedValue).append('"')
        }
        sb.append(">\n")
        node.children.forEach { sb.append(flatten(it, depth + 1)) }
        if (sb.length > 16_000) return sb.substring(0, 16_000)
        return sb.toString()
    }

    private fun abiOf(path: String): String {
        val parts = path.split('/')
        return if (parts.size >= 3) parts[1] else "unknown"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
