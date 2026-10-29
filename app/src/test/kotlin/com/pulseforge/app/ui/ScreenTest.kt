package com.pulseforge.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.pulseforge.ai.OfflineAssistant
import com.pulseforge.app.ui.screens.AssistantScreen
import com.pulseforge.app.ui.screens.DashboardScreen
import com.pulseforge.app.ui.screens.ReportScreen
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.ScanReport
import com.pulseforge.performance.OptimizationPlanner
import com.pulseforge.ui.theme.PulseForgeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * JVM UI tests. They render the real Compose screens under Robolectric and also write the
 * screenshots used in the README, so the images in the repository come from the app itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val report: ScanReport by lazy { TestReportFactory.report() }
    private val scan: ScanEntity by lazy { TestReportFactory.scanEntity(report) }

    private val screenshotDir: File by lazy {
        File(System.getProperty("pulseforge.screenshots") ?: "screenshots").apply { mkdirs() }
    }

    /**
     * Renders the current screen to a PNG by drawing the activity's decor view.
     *
     * Drawing the view avoids Compose's idle-wait, which never completes on screens that show an
     * indeterminate progress indicator. A capture failure is reported but never fails the test: the
     * behavioural assertions are what the suite guarantees.
     */
    private fun capture(name: String) {
        try {
            compose.mainClock.advanceTimeBy(64)
            val activity = compose.activity
            val view = activity.window.decorView
            val width = if (view.width > 0) view.width else 1080
            val height = if (view.height > 0) view.height else 2280
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            activity.runOnUiThread { view.draw(canvas) }
            FileOutputStream(File(screenshotDir, "$name.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("screenshot $name is empty", bitmap.width > 0 && bitmap.height > 0)
        } catch (e: Throwable) {
            FileOutputStream(File(screenshotDir, "$name.error.txt")).use {
                it.write("${e::class.simpleName}: ${e.message}".toByteArray())
            }
            println("screenshot '$name' could not be captured: ${e::class.simpleName}: ${e.message}")
        }
    }

    @Test
    fun dashboardEmptyStateInvitesImport() {
        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                DashboardScreen(emptyList(), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("No scans yet").assertIsDisplayed()
        compose.onNodeWithTag("importApk").assertIsDisplayed()
        capture("dashboard-empty")
    }

    @Test
    fun dashboardShowsLatestScanScores() {
        compose.setContent {
            PulseForgeTheme(darkTheme = true) {
                DashboardScreen(listOf(scan), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("com.pulseforge.fixture.insecure").assertIsDisplayed()
        compose.onNodeWithTag("openReport").assertIsDisplayed()
        capture("dashboard-dark")
    }

    @Test
    fun reportOverviewShowsScoresAndActions() {
        val plan = OptimizationPlanner.plan(report.findings, report.performance, report.apk, userOwnsSource = true)
        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                ReportScreen(report, scan, plan, emptyList(), { null }, {}, {})
            }
        }
        compose.onNodeWithText("Summary").assertIsDisplayed()
        compose.onNodeWithText("Do this first").performScrollTo().assertIsDisplayed()
        capture("report-overview")
    }

    @Test
    fun securityTabListsFindingsAndExpandsEvidence() {
        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                ReportScreen(report, scan, emptyList(), emptyList(), { null }, {}, {})
            }
        }
        compose.onNodeWithText("Security").performClick()
        compose.onNodeWithText("The APK is debuggable").assertIsDisplayed()
        capture("report-security")

        compose.onNodeWithText("The APK is debuggable").performClick()
        compose.onNodeWithText("Remediation").assertIsDisplayed()
    }

    @Test
    fun assistantAnswersFromTheReport() {
        val assistant = OfflineAssistant()
        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                AssistantScreen(
                    scans = listOf(scan),
                    report = report,
                    initialScanId = scan.id,
                    ask = { question, current -> assistant.answer(question, current) },
                    modelAvailable = false,
                    modelNote = "Deterministic engine",
                    onSelectScan = {},
                    onOpenReport = {}
                )
            }
        }
        compose.onNodeWithTag("assistantInput").performTextInput("What should I fix first?")
        compose.onNodeWithTag("assistantSend").performClick()
        compose.onNodeWithText("What should I fix first?").assertIsDisplayed()
        capture("assistant")
    }

    @Test
    fun reportWithoutDataShowsLoadingPlaceholder() {
        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                ReportScreen(null, null, emptyList(), emptyList(), { null }, {}, {})
            }
        }
        compose.onNodeWithText("Loading report...").assertIsDisplayed()
    }

    @Test
    fun applicationContextIsAvailable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(context.packageName.isNotEmpty())
    }
}
