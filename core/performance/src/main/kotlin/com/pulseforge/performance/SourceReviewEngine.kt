package com.pulseforge.performance

import com.pulseforge.model.Confidence
import com.pulseforge.model.SourceReview
import com.pulseforge.model.SourceSuggestion

/**
 * Reviews source files the user explicitly supplies and proposes concrete changes.
 *
 * This only runs on files the user picks; PulseForge never fetches source for a third-party APK.
 * Every suggestion shows the current snippet, the proposed snippet and a test to add, so a human
 * reviews the diff instead of trusting a rewrite.
 */
object SourceReviewEngine {

    data class SourceFile(val path: String, val content: String)

    private data class Rule(
        val id: String,
        val pattern: Regex,
        val title: String,
        val problem: String,
        val suggestion: String,
        val proposed: (String) -> String,
        val test: String? = null,
        val confidence: Confidence = Confidence.HIGH,
        val maxHits: Int = 3
    )

    private val rules = listOf(
        Rule(
            id = "webview-js",
            pattern = Regex("""setJavaScriptEnabled\s*\(\s*true\s*\)"""),
            title = "JavaScript enabled in a WebView",
            problem = "Enabling JavaScript on a WebView that can reach untrusted content widens the attack surface, " +
                "especially together with a JavaScript bridge.",
            suggestion = "Keep JavaScript off unless the page needs it, and restrict the WebView to first-party HTTPS origins.",
            proposed = { indent ->
                "$indent// Keep JS off unless the content genuinely needs it.\n" +
                    "${indent}webView.settings.javaScriptEnabled = false\n" +
                    "${indent}webView.settings.domStorageEnabled = false"
            },
            test = """
                @Test
                fun webViewDoesNotEnableJavaScript() {
                    // Assert on your own WebView factory: JS must be disabled by default.
                    assertFalse(createWebView().settings.javaScriptEnabled)
                }
            """.trimIndent()
        ),
        Rule(
            id = "webview-bridge",
            pattern = Regex("""addJavascriptInterface\s*\("""),
            title = "JavaScript bridge exposed",
            problem = "addJavascriptInterface exposes annotated methods to any page loaded in that WebView.",
            suggestion = "Prefer WebViewCompat.addWebMessageListener with an explicit origin allow-list, and keep any " +
                "remaining bridge method free of side effects.",
            proposed = { indent ->
                "${indent}WebViewCompat.addWebMessageListener(\n" +
                    "$indent    webView,\n" +
                    "$indent    \"pulseBridge\",\n" +
                    "$indent    setOf(\"https://app.example.com\"),\n" +
                    "$indent    object : WebViewCompat.WebMessageListener {\n" +
                    "$indent        override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {\n" +
                    "$indent            // Validate message.data before doing anything with it.\n" +
                    "$indent        }\n" +
                    "$indent    }\n" +
                    "$indent)"
            },
            test = """
                @Test
                fun bridgeRejectsUntrustedOrigin() {
                    // Drive the listener with a message from an untrusted origin and assert no state changes.
                }
            """.trimIndent()
        ),
        Rule(
            id = "webview-file-access",
            pattern = Regex("""setAllowUniversalAccessFromFileURLs\s*\(\s*true\s*\)|setAllowFileAccessFromFileURLs\s*\(\s*true\s*\)"""),
            title = "WebView allows file pages to reach other origins",
            problem = "A local file page can then read local files and send them off-device.",
            suggestion = "Remove the call (it defaults to false) and serve local content through WebViewAssetLoader.",
            proposed = { indent ->
                "${indent}// Serve local assets over an https origin instead of a file URL\n" +
                    "${indent}webView.loadUrl(\"https://appassets.androidplatform.net/assets/index.html\")"
            }
        ),
        Rule(
            id = "manifest-allow-backup",
            pattern = Regex("""android:allowBackup\s*=\s*"true""""),
            title = "Backups enabled in the manifest",
            problem = "App-private data can leave the device through cloud or adb backup.",
            suggestion = "Disable backup, or exclude sensitive directories with dataExtractionRules.",
            proposed = { indent ->
                "${indent}android:allowBackup=\"false\"\n" +
                    "${indent}android:dataExtractionRules=\"@xml/data_extraction_rules\""
            }
        ),
        Rule(
            id = "manifest-exported",
            pattern = Regex("""android:exported\s*=\s*"true""""),
            title = "Explicitly exported component",
            problem = "Any app on the device can start this component.",
            suggestion = "Set exported=false unless another app must reach it; if it must, guard it with a signature permission.",
            proposed = { indent -> "${indent}android:exported=\"false\"" }
        ),
        Rule(
            id = "manifest-cleartext",
            pattern = Regex("""android:usesCleartextTraffic\s*=\s*"true""""),
            title = "Cleartext traffic allowed",
            problem = "Plain HTTP is permitted for every host.",
            suggestion = "Remove the flag and add a network security config that forbids cleartext.",
            proposed = { indent ->
                "${indent}android:usesCleartextTraffic=\"false\"\n" +
                    "${indent}android:networkSecurityConfig=\"@xml/network_security_config\""
            }
        ),
        Rule(
            id = "weak-hash",
            pattern = Regex("""MessageDigest\.getInstance\s*\(\s*"(MD5|SHA-?1)"\s*\)"""),
            title = "Weak hash algorithm",
            problem = "MD5 and SHA-1 are unsuitable for integrity or password hashing.",
            suggestion = "Use SHA-256 for integrity; for passwords use a slow KDF such as PBKDF2 or Argon2.",
            proposed = { indent -> "${indent}MessageDigest.getInstance(\"SHA-256\")" },
            test = """
                @Test
                fun hashesUseSha256() {
                    assertEquals("SHA-256", HashFactory.create().algorithm)
                }
            """.trimIndent()
        ),
        Rule(
            id = "weak-cipher",
            pattern = Regex("""Cipher\.getInstance\s*\(\s*"[^"]*(ECB|DES|RC4)[^"]*"\s*\)"""),
            title = "Weak cipher mode",
            problem = "ECB leaks plaintext structure; DES and RC4 are deprecated.",
            suggestion = "Use AES/GCM/NoPadding with a fresh random IV per message and a Keystore-backed key.",
            proposed = { indent ->
                "${indent}val cipher = Cipher.getInstance(\"AES/GCM/NoPadding\")\n" +
                    "${indent}val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }\n" +
                    "${indent}cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))"
            }
        ),
        Rule(
            id = "insecure-random",
            pattern = Regex("""\bRandom\s*\(\s*\)"""),
            title = "Predictable random source",
            problem = "java.util.Random is predictable and unsuitable for tokens, IVs or salts.",
            suggestion = "Use java.security.SecureRandom for anything security-relevant.",
            proposed = { indent -> "${indent}val random = SecureRandom()" }
        ),
        Rule(
            id = "hardcoded-secret",
            pattern = Regex("""(?i)\b(val|var|String)\s+(\w*(?:key|secret|token|password)\w*)\s*=\s*"[^"]{8,}""""),
            title = "Hardcoded credential in source",
            problem = "A literal that looks like a credential is compiled into the APK, where anyone can read it.",
            suggestion = "Read the value from BuildConfig injected at build time, or fetch it from your backend at runtime.",
            proposed = { indent ->
                "${indent}// build.gradle.kts: buildConfigField(\"String\", \"API_KEY\", providers.environmentVariable(\"API_KEY\").get())\n" +
                    "${indent}private val apiKey: String = BuildConfig.API_KEY"
            },
            confidence = Confidence.MEDIUM
        ),
        Rule(
            id = "sensitive-log",
            pattern = Regex("""Log\.[dviwe]\s*\([^)]*(password|token|secret|apiKey)""", RegexOption.IGNORE_CASE),
            title = "Sensitive value written to logcat",
            problem = "Logs end up in bug reports and crash tooling.",
            suggestion = "Remove the log statement, or log a redacted identifier instead of the value.",
            proposed = { indent -> "${indent}Log.d(TAG, \"auth attempt\") // never log the token itself" }
        ),
        Rule(
            id = "bitmap-no-sampling",
            pattern = Regex("""BitmapFactory\.decode(File|Stream|Resource)\s*\("""),
            title = "Bitmap decoded without sampling",
            problem = "Decoding at full resolution allocates width x height x 4 bytes, a common OOM source.",
            suggestion = "Measure the view, then decode with inSampleSize (or use Coil/Glide which do it for you).",
            proposed = { indent ->
                "${indent}val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }\n" +
                    "${indent}BitmapFactory.decodeFile(path, bounds)\n" +
                    "${indent}val sample = maxOf(1, minOf(bounds.outWidth / targetW, bounds.outHeight / targetH))\n" +
                    "${indent}val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })"
            },
            confidence = Confidence.MEDIUM
        )
    )

    fun review(files: List<SourceFile>): SourceReview {
        val suggestions = mutableListOf<SourceSuggestion>()
        val notes = mutableListOf<String>()

        for (file in files) {
            if (file.content.length > 2_000_000) {
                notes.add("${file.path} was skipped: larger than 2 MB.")
                continue
            }
            val lines = file.content.lines()
            for (rule in rules) {
                var hits = 0
                for ((index, line) in lines.withIndex()) {
                    if (hits >= rule.maxHits) break
                    if (!rule.pattern.containsMatchIn(line)) continue
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    suggestions.add(
                        SourceSuggestion(
                            id = "${rule.id}:${file.path}:${index + 1}",
                            title = rule.title,
                            filePath = file.path,
                            line = index + 1,
                            problem = rule.problem,
                            suggestion = rule.suggestion,
                            currentSnippet = line.trim(),
                            proposedSnippet = rule.proposed(indent),
                            testSnippet = rule.test,
                            confidence = rule.confidence
                        )
                    )
                    hits++
                }
            }
        }

        if (files.isEmpty()) {
            notes.add("No source files were supplied. Add the files you own to get concrete code changes.")
        }
        notes.add("Suggestions are pattern-based and must be reviewed: PulseForge does not compile or run your project.")
        notes.add("PulseForge never modifies your files. Apply the changes yourself and run your test suite.")

        return SourceReview(
            filesAnalysed = files.map { it.path },
            suggestions = suggestions.sortedBy { it.filePath },
            notes = notes
        )
    }
}
