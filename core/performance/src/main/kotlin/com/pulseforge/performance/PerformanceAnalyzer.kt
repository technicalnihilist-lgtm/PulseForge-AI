package com.pulseforge.performance

import com.pulseforge.apk.ApkScanInput
import com.pulseforge.model.CategorySize
import com.pulseforge.model.Confidence
import com.pulseforge.model.Effort
import com.pulseforge.model.EstimatedMetric
import com.pulseforge.model.Impact
import com.pulseforge.model.MeasuredMetric
import com.pulseforge.model.OptimizationAction
import com.pulseforge.model.PerformanceAnalysis
import com.pulseforge.model.UnavailableMetric

/**
 * Turns measurements from the APK into a performance report.
 *
 * Hard rule: anything that cannot be observed statically is listed in
 * [PerformanceAnalysis.unavailable] rather than guessed. Estimates always carry their basis.
 */
object PerformanceAnalyzer {

    /** Bytes per pixel for a decoded ARGB_8888 bitmap. */
    private const val BYTES_PER_PIXEL = 4L

    fun analyse(input: ApkScanInput): PerformanceAnalysis {
        val facts = input.facts
        val resources = facts.resources
        val dex = facts.dex

        val measured = buildList {
            add(MeasuredMetric("APK size", formatBytes(facts.fileSizeBytes), "Size of the file on disk"))
            resources?.let {
                add(MeasuredMetric("Uncompressed payload", formatBytes(it.totalUncompressedBytes), "${it.entryCount} archive entries"))
                add(MeasuredMetric("Images", "${it.imageCount} files, ${formatBytes(it.imageBytes)}", "Share of payload: ${percent(it.imageBytes, it.totalUncompressedBytes)}"))
                if (it.nativeLibs.isNotEmpty()) {
                    add(
                        MeasuredMetric(
                            "Native libraries",
                            "${it.nativeLibs.size} files, ${formatBytes(it.nativeLibs.sumOf { lib -> lib.bytes })}",
                            "ABIs: ${it.abis.joinToString(", ")}"
                        )
                    )
                }
            }
            add(MeasuredMetric("DEX files", dex.dexFiles.toString(), if (dex.multidex) "Multidex build" else "Single dex"))
            add(MeasuredMetric("Referenced methods", formatCount(dex.totalReferencedMethods), "method_ids across all dex files"))
            add(MeasuredMetric("Defined classes", formatCount(dex.definedClasses), "class_defs across all dex files"))
            add(MeasuredMetric("String constants", formatCount(dex.stringConstantCount), "String pool entries"))
            layoutDepth(input)?.let { (file, depth) -> add(MeasuredMetric("Deepest layout", "$depth levels", file)) }
        }

        val estimates = buildList {
            resources?.let { res ->
                val largest = res.oversizedImages.firstOrNull()
                if (largest != null && largest.width > 0 && largest.height > 0) {
                    val decoded = largest.width.toLong() * largest.height * BYTES_PER_PIXEL
                    add(
                        EstimatedMetric(
                            label = "Largest bitmap in memory",
                            estimate = formatBytes(decoded),
                            basis = "${largest.path} decodes to ${largest.width}x${largest.height} ARGB_8888 " +
                                "(width x height x 4 bytes). Only accurate if the bitmap is decoded at full size.",
                            confidence = Confidence.MEDIUM
                        )
                    )
                }
                val totalDecoded = res.oversizedImages
                    .filter { it.width > 0 && it.height > 0 }
                    .sumOf { it.width.toLong() * it.height * BYTES_PER_PIXEL }
                if (totalDecoded > 0) {
                    add(
                        EstimatedMetric(
                            label = "Worst-case bitmap memory",
                            estimate = formatBytes(totalDecoded),
                            basis = "Sum of decoded sizes for the ${res.oversizedImages.size} oversized images. " +
                                "A real app rarely holds all of them at once, so treat this as an upper bound.",
                            confidence = Confidence.LOW
                        )
                    )
                }
            }
            if (dex.multidex) {
                add(
                    EstimatedMetric(
                        label = "First-run dex cost",
                        estimate = "Higher than single-dex",
                        basis = "${dex.dexFiles} dex files must be opened and verified on first launch. " +
                            "The exact cost depends on the device and cannot be derived from the APK.",
                        confidence = Confidence.LOW
                    )
                )
            }
        }

        val unavailable = listOf(
            UnavailableMetric(
                "Cold and warm startup time",
                "Startup depends on the CPU, storage speed, ART profile state and what the app does in Application.onCreate. None of that is in the APK.",
                "Measure with Jetpack Macrobenchmark (StartupTimingMetric) or adb shell am start -W on a test device."
            ),
            UnavailableMetric(
                "Frame rate / jank",
                "Rendering happens at runtime and depends on the device GPU and the code paths taken.",
                "Use Macrobenchmark with FrameTimingMetric, or Perfetto gfx traces."
            ),
            UnavailableMetric(
                "CPU and memory in use",
                "Static analysis cannot execute the app, so no runtime counters exist.",
                "Android Studio Profiler or dumpsys meminfo for the package, on an authorized test build."
            ),
            UnavailableMetric(
                "Battery and network consumption",
                "Both depend on usage patterns and radio state.",
                "Battery Historian with a captured bug report, or Energy Profiler on a device."
            ),
            UnavailableMetric(
                "ANR and crash rate",
                "These are production telemetry, not properties of an APK.",
                "Play Console Android vitals for your own app; not obtainable for third-party APKs."
            ),
            UnavailableMetric(
                "Real layout inflation cost",
                "Layout depth is measurable, but inflation time depends on the runtime and device.",
                "Macrobenchmark or a Perfetto trace of the screen in question."
            )
        )

        return PerformanceAnalysis(
            measured = measured,
            estimates = estimates,
            unavailable = unavailable,
            composition = resources?.byCategory ?: emptyList(),
            duplicates = resources?.duplicates.orEmpty(),
            oversizedImages = resources?.oversizedImages.orEmpty(),
            actions = buildActions(input),
            runtimeProfilingNote = "Runtime profiling is optional and requires your own test device and an " +
                "authorized build. PulseForge never installs or runs the analysed APK. Use Jetpack Macrobenchmark " +
                "in your project, or Android Studio's Profiler against a debug build you control."
        )
    }

    private fun buildActions(input: ApkScanInput): List<OptimizationAction> {
        val resources = input.facts.resources ?: return emptyList()
        val actions = mutableListOf<OptimizationAction>()

        val duplicateWaste = resources.duplicates.sumOf { it.wastedBytes }
        if (duplicateWaste > 0) {
            actions.add(
                OptimizationAction(
                    id = "dedupe-assets",
                    title = "Remove ${resources.duplicates.size} duplicate resource group(s)",
                    impact = if (duplicateWaste > 512 * 1024) Impact.HIGH else Impact.MEDIUM,
                    effort = Effort.LOW,
                    rationale = "Identical files are stored once per path. Their SHA-256 digests match, so removing the " +
                        "extra copies saves ${formatBytes(duplicateWaste)} with no behaviour change.",
                    steps = resources.duplicates.take(5).map {
                        "Keep one of: ${it.paths.joinToString(", ")} (${formatBytes(it.bytesEach)} each)"
                    } + "Replace the removed paths with references to the single kept resource.",
                    estimatedSavingBytes = duplicateWaste,
                    applicableWhen = "Always applicable; this is an exact, verified saving.",
                    caveats = null,
                    requiresSourceAccess = true
                )
            )
        }

        val oversizedBytes = resources.oversizedImages.sumOf { it.bytes }
        if (oversizedBytes > 0) {
            // Documented assumption: WebP at the same resolution typically lands 45-70% smaller than
            // PNG for photographic content; we use 55% as a mid-point and label it an estimate.
            val estimated = (oversizedBytes * 0.55).toLong()
            actions.add(
                OptimizationAction(
                    id = "compress-images",
                    title = "Compress ${resources.oversizedImages.size} oversized image(s)",
                    impact = Impact.HIGH,
                    effort = Effort.LOW,
                    rationale = "These images account for ${formatBytes(oversizedBytes)}. Converting to WebP at the same " +
                        "resolution usually saves 45-70% for photographic content; this estimate uses 55%.",
                    steps = resources.oversizedImages.take(5).map {
                        val dims = if (it.width > 0) " (${it.width}x${it.height})" else ""
                        "${it.path}$dims - ${formatBytes(it.bytes)}: convert to WebP"
                    } + "Use Android Studio's image asset tooling or cwebp -q 80 and verify visual quality.",
                    estimatedSavingBytes = estimated,
                    applicableWhen = "Images larger than 200 KB or above about 1.5 megapixels.",
                    caveats = "Estimate only: the real saving depends on image content. Lossy conversion needs a visual review."
                )
            )
            val huge = resources.oversizedImages.filter { it.width >= 2000 || it.height >= 2000 }
            if (huge.isNotEmpty()) {
                actions.add(
                    OptimizationAction(
                        id = "downscale-images",
                        title = "Downscale ${huge.size} image(s) to display size",
                        impact = Impact.MEDIUM,
                        effort = Effort.LOW,
                        rationale = "An image wider than 2000 px is rarely displayed larger than 1080 px on a phone. " +
                            "Memory scales with width x height, so downscaling cuts both APK size and heap use.",
                        steps = huge.take(5).map { "${it.path} (${it.width}x${it.height}) - resize to the largest displayed size" },
                        estimatedSavingBytes = null,
                        applicableWhen = "Images larger than the largest density bucket that displays them.",
                        caveats = "Keep a high-resolution variant only if a zoom or export feature needs it."
                    )
                )
            }
        }

        val abis = resources.abis
        if (abis.size > 1) {
            val libBytes = resources.nativeLibs.sumOf { it.bytes }
            val perAbi = libBytes / abis.size
            actions.add(
                OptimizationAction(
                    id = "split-abi",
                    title = "Ship one ABI per device (App Bundle)",
                    impact = Impact.HIGH,
                    effort = Effort.MEDIUM,
                    rationale = "The APK carries ${abis.size} ABI variants (${formatBytes(libBytes)} of native code). " +
                        "Each device downloads all of them and uses one.",
                    steps = listOf(
                        "Publish an Android App Bundle (.aab) instead of a universal APK.",
                        "For sideloaded builds, enable ABI splits in the app module.",
                        "Verify the per-ABI APKs install on both a 32-bit and a 64-bit device."
                    ),
                    estimatedSavingBytes = perAbi * (abis.size - 1),
                    applicableWhen = "Apps with native libraries for more than one ABI.",
                    caveats = "Estimate assumes native libraries are evenly distributed across ABIs."
                )
            )
        }

        if (resources.unusedAssetCandidates.isNotEmpty()) {
            actions.add(
                OptimizationAction(
                    id = "remove-unused-assets",
                    title = "Review ${resources.unusedAssetCandidates.size} possibly unused asset(s)",
                    impact = Impact.MEDIUM,
                    effort = Effort.MEDIUM,
                    rationale = "These asset filenames never appear in the DEX string pool, which is where a path used at " +
                        "runtime would normally be referenced.",
                    steps = resources.unusedAssetCandidates.take(8) +
                        "Confirm each file is not loaded by a dynamically built path, then delete it.",
                    estimatedSavingBytes = null,
                    applicableWhen = "Assets that are not referenced by any string in the bytecode.",
                    caveats = "Heuristic: a path assembled at runtime will not be seen, so verify before deleting."
                )
            )
        }

        if (input.facts.dex.definedClasses > 60_000 || input.facts.dex.multidex) {
            actions.add(
                OptimizationAction(
                    id = "r8-full-mode",
                    title = "Enable R8 full mode and resource shrinking",
                    impact = Impact.MEDIUM,
                    effort = Effort.MEDIUM,
                    rationale = "The app ships ${formatCount(input.facts.dex.definedClasses)} classes across " +
                        "${input.facts.dex.dexFiles} dex files. R8 full mode with shrinking typically removes 20-50% of " +
                        "unused code in apps that use reflection lightly.",
                    steps = listOf(
                        "android.enableR8.fullMode=true in gradle.properties",
                        "release build type: isMinifyEnabled = true, isShrinkResources = true",
                        "Add keep rules only for reflection/JSON targets that break, and re-run instrumentation tests."
                    ),
                    estimatedSavingBytes = null,
                    applicableWhen = "Release builds from source you control.",
                    caveats = "Requires thorough testing: over-aggressive shrinking removes code reached only by reflection."
                )
            )
        }

        actions.add(
            OptimizationAction(
                id = "baseline-profile",
                title = "Generate a Baseline Profile",
                impact = Impact.MEDIUM,
                effort = Effort.MEDIUM,
                rationale = "A Baseline Profile lets ART pre-compile the startup and scroll-critical paths at install " +
                    "time. Google reports 20-30% faster startup for apps that ship one. PulseForge cannot measure your " +
                    "startup time, so the gain must be verified on your device.",
                steps = listOf(
                    "Add the androidx.baselineprofile Gradle plugin and a baselineprofile module.",
                    "Write a Macrobenchmark that starts the app and scrolls the main list.",
                    "Generate, commit app/src/main/baseline-prof.txt, and re-measure before and after."
                ),
                estimatedSavingBytes = null,
                applicableWhen = "Apps built from source; most valuable for large apps.",
                caveats = "Requires a physical device or emulator running API 28+ to generate profiles."
            )
        )

        if (resources.oversizedImages.any { it.format == "PNG" }) {
            actions.add(
                OptimizationAction(
                    id = "lazy-loading",
                    title = "Load large images lazily and with sampling",
                    impact = Impact.MEDIUM,
                    effort = Effort.MEDIUM,
                    rationale = "Decoding a large PNG at full size allocates width x height x 4 bytes of heap. " +
                        "Coil or Glide apply inSampleSize and recycle off-screen bitmaps automatically.",
                    steps = listOf(
                        "Use Coil (Compose) or Glide with a size-limited request.",
                        "For manual decoding, set BitmapFactory.Options.inSampleSize from the target view size.",
                        "Avoid decoding in onBindViewHolder; cancel requests when the view is recycled."
                    ),
                    estimatedSavingBytes = null,
                    applicableWhen = "Screens that display large images in lists or on scroll.",
                    caveats = "This changes rendering behaviour; verify visually."
                )
            )
        }

        return actions
    }

    /** Depth of the deepest compiled layout, derived from the indentation of the flattened XML. */
    private fun layoutDepth(input: ApkScanInput): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for ((path, text) in input.xmlResourceSummaries) {
            if (!path.startsWith("res/layout/")) continue
            val depth = text.lineSequence()
                .filter { it.trimStart().startsWith("<") }
                .maxOfOrNull { line -> (line.length - line.trimStart().length) / 2 } ?: 0
            if (best == null || depth > best.second) best = path to depth
        }
        return best
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun formatCount(value: Int): String = if (value >= 1000) "%,d".format(value) else value.toString()

    private fun percent(part: Long, total: Long): String = if (total <= 0) "n/a" else "%.0f%%".format(part * 100.0 / total)

    fun totalMeasuredBytes(composition: List<CategorySize>): Long = composition.sumOf { it.bytes }
}
