# Architecture

## Module graph

```
:app  ──► :core:ui
  │  ──► :core:data ──► :core:report ──► :core:security ──► :core:apk ──► :core:model
  │  ──► :core:ai   ──► :core:performance ──► :core:apk
  └────────────────────────────────────────────────────────────► :core:model
```

Dependencies point one way. `:core:model` has no Android dependency at all, and `:core:apk`,
`:core:security`, `:core:performance` and `:core:ai` are plain JVM modules, which is what makes the
analysis pipeline testable without a device.

## Data flow of one scan

1. **Import** — `ImportScreen` launches `ActivityResultContracts.OpenDocument()`. `FileVault.import`
   streams the document into `filesDir/scans/<scanId>/input.apk`, enforcing a 2 GB cap and the ZIP
   magic, then `ScanRepository.importApk` writes a `RUNNING` row to Room.
2. **Queue** — `ScanScheduler` enqueues a unique `ScanWorker` (WorkManager, `ExistingWorkPolicy.KEEP`).
   The worker is constructed by `PulseWorkerFactory`, which injects the repository and pipeline.
3. **Analyse** — `ScanPipeline.run` calls `ApkAnalyzer.analyze`, which produces an `ApkScanInput`:
   serialisable `ApkFacts` plus transient indexes (DEX strings, method refs, resource strings, text
   assets, flattened compiled XML).
4. **Rules** — `SecurityEngine` runs the 26 rules over that input; `PerformanceAnalyzer` and
   `PrivacyAnalyzer` build their sections.
5. **Score + redact** — `ScoreCalculator` produces a `ScoreCard` with every deduction itemised;
   `ReportRedactor.sanitize` rewrites any credential-shaped value that slipped through.
6. **Persist** — the report is serialised to JSON and stored in the same Room row as the summary
   columns the dashboard queries.
7. **Present** — screens are stateless composables fed by `AppViewModel`; report tabs read the
   deserialised `ScanReport`.

## Key types

| Type | Role |
|---|---|
| `ApkFacts` | Everything the parsers read, serialisable. The only thing rules consume besides indexes. |
| `ApkScanInput` | `ApkFacts` + transient `DexIndex`, resource strings, asset text, XML summaries. Never persisted. |
| `Finding` | One observation: severity, confidence, evidence type, evidence list, remediation, MASVS/CWE. |
| `ScanReport` | `schemaVersion` + engine info + facts + findings + performance + privacy + scoring + meta. |
| `ScanStage` | Ordered stages the UI renders progress against. |

## Why these parsers are hand-written

`apk-parser`, `axml` and friends either pull large dependency trees or hide the evidence. PulseForge
needs the *exact* attribute values, chunk offsets and DEX table rows it reports, so the binary
formats are parsed directly with bounds checks, depth caps and entry limits. `apksig` (official
Android tooling) is used only for signature *verification*; certificates are also extracted
independently so a verifier failure still yields usable evidence.

## Threading

* Analysis runs on `Dispatchers.IO` inside a `CoroutineWorker`.
* Progress is written to Room at most every 150 ms (and always at 100%), so the UI survives process
  death without hammering SQLite.
* Cancellation is cooperative: `coroutineContext.ensureActive()` between stages, and the worker maps
  `CancellationException` to a `CANCELLED` row.

## Extension points

* **New security rule** — implement `SecurityRule`, add it to `RuleCatalog.rules`, bump
  `RuleCatalog.VERSION`. Unit-test it against a fixture.
* **New report format** — add a writer in `:core:report` and an `ExportFormat` entry in `:app`.
* **Local model** — drop the asset at `app/src/main/assets/models/pulseforge-lite.tflite`;
  `AssetLocalModel` flips to available and the UI says so. No code path fakes it.
