package com.pulseforge.performance

import com.pulseforge.model.ScanReport

/** One paste-ready piece of build configuration, with the evidence that justifies it. */
data class RecipeSection(
    val id: String,
    val title: String,
    val rationale: String,
    val snippet: String,
    val language: String = "kotlin",
    /** Where the snippet belongs, e.g. "app/build.gradle.kts". */
    val target: String,
    val estimatedSavingBytes: Long? = null,
    val requiresOwnership: Boolean = true
)

data class OptimizationRecipe(
    val sections: List<RecipeSection>,
    val notes: List<String>
) {
    val estimatedSavingBytes: Long get() = sections.sumOf { it.estimatedSavingBytes ?: 0L }
    val isEmpty: Boolean get() = sections.isEmpty()
}

/**
 * Turns a finished report into concrete build changes.
 *
 * Every section is conditioned on something the analyser actually measured - duplicate assets,
 * oversized images, several ABIs, multidex - so the recipe is not generic advice. The snippets are
 * real AGP 8.5 configuration for the app's own module.
 *
 * This never claims to rebuild anything: it produces configuration for the user's build, because
 * only their build can shrink and re-sign their app.
 */
object BuildRecipeGenerator {

    fun generate(report: ScanReport, userOwnsSource: Boolean = true): OptimizationRecipe {
        val perf = report.performance
        val dex = report.apk.dex
        val resources = report.apk.resources
        val sections = mutableListOf<RecipeSection>()

        sections += r8Section(report)
        sections += resourceShrinkerSection()

        val duplicates = perf.duplicates
        if (duplicates.isNotEmpty()) {
            val wasted = duplicates.sumOf { it.wastedBytes }
            sections += RecipeSection(
                id = "dedupe",
                title = "Remove ${duplicates.size} duplicated asset group(s)",
                rationale = "${duplicates.size} files are byte-identical, wasting ${formatBytes(wasted)}. " +
                    "Keep one copy and reference it from both places.",
                snippet = buildString {
                    appendLine("// Duplicated, byte-identical files found in this APK:")
                    duplicates.take(5).forEach { group ->
                        appendLine("//  ${group.paths.joinToString("  ==  ")}")
                    }
                    appendLine("// Keep one, update the references, delete the rest.")
                },
                language = "text",
                target = "res/ and assets/",
                estimatedSavingBytes = wasted
            )
        }

        val images = perf.oversizedImages
        if (images.isNotEmpty()) {
            val bytes = images.sumOf { it.bytes }
            sections += RecipeSection(
                id = "images",
                title = "Downscale and re-encode ${images.size} oversized image(s)",
                rationale = "Together these images occupy ${formatBytes(bytes)}. Converting to WebP at the " +
                    "display size typically saves 45-70%; the saving below is an estimate, not a measurement.",
                snippet = buildString {
                    appendLine("// 1. Convert to WebP at the size the UI actually draws:")
                    appendLine("//    cwebp -q 82 input.png -o res/drawable-xxhdpi/hero.webp")
                    appendLine("// 2. Keep density buckets honest: one asset per bucket, not a copy in each.")
                    appendLine("// 3. For assets loaded with BitmapFactory, decode with a sample size:")
                    appendLine("//    BitmapFactory.Options().apply { inSampleSize = 2 }")
                    appendLine()
                    images.take(5).forEach { image ->
                        appendLine("//  ${image.path}  ${image.width}x${image.height}  ${formatBytes(image.bytes)}")
                    }
                },
                language = "text",
                target = "res/drawable-*",
                estimatedSavingBytes = (bytes * 0.55).toLong()
            )
        }

        if (dex.multidex) {
            sections += RecipeSection(
                id = "multidex",
                title = "Reduce method count below the multidex threshold",
                rationale = "The APK has ${dex.dexFiles} DEX files and ${dex.totalReferencedMethods} referenced " +
                    "methods. Multidex costs first-launch time; shrinking usually removes the need.",
                snippet = """
                    android {
                        defaultConfig {
                            multiDexEnabled = false   // only after R8 has been measured to fit
                        }
                    }
                """.trimIndent(),
                target = "app/build.gradle.kts"
            )
        }

        val abis = resources?.abis.orEmpty()
        if (abis.size > 1) {
            val libBytes = resources?.nativeLibs?.sumOf { it.bytes } ?: 0L
            sections += RecipeSection(
                id = "abis",
                title = "Ship one ABI per artifact",
                rationale = "The APK bundles ${abis.joinToString(", ")} (${formatBytes(libBytes)} of native code). " +
                    "Play delivers per-device splits if you let it.",
                snippet = """
                    android {
                        splits {
                            abi {
                                isEnable = true
                                reset()
                                include("arm64-v8a", "armeabi-v7a", "x86_64")
                                isUniversalApk = false
                            }
                        }
                    }
                """.trimIndent(),
                target = "app/build.gradle.kts",
                estimatedSavingBytes = if (abis.isEmpty()) 0 else (libBytes / abis.size) * (abis.size - 1)
            )
        }

        sections += baselineProfileSection()
        sections += dependencySection(report)

        val notes = buildList {
            add("A recipe only helps an app you can rebuild. For a third-party APK, treat these as questions to raise with its publisher.")
            if (!userOwnsSource) {
                add("You have not marked these apps as yours, so this recipe is informational: apply it only to a project you own.")
            }
            add("Measure after every change: scan the new APK and compare it with the old report on the Compare tab.")
            if (report.meta.analysisMode.contains("metadata only")) {
                add("This report is manifest-level only, so size findings are unavailable and the recipe covers configuration only.")
            }
        }

        return OptimizationRecipe(sections = sections, notes = notes)
    }

    private fun r8Section(report: ScanReport) = RecipeSection(
        id = "r8",
        title = "Turn on R8 with full mode",
        rationale = "Shrinking and obfuscation are the largest single lever on both size and " +
            "reverse-engineering cost. The APK currently reports ${report.findings.size} findings; " +
            "shrinking also removes dead code that static analysis flags.",
        snippet = """
            android {
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                }
            }
        """.trimIndent(),
        target = "app/build.gradle.kts"
    )

    private fun resourceShrinkerSection() = RecipeSection(
        id = "res-shrink",
        title = "Keep resources shrinking on every build",
        rationale = "Resource shrinking removes drawables, layouts and strings that no code path reaches. " +
            "It is skipped when a shrinker rule keeps a whole package, so check the shrinker report.",
        snippet = """
            android {
                buildTypes {
                    release {
                        isShrinkResources = true
                    }
                }
                // The shrinker report lists what was removed and what was kept:
                // app/build/outputs/mapping/release/resources.txt
            }
        """.trimIndent(),
        target = "app/build.gradle.kts"
    )

    private fun baselineProfileSection() = RecipeSection(
        id = "baseline",
        title = "Add a Baseline Profile",
        rationale = "A baseline profile lets the ART compiler pre-compile the startup path, which is the " +
            "one startup improvement that can be measured from a build rather than guessed.",
        snippet = """
            // app/build.gradle.kts
            plugins {
                id("androidx.baselineprofile")
            }
            dependencies {
                baselineProfile(project(":baselineprofile"))
            }

            // baselineprofile/src/main/kotlin/StartupBenchmark.kt
            @RunWith(AndroidJUnit4::class)
            class StartupBenchmark {
                @get:Rule val rule = MacrobenchmarkRule()

                @Test fun startup() = rule.measureRepeated(
                    packageName = "your.package.name",
                    metrics = listOf(StartupTimingMetric()),
                    iterations = 10,
                    startupMode = StartupMode.COLD
                ) { pressHome(); startActivityAndWait() }
            }
        """.trimIndent(),
        target = "app/build.gradle.kts"
    )

    private fun dependencySection(report: ScanReport): RecipeSection {
        val roots = report.apk.dex.packageRoots.take(6).joinToString(", ") { it.prefix }
        return RecipeSection(
            id = "deps",
            title = "Audit the dependency graph",
            rationale = "Third-party packages found in the bytecode: ${roots.ifBlank { "none detected" }}. " +
                "Versions are not recoverable from an APK, so check them in your build instead of guessing here.",
            snippet = """
                // List what actually ships, then look for duplicates and unused SDKs:
                //   ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
                //   ./gradlew :app:dependencyInsight --dependency okhttp
                //
                // Common wins: one HTTP client, one image loader, one analytics SDK.
            """.trimIndent(),
            language = "shell",
            target = "build"
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }
}
