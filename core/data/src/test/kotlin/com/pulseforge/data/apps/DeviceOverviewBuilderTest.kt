package com.pulseforge.data.apps

import com.pulseforge.model.InstalledApp
import com.pulseforge.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOverviewBuilderTest {

    private fun app(
        pkg: String,
        label: String = pkg,
        debuggable: Boolean = false,
        dangerous: Int = 0,
        exported: Int = 0,
        targetSdk: Int = 34,
        system: Boolean = false,
        installer: String? = "com.android.vending"
    ) = InstalledApp(
        packageName = pkg,
        label = label,
        targetSdk = targetSdk,
        isSystemApp = system,
        isDebuggable = debuggable,
        installer = installer,
        permissionCount = dangerous,
        dangerousPermissionCount = dangerous,
        exportedComponentCount = exported
    )

    @Test
    fun `clean device scores 100`() {
        val overview = DeviceOverviewBuilder.build(
            listOf(app("com.a.good"), app("com.b.good", dangerous = 2)),
            reclaimableBytes = 1024
        )
        assertEquals(100, overview.score)
        assertTrue(overview.breakdown.isEmpty())
        assertEquals(2, overview.scannedApps)
    }

    @Test
    fun `debuggable apps deduct and are reported as critical`() {
        val overview = DeviceOverviewBuilder.build(
            listOf(app("com.a.debug", debuggable = true), app("com.b.ok")),
            reclaimableBytes = 0
        )
        assertEquals(80, overview.score)
        val category = overview.categories.first { it.id == "debug" }
        assertEquals(Severity.CRITICAL, category.severity)
        assertEquals(1, category.count)
        assertTrue(overview.offenders.any { it.packageName == "com.a.debug" })
    }

    @Test
    fun `deductions are capped so the score never goes negative`() {
        val many = (1..10).map { app("com.debug.$it", debuggable = true, dangerous = 12, exported = 9) }
        val overview = DeviceOverviewBuilder.build(many, reclaimableBytes = 0)
        assertEquals(0, overview.score)
        // The cap is visible in the breakdown rather than hidden in the number.
        assertTrue(overview.breakdown.any { it.label.contains("Debuggable") && it.points == -40 })
        assertTrue(overview.breakdown.any { it.label.contains("permission") && it.points == -30 })
    }

    @Test
    fun `system apps are excluded from the outdated-target category`() {
        val overview = DeviceOverviewBuilder.build(
            listOf(app("com.sys", system = true, targetSdk = 21), app("com.user", targetSdk = 21)),
            reclaimableBytes = 0
        )
        val category = overview.categories.first { it.id == "outdated" }
        assertEquals(1, category.count)
    }

    @Test
    fun `cache size contributes to the score at the documented thresholds`() {
        val apps = listOf(app("com.a"))
        assertEquals(100, DeviceOverviewBuilder.build(apps, 50L * 1024 * 1024).score)
        assertEquals(95, DeviceOverviewBuilder.build(apps, 200L * 1024 * 1024).score)
        assertEquals(90, DeviceOverviewBuilder.build(apps, 900L * 1024 * 1024).score)
    }

    @Test
    fun `overview states its own limits`() {
        val overview = DeviceOverviewBuilder.build(listOf(app("com.a")), 0)
        assertTrue(overview.limitations.any { it.contains("cannot free other apps") })
        assertTrue(overview.categories.any { it.id == "storage" })
    }
}
