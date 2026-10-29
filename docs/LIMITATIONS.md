# Limitations

These are printed in every report, not buried here.

1. **Static analysis only.** The APK is never installed, loaded into a class loader or executed.
2. **Capability, not behaviour.** A finding describes what the code can do. What it does at runtime
   is unknown.
3. **Obfuscation and packers.** Code unpacked at runtime, or reached only via reflection, is
   partially or fully invisible.
4. **No dependency versions.** An APK does not carry them, so PulseForge never claims an SDK is
   vulnerable. It names the SDK and tells you how to obtain versions (Gradle dependency report, or
   the vendor's SBOM).
5. **No runtime metrics.** Startup time, FPS, memory in use, battery and network traffic cannot be
   measured from a file; they are listed as unavailable with the tool that measures them.
6. **No causal claims.** The Compare tab shows a delta between two reports; it cannot prove a change
   caused it.
7. **Clean ≠ secure.** A report with no findings means these checks found nothing.
8. **Third-party APKs are read-only.** PulseForge reports and recommends; it only proposes code
   changes for source files the user supplies.
9. **Heuristics have false positives.** Every pattern-match finding carries a false-positive note.
10. **Coverage varies with the file.** If the manifest, `resources.arsc` or the DEX tables cannot be
    read, the dependent checks do not run and the report says so.

## Known implementation limits

* Images are dimension-checked for up to 3000 entries per scan; beyond that the list is truncated.
* Duplicate detection hashes at most 400 candidate entries (16 MB each).
* Text assets larger than 192 KB are not scanned for secrets.
* DEX parsing reads the header, string/type/method/class tables — not bytecode, so data-flow
  questions ("does this value reach that sink?") are out of scope.
* PDF export uses `android.graphics.pdf`, so it renders identically on device and under Robolectric,
  but fonts are the platform defaults.
