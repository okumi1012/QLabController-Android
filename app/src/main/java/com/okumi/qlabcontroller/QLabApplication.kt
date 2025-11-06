package com.okumi.qlabcontroller

import android.app.Application

class QLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize settings manager and apply theme
        val settingsManager = SettingsManager(this)
        settingsManager.applyTheme()

        // Initialize logging based on settings
        LogManager.setEnabled(settingsManager.loggingEnabled)
    }
}
