# Privacy

## What leaves the device

Nothing. The app declares no permissions, and `INTERNET` is explicitly removed in the manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

Without that permission the process cannot open a socket, so an APK, source file, secret or report
cannot be uploaded even by accident or by a future bug in higher layers.

WorkManager normally merges in `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and
`FOREGROUND_SERVICE`; all four are removed too, because PulseForge uses no network constraints,
foreground work or reboot rescheduling. Trade-off: a scan interrupted by a reboot is not resumed —
it stays in history as failed and can be re-run.

The only way data leaves is a file the user exports through the system picker.

## What is stored, and for how long

| Data | Location | Removed when |
|---|---|---|
| Imported APK | `filesDir/scans/<scanId>/input.apk` | scan deleted, or immediately after the report if "keep copy" is off |
| Scan report (JSON) | Room `pulseforge.db` | scan deleted |
| Settings | DataStore preferences | app data cleared |

`backup_rules.xml` and `data_extraction_rules.xml` exclude `scans/`, `pulseforge.db` and the settings
file from cloud backup and device transfer. `allowBackup` is false. Orphaned vault directories are
swept on every app start.

## Secrets

* Rules mask the value before it becomes evidence (`Redactor.mask`, `Redactor.maskUrlSecrets`).
* `ReportRedactor.sanitize` runs over every report as a second pass: AWS/Google/Stripe/Slack keys,
  JWTs, PEM headers, URL userinfo and credential-shaped query parameters.
* URLs are reported as the URL only, never the JSON blob that contains them.
* The app never writes secrets to logcat.

## Permissions the app asks for

None. Files are read through the Storage Access Framework, which grants per-file access without a
storage permission.

## Analytics, telemetry, crash reporting

None. There is no SDK, no identifier, no network call.
