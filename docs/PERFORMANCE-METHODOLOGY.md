# Performance methodology

The report separates three kinds of numbers, and never blurs them.

## 1. Measured — read from the file

* APK size, uncompressed payload, entry count.
* Size composition by category (DEX, native libs, images, other resources, assets, signature).
* Image count and total image bytes; per-image dimensions parsed from PNG/JPEG/WebP/GIF/BMP headers
  (a few dozen bytes each, never a full decode).
* Native library size and the set of ABIs.
* DEX files, referenced methods, defined classes, string constants, package roots.
* Deepest compiled layout (derived from the indentation of the flattened `res/layout` XML).
* Byte-identical duplicates: grouped by CRC-32 + size, then confirmed by SHA-256.

## 2. Estimated — derived, with the basis printed next to the number

* **Largest bitmap in memory** = `width × height × 4` bytes (ARGB_8888). Accurate only if the bitmap
  is decoded at full size, which the basis text says.
* **Worst-case bitmap memory** = sum of the above for oversized images. An upper bound.
* **WebP saving** = 55% of oversized-image bytes. A mid-point of the 45–70% range typical for
  photographic content; labelled "estimate" and paired with a visual-review caveat.
* **ABI split saving** = native bytes ÷ ABI count × (ABI count − 1), assuming an even split.
* **Duplicate saving** = exact, because the files are byte-identical.

## 3. Unavailable — stated, never invented

Cold/warm startup, frame rate and jank, CPU and memory in use, battery and network consumption, ANR
and crash rates, real layout inflation cost. Each entry says *why* it cannot come from a file and
*which tool* measures it (Macrobenchmark, Perfetto, Studio Profiler, Battery Historian, Play vitals).

## Oversized-image thresholds

An image is reported when it is larger than 200 KB **or** above ~1.5 megapixels. Both criteria are
listed in the finding so a reviewer can disagree with the threshold rather than with a black box.

## Unused-asset heuristic

An `assets/` file larger than 8 KB is flagged when its filename never appears in the DEX string pool.
A path built at runtime from concatenated parts will not be seen, so the caveat says "verify before
deleting" and the action is marked as heuristic.

## Optional runtime profiling (authorized builds only)

PulseForge never installs or runs the APK. For your own build, use Jetpack Macrobenchmark:

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartup() = rule.measureRepeated(
        packageName = "com.example.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD
    ) { pressHome(); startActivityAndWait() }
}
```

Compare `StartupTimingMetric` and `FrameTimingMetric` before and after a change, then use the
Compare tab to diff the two reports. The app cannot prove causation; it shows the delta.
