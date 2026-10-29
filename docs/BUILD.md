# Build, test and release

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK platform | 34 (`platforms;android-34`) |
| Android build-tools | 34.0.0 (`aapt2`, `d8`, `zipalign`, `apksigner`) |
| Gradle | 8.7 (wrapper) |
| Kotlin | 1.9.24, Compose compiler 1.5.14 |

`local.properties` must point at the SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Reproducible steps

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export JAVA_HOME=/path/to/jdk-17

# fixtures used by every test suite (regenerated, not committed)
python3 tools/make-fixtures.py

# Robolectric runtime for the JVM UI tests (offline-friendly)
bash tools/fetch-robolectric.sh

./gradlew test                     # all unit tests
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:assembleRelease     # minified release APK
./gradlew :app:connectedDebugAndroidTest   # needs a device/emulator
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk` and `.../release/app-release.apk`.

## Release signing

Create `keystore.properties` in the repo root (git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=pulseforge
keyPassword=...
```

Without that file the release build falls back to the debug key so CI still produces an artifact.
The app shows its signing identity in the About screen and the report, so a debug-signed "release"
is never mistaken for a shipped one.

## Dependencies and licences

| Library | Version | Licence |
|---|---|---|
| Kotlin stdlib | 1.9.24 | Apache 2.0 |
| AndroidX Core KTX | 1.13.1 | Apache 2.0 |
| Compose BOM | 2024.06.00 | Apache 2.0 |
| Material 3 | 1.2.1 (BOM) | Apache 2.0 |
| Navigation Compose | 2.7.7 | Apache 2.0 |
| Lifecycle | 2.8.2 | Apache 2.0 |
| Room | 2.6.1 | Apache 2.0 |
| WorkManager | 2.9.0 | Apache 2.0 |
| DataStore | 1.1.1 | Apache 2.0 |
| KotlinX Coroutines | 1.8.1 | Apache 2.0 |
| KotlinX Serialization | 1.6.3 | Apache 2.0 |
| apksig (signature verification) | 8.5.2 | Apache 2.0 |
| JUnit | 4.13.2 | EPL 1.0 |
| Robolectric | 4.12.2 | MIT |
| androidx.test / Espresso | 1.5.0 / 3.5.1 | Apache 2.0 |

No third-party PDF, image or JSON-schema library is used: PDF export is `android.graphics.pdf`, image
dimensions come from hand-written header parsers, and JSON is `kotlinx.serialization`.

## CI

`.github/workflows/ci.yml` installs the SDK, generates fixtures, runs every unit test, assembles
debug + release, lints, **fails the build if the APK requests INTERNET**, and uploads the APKs, test
reports and screenshots as artifacts.
