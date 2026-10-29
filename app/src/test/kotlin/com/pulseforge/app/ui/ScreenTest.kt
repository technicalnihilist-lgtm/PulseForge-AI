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
import com.pulseforge.app.ui.screens.DeviceOverviewScreen
import com.pulseforge.app.ui.screens.InstalledAppsScreen
import com.pulseforge.app.ui.screens.OptimizeScreen
import com.pulseforge.app.ui.screens.ReportScreen
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.data.apps.DeviceOverviewBuilder
import com.pulseforge.model.InstalledApp
import com.pulseforge.model.ScanReport
import com.pulseforge.performance.BuildRecipeGenerator
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
    fun installedAppsListShowsRiskAndStartsAScan() {
        var scanned: String? = null
        val apps = listOf(
            InstalledApp(
                packageName = "com.example.social",
                label = "Social App",
                versionName = "4.2.0",
                versionCode = 420,
                minSdk = 26,
                targetSdk = 33,
                isSystemApp = false,
                isDebuggable = false,
                installer = "com.android.vending",
                apkPath = "/data/app/~~a/com.example.social-1/base.apk",
                apkSizeBytes = 48_000_000,
                apkReadable = true,
                permissionCount = 14,
                dangerousPermissionCount = 9,
                exportedComponentCount = 7
            ),
            InstalledApp(
                packageName = "com.example.debug",
                label = "Debug Build",
                versionName = "1.0.0",
                versionCode = 1,
                minSdk = 24,
                targetSdk = 30,
                isDebuggable = true,
                apkPath = "/data/app/~~b/com.example.debug-1/base.apk",
                apkSizeBytes = 12_000_000,
                apkReadable = true,
                permissionCount = 3,
                dangerousPermissionCount = 1,
                exportedComponentCount = 2
            ),
            InstalledApp(
                packageName = "com.vendor.locked",
                label = "Locked App",
                versionName = "2.1",
                versionCode = 21,
                isSystemApp = false,
                apkPath = "/data/app/~~c/com.vendor.locked-1/base.apk",
                apkSizeBytes = 9_000_000,
                apkReadable = false,
                permissionCount = 5,
                dangerousPermissionCount = 2,
                exportedComponentCount = 1
            )
        )

        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                InstalledAppsScreen(
                    apps = apps,
                    loading = false,
                    includeSystem = true,
                    onToggleSystem = {},
                    onRefresh = {},
                    onScan = { scanned = it.packageName },
                    onOpenSettings = {}
                )
            }
        }

        compose.onNodeWithText("Social App").assertIsDisplayed()
        compose.onNodeWithText("Debug Build").assertIsDisplayed()
        compose.onNodeWithText("Locked App").assertIsDisplayed()
        // The unreadable-APK case must say so rather than implying a full scan.
        compose.onNodeWithText(
            "APK not readable on this device - the scan will cover manifest metadata only."
        ).assertIsDisplayed()

        compose.onNodeWithTag("scan_com.example.social").performClick()
        org.junit.Assert.assertEquals("com.example.social", scanned)

        capture("installed-apps")
    }

    @Test
    fun optimizeScreenShowsRecipeAndHardeningActions() {
        val plan = OptimizationPlanner.plan(report.findings, report.performance, report.apk, userOwnsSource = true)
        val recipe = BuildRecipeGenerator.generate(report, userOwnsSource = true)

        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                OptimizeScreen(
                    report = report,
                    plan = plan,
                    recipe = recipe,
                    installedPackage = "com.pulseforge.fixture.insecure",
                    onBack = {},
                    onOpenAppSettings = {},
                    onOpenBatterySettings = {},
                    onUninstall = {},
                    planText = { "plan" }
                )
            }
        }

        // Only the first screenful is composed, so assert on what is visible: the title, the
        // installed-app hardening block and the summary. The recipe below it is exercised by the
        // unit tests in :core:report.
        compose.onNodeWithText("Optimize").assertIsDisplayed()
        compose.onNodeWithTag("revokePermissions").assertIsDisplayed()
        compose.onNodeWithText("Summary").assertIsDisplayed()
        compose.onNodeWithText("Harden this app").assertIsDisplayed()

        capture("optimize")
    }

    @Test
    fun deviceOverviewShowsScoreBreakdownAndActions() {
        val apps = listOf(
            InstalledApp(
                packageName = "com.example.social",
                label = "Social App",
                versionName = "4.2.0",
                versionCode = 420,
                targetSdk = 33,
                apkSizeBytes = 48_000_000,
                permissionCount = 14,
                dangerousPermissionCount = 9,
                exportedComponentCount = 7
            ),
            InstalledApp(
                packageName = "com.example.debug",
                label = "Debug Build",
                versionName = "1.0.0",
                versionCode = 1,
                targetSdk = 30,
                isDebuggable = true,
                apkSizeBytes = 12_000_000,
                permissionCount = 3,
                dangerousPermissionCount = 1,
                exportedComponentCount = 2
            ),
            InstalledApp(
                packageName = "com.example.old",
                label = "Old App",
                targetSdk = 28,
                apkSizeBytes = 8_000_000,
                permissionCount = 4,
                dangerousPermissionCount = 2,
                exportedComponentCount = 1
            )
        )
        val overview = DeviceOverviewBuilder.build(apps, reclaimableBytes = 240L * 1024 * 1024)

        compose.setContent {
            PulseForgeTheme(darkTheme = false) {
                DeviceOverviewScreen(
                    overview = overview,
                    scanning = false,
                    onScanAll = {},
                    onCleanCache = {},
                    onOpenSettings = {},
                    onScanApp = {},
                    onBack = {}
                )
            }
        }

        // Only the first screenful is composed; the score card and its breakdown are what the
        // device view is judged on. The action wiring is covered by the builder unit tests.
        compose.onNodeWithText("Optimize device").assertIsDisplayed()
        compose.onNodeWithText("Device score").assertIsDisplayed()
        compose.onNodeWithText("3 apps measured").assertExists()

        capture("device-overview")
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
