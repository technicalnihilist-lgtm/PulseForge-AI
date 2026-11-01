# Performance notes

Where the app spends its time, what was done about it, and the budgets that keep it honest.

## Budgets enforced by tests

| Path | Budget | Test |
|---|---|---|
| `SecurityEngine.evaluate()` on the fixture APK | < 2000 ms per run | `SecurityEngineTest.rule engine stays within its time budget` |
| `JsonReportWriter.read()` of a full report | < 1500 ms per run | `ReportPipelineTest.json round trip stays within its time budget` |

These are regression guards, not benchmarks: a generous ceiling that fails if someone
re-introduces a full extra pass over the string pool.

## Work removed from the main thread

| Path | Before | Now |
|---|---|---|
| Reclaimable-bytes measurement | recursive directory walk on the caller's thread (main, during a device scan) | `Dispatchers.IO` |
| `isInstalled(package)` for the Optimize screen | `PackageManager.getPackageInfo` — a Binder round trip executed during composition | in-memory set populated once in the background |
| Installed-app listing | IO | IO (unchanged) |

## Repeated work removed

| Path | Before | Now |
|---|---|---|
| Secret scanning | 8 patterns × one full pass over the DEX string pool each | one pass testing all patterns, stopping when each quota is met |
| Algorithm-name lookup (`hasExact`) | lower-cased the entire string pool on every call | one memoised lower-cased set |
| Report loading | JSON deserialised again on every screen visit | 2-entry LRU keyed by report id, invalidated on delete |
| Optimization plan / build recipe | rebuilt on every recomposition of the report screen | memoised per `reportId` + ownership flag |
| Launcher-component query | `queryIntentActivities` on every metadata scan | 60 s TTL memo |

## Deliberate non-optimisations

* **DEX is read whole into memory.** Streaming a DEX header/tables parser would save peak memory but
  add a lot of pointer-chasing code for files that are usually under 30 MB; the per-file cap keeps
  it bounded.
* **Duplicates are confirmed with SHA-256.** CRC-32 + size alone would be faster and wrong.
* **Every rule runs on every scan.** Skipping rules when the manifest failed to parse would hide
  findings silently; the rules return empty instead, and the report says the check did not run.

## Measuring on a device

```bash
adb shell am start -n com.pulseforge.ai.debug/com.pulseforge.app.MainActivity
adb shell dumpsys gfxinfo com.pulseforge.ai.debug framestats   # frame times
adb logcat -s PulseForge                                        # scan stage timings
```

A static APK cannot tell you its own startup time or frame rate; see
[`PERFORMANCE-METHODOLOGY.md`](PERFORMANCE-METHODOLOGY.md) for what is measured versus estimated.
