#!/usr/bin/env python3
"""Builds the synthetic APK fixtures used by the tests and the sample reports.

Everything here is generated locally from source that ships in this repo, so the fixtures are
reproducible and contain no third-party code. Each fixture is a real, signed APK built with the
official Android build tools (aapt2, d8, zipalign, apksigner).

Usage:
    export ANDROID_HOME=/path/to/android-sdk      # needs platforms;android-34 and build-tools;34.0.0
    python3 tools/make-fixtures.py

The generated APKs are deliberately not committed: they are build artefacts of this script, and CI
regenerates them before running the tests.
"""
import os, shutil, subprocess, zipfile, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "fixtures"
WORK = ROOT / "build" / "fixture-work"
ANDROID_HOME = os.environ.get("ANDROID_HOME", "")
BUILD_TOOLS = os.environ.get("BUILD_TOOLS", os.path.join(ANDROID_HOME, "build-tools", "34.0.0"))
PLATFORM_JAR = os.path.join(ANDROID_HOME, "platforms", "android-34", "android.jar")
KEYSTORE = FIXTURES / "pulseforge-test.jks"
KS_PASS = "pulseforge"

INSECURE_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.pulseforge.fixture.insecure"
    android:versionCode="7"
    android:versionName="1.4.2">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="30" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <application
        android:label="Fixture Insecure"
        android:debuggable="true"
        android:allowBackup="true"
        android:usesCleartextTraffic="true"
        android:extractNativeLibs="true"
        android:name="com.pulseforge.fixture.InsecureActivity">
        <activity android:name="com.pulseforge.fixture.InsecureActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name="com.pulseforge.fixture.DeepLinkActivity" android:exported="true">
            <intent-filter android:priority="100">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="http" android:host="*" />
            </intent-filter>
        </activity>
        <service android:name="com.pulseforge.fixture.SyncService" android:exported="true" />
        <receiver android:name="com.pulseforge.fixture.PushReceiver" android:exported="true">
            <intent-filter>
                <action android:name="com.pulseforge.fixture.PUSH" />
            </intent-filter>
        </receiver>
        <provider
            android:name="com.pulseforge.fixture.DataProvider"
            android:authorities="com.pulseforge.fixture.provider"
            android:exported="true" />
    </application>
</manifest>
"""

CLEAN_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.pulseforge.fixture.clean"
    android:versionCode="12"
    android:versionName="2.0.0">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34" />
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="Fixture Clean"
        android:debuggable="false"
        android:allowBackup="false"
        android:usesCleartextTraffic="false"
        android:networkSecurityConfig="@xml/network_security_config"
        android:extractNativeLibs="false">
        <activity android:name="com.pulseforge.fixture.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name="com.pulseforge.fixture.SettingsActivity" android:exported="false" />
        <service android:name="com.pulseforge.fixture.WorkerService" android:exported="false" />
    </application>
</manifest>
"""

NETWORK_CONFIG = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
"""

INSECURE_JAVA = """package com.pulseforge.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.security.MessageDigest;
import javax.crypto.Cipher;

public class InsecureActivity extends Activity {
    public static final String AWS_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    public static final String DB_PASSWORD = "SuperSecretPassword123!";
    private static final String ENDPOINT = "http://api.fixture.example.com/v1/login?api_key=AIzaSyDummyKeyForTesting1234567890abcd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.loadUrl(ENDPOINT);
        try {
            MessageDigest.getInstance("MD5");
            Cipher.getInstance("AES/ECB/PKCS5Padding");
            Cipher.getInstance("DES");
            java.util.Random random = new java.util.Random();
            random.nextInt();
            Runtime.getRuntime().exec("logcat -d");
        } catch (Exception ignored) {
        }
        android.util.Log.d("Fixture", "debug " + DB_PASSWORD);
    }

    public class Bridge {
        @JavascriptInterface
        public String getKey() {
            return AWS_ACCESS_KEY;
        }
    }
}
"""

CLEAN_JAVA = """package com.pulseforge.fixture;

import android.app.Activity;
import android.os.Bundle;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            MessageDigest.getInstance("SHA-256");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new GCMParameterSpec(128, new byte[12]));
        } catch (Exception ignored) {
        }
    }
}
"""


def run(cmd, **kwargs):
    result = subprocess.run(cmd, capture_output=True, text=True, **kwargs)
    if result.returncode != 0:
        print("FAILED:", " ".join(str(c) for c in cmd))
        print(result.stdout[-3000:])
        print(result.stderr[-3000:])
        raise SystemExit(1)
    return result


def make_keystore():
    if KEYSTORE.exists():
        return
    run(["keytool", "-genkeypair", "-keystore", str(KEYSTORE), "-alias", "pulseforge",
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
         "-storepass", KS_PASS, "-keypass", KS_PASS,
         "-dname", "CN=PulseForge Test Fixture,O=PulseForge,C=IN"])


def compile_dex(source, name, out_dir):
    src_dir = out_dir / "src"
    src_dir.mkdir(parents=True, exist_ok=True)
    java_file = src_dir / f"{name}.java"
    java_file.write_text(source)
    classes = out_dir / "classes"
    classes.mkdir(exist_ok=True)
    run(["javac", "-source", "8", "-target", "8", "-nowarn",
         "-bootclasspath", PLATFORM_JAR, "-classpath", PLATFORM_JAR,
         "-d", str(classes), str(java_file)])
    dex_out = out_dir / "dex"
    dex_out.mkdir(exist_ok=True)
    run([os.path.join(BUILD_TOOLS, "d8"), "--min-api", "26", "--lib", PLATFORM_JAR,
         "--output", str(dex_out)] + [str(p) for p in classes.rglob("*.class")])
    return dex_out / "classes.dex"


def big_png(path, size):
    """Writes an uncompressible (noise) PNG - exactly the kind of asset an app should not ship."""
    try:
        from PIL import Image
        import random
        rnd = random.Random(20240501)
        img = Image.new("RGB", (size, size))
        px = img.load()
        for y in range(size):
            for x in range(size):
                px[x, y] = (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256))
        img.save(path, "PNG", optimize=False)
    except ImportError:
        import struct, zlib

        def chunk(t, d):
            c = t + d
            return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

        raw = b"\x00" * (size * 3 + 1)
        data = (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw * size))
                + chunk(b"IEND", b""))
        pathlib.Path(path).write_bytes(data)


def build_apk(manifest, dex, out_apk, extras=None, res_files=None):
    stage = out_apk.parent / (out_apk.stem + "-stage")
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    (stage / "AndroidManifest.xml").write_text(manifest)
    link_args = [os.path.join(BUILD_TOOLS, "aapt2"), "link", "-o", str(stage / "base.apk"),
                 "--manifest", str(stage / "AndroidManifest.xml"), "-I", PLATFORM_JAR,
                 "--min-sdk-version", "26", "--target-sdk-version", "34"]
    if res_files:
        res_dir = stage / "res"
        for rel, content in res_files.items():
            target = res_dir / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content if isinstance(content, bytes) else content.encode())
        compiled = stage / "compiled.zip"
        run([os.path.join(BUILD_TOOLS, "aapt2"), "compile", "--dir", str(res_dir), "-o", str(compiled)])
        link_args += ["-R", str(compiled), "--auto-add-overlay"]
    run(link_args)

    with zipfile.ZipFile(stage / "base.apk", "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
        for name, data in (extras or {}).items():
            z.writestr(name, data)

    aligned = stage / "aligned.apk"
    run([os.path.join(BUILD_TOOLS, "zipalign"), "-f", "-p", "4", str(stage / "base.apk"), str(aligned)])
    run([os.path.join(BUILD_TOOLS, "apksigner"), "sign",
         "--ks", str(KEYSTORE), "--ks-pass", f"pass:{KS_PASS}", "--key-pass", f"pass:{KS_PASS}",
         "--ks-key-alias", "pulseforge", "--v1-signing-enabled", "true",
         "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--out", str(out_apk), str(aligned)])
    run([os.path.join(BUILD_TOOLS, "apksigner"), "verify", str(out_apk)])


def main():
    if not ANDROID_HOME:
        print("ANDROID_HOME is not set; see the header of this script.")
        raise SystemExit(2)
    FIXTURES.mkdir(parents=True, exist_ok=True)
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    make_keystore()

    insecure_dir = WORK / "insecure"
    insecure_dir.mkdir()
    insecure_dex = compile_dex(INSECURE_JAVA, "InsecureActivity", insecure_dir)
    big = WORK / "big.png"
    big_png(big, 1200)
    extras = {
        "assets/config.json": '{"api_key": "AIzaSyDummyKeyForTesting1234567890abcd", "endpoint": "http://legacy.example.com"}',
        "assets/credentials.properties": "aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n",
        "assets/large_hero.png": big.read_bytes(),
        "assets/duplicate_a.png": big.read_bytes(),
        "res/raw/license_key.txt": "PROD-LICENSE-9F8A7B6C5D4E3F2A1B0C\n",
        "lib/arm64-v8a/libfixture.so": b"\x7fELF" + b"\x00" * 4096,
        "lib/x86_64/libfixture.so": b"\x7fELF" + b"\x00" * 4096,
    }
    build_apk(INSECURE_MANIFEST, insecure_dex, FIXTURES / "fixture-insecure.apk", extras)

    clean_dir = WORK / "clean"
    clean_dir.mkdir()
    clean_dex = compile_dex(CLEAN_JAVA, "MainActivity", clean_dir)
    build_apk(CLEAN_MANIFEST, clean_dex, FIXTURES / "fixture-clean.apk",
              extras={"assets/strings.json": '{"greeting": "hello"}'},
              res_files={"xml/network_security_config.xml": NETWORK_CONFIG})

    data = (FIXTURES / "fixture-insecure.apk").read_bytes()
    (FIXTURES / "fixture-truncated.apk").write_bytes(data[: len(data) // 2])
    (FIXTURES / "fixture-not-an-apk.bin").write_bytes(b"This is not an APK, just a text file.\n" * 20)

    for f in sorted(FIXTURES.glob("*.apk")):
        print(f"{f.name:32s} {f.stat().st_size:>9,d} bytes")


if __name__ == "__main__":
    main()
