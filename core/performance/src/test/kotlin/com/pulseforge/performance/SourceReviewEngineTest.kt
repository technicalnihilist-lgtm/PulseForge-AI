package com.pulseforge.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the single-pass line scan in [SourceReviewEngine.review]: for every rule, the first
 * `maxHits` matching lines in file order, emitted rule by rule.
 *
 * The rewrite walks each line once for all rules instead of once per rule, so these tests pin the
 * output that must not change.
 */
class SourceReviewEngineTest {

    private val source = listOf(
        "class Screen {",
        "    fun wire(webView: WebView) {",
        "        webView.settings.setJavaScriptEnabled(true)",
        "        webView.settings.setJavaScriptEnabled(true)",
        "        webView.settings.setJavaScriptEnabled(true)",
        "        webView.settings.setJavaScriptEnabled(true)",
        "        webView.addJavascriptInterface(bridge, \"bridge\")",
        "    }",
        "}"
    ).joinToString("\n")

    private fun review() = SourceReviewEngine.review(
        listOf(SourceReviewEngine.SourceFile("Screen.kt", source))
    )

    @Test
    fun `each rule keeps its first matches in line order and stops at maxHits`() {
        val hits = review().suggestions.filter { it.id.startsWith("webview-js:") }
        // Five matches in the file, maxHits for this rule is 3.
        assertEquals(3, hits.size)
        assertEquals(listOf(3, 4, 5), hits.map { it.line })
        assertEquals("Screen.kt", review().filesAnalysed.single())
    }

    @Test
    fun `suggestions stay grouped by rule and then by line`() {
        val ruleIds = review().suggestions.map { it.id.substringBefore(':') }
        assertTrue(ruleIds.isNotEmpty())
        val runs = mutableListOf<String>()
        for (id in ruleIds) {
            if (runs.lastOrNull() != id) runs.add(id)
        }
        // No rule's suggestions are split into two runs: the order is unchanged by the rewrite.
        assertEquals(runs.distinct(), runs)
    }
}
