# Security methodology

## Principles

1. **Evidence over claims.** Every finding records what was observed (`evidenceType`) and how much
   the analyser trusts it (`confidence`).
   * `DIRECT_OBSERVATION` — read from the artifact (manifest attribute, certificate field, ZIP entry).
   * `PATTERN_MATCH` — matched a bytecode/string/resource pattern; benign code can match too.
   * `INFERENCE` — concluded from several observations.
2. **No exploitability claims.** PulseForge says what is exposed, never that it is exploitable.
3. **A false-positive note on every finding** so a reviewer knows what would make it benign.
4. **No invented facts.** If a check cannot run (unreadable manifest, unparsable ARSC) the report
   says so and the affected findings are absent rather than guessed.

## Rule catalogue (ruleset 2024.10-r1)

| ID | Rule | Severity | MASVS | CWE |
|---|---|---|---|---|
| PF-MAN-001 | Exported component without permission | High/Medium | PLATFORM-1 | 926 |
| PF-MAN-002 | Risky intent filter / deep link | High/Medium | PLATFORM-1 | 925 |
| PF-MAN-003 | Unprotected content provider | High | PLATFORM-1 | 926, 22 |
| PF-MAN-004 | Custom permission not signature-level | Medium | PLATFORM-1 | 926 |
| PF-MAN-005 | Debuggable build | Critical | CODE-4 | 489 |
| PF-MAN-006 | testOnly build | Low | CODE-4 | — |
| PF-MAN-007 | Backup enabled / default | High/Medium | STORAGE-1 | 312 |
| PF-MAN-008 | Cleartext traffic allowed | High | NETWORK-1 | 319 |
| PF-MAN-009 | requestLegacyExternalStorage | Medium | STORAGE-2 | 312 |
| PF-WEB-001 | WebView bridge / file access / savePassword / multiwindow | Critical–Low | PLATFORM-2 | 749, 552 |
| PF-TLS-001 | SSL errors ignored / custom trust manager / hostname check off | High–Medium | NETWORK-1 | 295 |
| PF-NET-002 | No pinning detected (informational) | Info | NETWORK-2 | — |
| PF-NET-003 | Hardcoded cleartext URLs | Medium | NETWORK-1 | 319 |
| PF-CRY-001 | MD5 / SHA-1 | High/Medium | CRYPTO-1 | 328 |
| PF-CRY-002 | ECB, DES, RC4, Blowfish | High | CRYPTO-1 | 327 |
| PF-CRY-003 | java.util.Random without SecureRandom | Low | CRYPTO-1 | 330 |
| PF-SEC-001 | AWS / Google / Stripe / PEM / Slack / URL creds / JWT / generic | Critical–Medium | STORAGE-1 | 798 |
| PF-PRM-001 | Broad permission surface, restricted permissions, risky combinations | High–Medium | PRIVACY-1/2 | 250 |
| PF-SIG-001 | Debug key, v1-only, expired, short key, missing certificate | Critical–Medium | CODE-1 | 489, 326 |
| PF-COD-001 | Runtime.exec / ProcessBuilder | Medium | CODE-1 | 78 |
| PF-COD-002 | Dynamic code loading | High | RESILIENCE-1 | 494 |
| PF-COD-003 | Sensitive logging | Low | STORAGE-2 | 532 |
| PF-COD-004 | Heavy reflection (coverage note) | Info | RESILIENCE-1 | — |
| PF-DEP-001 | Deprecated HTTP stack, SDK inventory | Medium/Info | NETWORK-1 | 1104 |
| PF-CMP-001 | targetSdk below 12, no 64-bit, multiple ABIs | High–Low | CODE-1 | 1104 |

## How a few of them actually work

**Exported components.** `ComponentInfo.effectiveExported(targetSdk)` mirrors the platform: an
explicit `android:exported`, or (targetSdk < 31) an implicit export when an intent filter exists.
Launcher activities and components with a permission are excluded — they are exported by design.

**Cleartext.** The manifest flag, the targetSdk default, and any compiled `res/xml` network security
config that sets `cleartextTrafficPermitted` are all considered. A config that forbids cleartext
downgrades an unset flag to Low.

**Secrets.** Patterns run over the DEX string pool, `resources.arsc` values and text assets. Values
are masked at the rule, and `ReportRedactor` re-masks every evidence value, summary and detail as a
second pass. A URL is reported as the URL only, never the surrounding JSON that contains it.

**WebView.** Method references are read from the DEX method table — `setJavaScriptEnabled` lives on
`WebSettings`, `addJavascriptInterface` on `WebView`. A bridge combined with JavaScript escalates to
Critical. The argument passed at the call site is not visible, which is stated in the false-positive
note.

## What the analyser cannot see

* Code loaded at runtime, unpacked after install, or reached only through reflection.
* Which URL a WebView actually loads.
* Server-side controls, backend authentication, certificate pinning inside a bundled SDK.
* Whether an exported component validates its input.

## Deliberate non-goals

PulseForge does not bypass signature checks, licence checks, payment protections or access controls;
it does not exploit anything; it does not execute the APK.
