package com.okumi.qlabcontroller

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "qlab_settings"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PORT = "port"
        private const val KEY_PASSCODE = "passcode"
        private const val DEFAULT_PORT = 53000
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
}
