package com.pulseforge.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pulseforge_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val deepScan: Boolean = true,
    val keepApkCopy: Boolean = true,
    val userOwnsSource: Boolean = false
)

/** Local preferences only. Nothing here is ever sent anywhere. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DEEP_SCAN = booleanPreferencesKey("deep_scan")
        val KEEP_COPY = booleanPreferencesKey("keep_apk_copy")
        val OWNS_SOURCE = booleanPreferencesKey("user_owns_source")
    }

    /**
     * DataStore emits the whole preference map after any write, so re-selecting the current theme or
     * toggling an unrelated key pushed an equal [AppSettings] downstream and re-applied the theme.
     * [distinctUntilChanged] drops those duplicates.
     */
    val settings: Flow<AppSettings> = context.dataStore.data
        .map { prefs -> prefs.toSettings() }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setDeepScan(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEEP_SCAN] = enabled }
    }

    suspend fun setKeepApkCopy(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_COPY] = enabled }
    }

    suspend fun setUserOwnsSource(owns: Boolean) {
        context.dataStore.edit { it[Keys.OWNS_SOURCE] = owns }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        // `ThemeMode.valueOf` built and threw an IllegalArgumentException for any stored value that
        // no longer names a constant, on every emission; a lookup returns the fallback directly.
        themeMode = ThemeMode.entries.firstOrNull { it.name == this[Keys.THEME] } ?: ThemeMode.SYSTEM,
        deepScan = this[Keys.DEEP_SCAN] ?: true,
        keepApkCopy = this[Keys.KEEP_COPY] ?: true,
        userOwnsSource = this[Keys.OWNS_SOURCE] ?: false
    )
}
