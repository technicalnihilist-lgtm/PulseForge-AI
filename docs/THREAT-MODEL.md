# Threat model

## Assets PulseForge handles

1. **The imported APK** — may be confidential (an unreleased build) or untrusted (malware).
2. **The report** — contains structural detail and masked secrets.
3. **Source files the user optionally supplies** — the most sensitive input the app ever touches.
4. **The scan history** — reveals which apps the user examines.

## Adversaries

| Adversary | Goal | Mitigation |
|---|---|---|
| A malicious APK | Exploit the analyser: zip bombs, malformed AXML/DEX, huge entries, pathological string pools | Size caps, entry-count cap, per-entry caps, bounds-checked reads, tree-depth cap, `OutOfMemoryError` handling, no execution, no class loading |
| A malicious document provider | Feed a non-APK or truncated file | ZIP magic check, structured `ApkException` codes, no partial state saved |
| Another app on the device | Read PulseForge's data | App-private storage, `allowBackup=false`, backup/extraction rules exclude scans and the DB, no exported components |
| A network attacker | Exfiltrate APKs or reports | The app has **no INTERNET permission**; nothing can be sent |
| A shoulder-surfer / screenshots | Read secrets from the screen | Secrets masked in evidence and reports; full values never rendered |
| The analyst themselves | Over-trust a heuristic | Evidence type + confidence on every finding, false-positive note, explicit limitations, no "secure" claim |

## What PulseForge explicitly does not do

* Execute, install, or load the analysed APK (no `DexClassLoader`, no `Runtime.exec` on the target).
* Bypass signature verification, licence checks, payment protections, root detection or any access
  control.
* Exploit anything it finds; there is no proof-of-concept generation.
* Upload anything. There is no network code path at all.
* Modify a third-party APK. Source suggestions only apply to files the user supplies.

## Trust boundaries

* **SAF URI → FileVault**: treated as untrusted bytes; only copied, never parsed in place.
* **FileVault → parsers**: untrusted structure; every read bounds-checked.
* **Parsers → rules**: trusted in-memory data, but rules still cannot throw the scan away (a rule
  failure becomes an `ENGINE_ERROR` info finding).
* **Report → user**: redacted twice (rule-level masking, then `ReportRedactor`).

## Residual risks (accepted, documented)

1. A crafted APK may still exhaust CPU on a very slow device; the scan is cancellable and bounded.
2. `apksig` runs in-process; a vulnerability there would be reachable. It is wrapped in
   `catch (Throwable)` so a verifier failure degrades to "not verified" rather than a crash.
3. Masking is pattern-based; an unusual secret format may survive into a report. Reports stay on
   device unless the user exports them.
4. Robolectric-based UI tests do not exercise real hardware rendering.
