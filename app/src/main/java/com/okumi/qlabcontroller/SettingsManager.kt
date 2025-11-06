package com.okumi.qlabcontroller

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "qlab_settings"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PORT = "port"
        private const val KEY_PASSCODE = "passcode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val DEFAULT_PORT = 53000

        // Theme modes
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    var ipAddress: String?
        get() = prefs.getString(KEY_IP_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_IP_ADDRESS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var passcode: String?
        get() = prefs.getString(KEY_PASSCODE, null)
        set(value) = prefs.edit().putString(KEY_PASSCODE, value).apply()

    fun saveConnectionSettings(ip: String, port: Int, passcode: String?) {
        prefs.edit().apply {
            putString(KEY_IP_ADDRESS, ip)
            putInt(KEY_PORT, port)
            putString(KEY_PASSCODE, passcode)
            apply()
        }
    }

    fun clearSettings() {
        prefs.edit().clear().apply()
    }

    // Theme settings
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
            applyTheme(value)
        }

    fun applyTheme(mode: Int = themeMode) {
        when (mode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun getThemeModeName(mode: Int): String {
        return when (mode) {
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            else -> "System Default"
        }
    }

    // Logging settings
    var loggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOGGING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOGGING_ENABLED, value).apply()
            LogManager.setEnabled(value)
        }
}
