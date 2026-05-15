package com.okumi.qlabcontroller

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.card.MaterialCardView

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsManager = SettingsManager(this)

        setupThemeCard()
        setupLoggingCard()

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }
    }

    private fun setupThemeCard() {
        val themeCard = findViewById<MaterialCardView>(R.id.themeCard)
        val themeDescription = findViewById<TextView>(R.id.themeDescription)

        updateThemeDescription(themeDescription)

        themeCard.setOnClickListener {
            showThemeDialog(themeDescription)
        }
    }

    private fun setupLoggingCard() {
        val loggingSwitch = findViewById<SwitchCompat>(R.id.loggingSwitch)
        val debugLogStatus = findViewById<TextView>(R.id.debugLogStatus)

        // Set initial state
        loggingSwitch.isChecked = settingsManager.loggingEnabled

        loggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.loggingEnabled = isChecked
            updateDebugLogStatus(debugLogStatus)
        }

        updateDebugLogStatus(debugLogStatus)
    }

    private fun showThemeDialog(themeDescription: TextView) {
        val themes = arrayOf("System Default", "Light", "Dark")
        val currentTheme = settingsManager.themeMode

        AlertDialog.Builder(this)
            .setTitle("Select Theme")
            .setSingleChoiceItems(themes, currentTheme) { dialog, which ->
                settingsManager.themeMode = which
                updateThemeDescription(themeDescription)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateThemeDescription(textView: TextView) {
        textView.text = "Current: ${settingsManager.getThemeModeName(settingsManager.themeMode)}"
    }

    private fun updateDebugLogStatus(textView: TextView) {
        val logCount = LogManager.getAllLogs().size
        textView.text = if (settingsManager.loggingEnabled) {
            "Logging enabled - $logCount logs collected"
        } else {
            "Logging disabled - Enable to collect logs"
        }
    }
}
