package com.okumi.qlabcontroller

import android.content.Intent
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
        setupDebugCard()

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

    private fun setupDebugCard() {
        findViewById<MaterialCardView>(R.id.debugCard).setOnClickListener {
            if (!settingsManager.loggingEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("Logging Disabled")
                    .setMessage("Debug logging is currently disabled. Enable it to start collecting logs.")
                    .setPositiveButton("Enable") { _, _ ->
                        settingsManager.loggingEnabled = true
                        findViewById<SwitchCompat>(R.id.loggingSwitch).isChecked = true
                        openDebugLog()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                openDebugLog()
            }
        }
    }

    private fun openDebugLog() {
        val intent = Intent(this, DebugLogActivity::class.java)
        startActivity(intent)
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
