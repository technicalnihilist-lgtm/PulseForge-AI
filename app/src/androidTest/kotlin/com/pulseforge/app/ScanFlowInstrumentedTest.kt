package com.pulseforge.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test. Requires a connected device or emulator (API 26+):
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *
 * The JVM suite (ScreenTest) covers the same screens without a device; this test proves the app
 * launches on Android with its real dependencies (Room, WorkManager, DataStore).
 */
@RunWith(AndroidJUnit4::class)
class ScanFlowInstrumentedTest {

    @Test
    fun appLaunchesAndShowsDashboard() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("PulseForge AI")).check(matches(isDisplayed()))
        }
    }
}
