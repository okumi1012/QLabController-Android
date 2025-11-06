package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ConnectionActivity : AppCompatActivity() {
    private lateinit var ipEditText: TextInputEditText
    private lateinit var portEditText: TextInputEditText
    private lateinit var passcodeEditText: TextInputEditText
    private lateinit var connectButton: Button
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)

        settingsManager = SettingsManager(this)

        initializeViews()
        loadSavedSettings()
        handleIntentData()
        setupClickListeners()
    }

    private fun handleIntentData() {
        // Check if we received connection info from network scan
        val ipAddress = intent.getStringExtra("IP_ADDRESS")
        val port = intent.getIntExtra("PORT", 53000)
        val autoConnect = intent.getBooleanExtra("AUTO_CONNECT", false)

        if (ipAddress != null) {
            ipEditText.setText(ipAddress)
            portEditText.setText(port.toString())

            if (autoConnect) {
                // Automatically connect
                connect()
            }
        }
    }

    private fun initializeViews() {
        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        passcodeEditText = findViewById(R.id.passcodeEditText)
        connectButton = findViewById(R.id.connectButton)
    }

    private fun loadSavedSettings() {
        settingsManager.ipAddress?.let { ipEditText.setText(it) }
        portEditText.setText(settingsManager.port.toString())
        settingsManager.passcode?.let { passcodeEditText.setText(it) }
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            connect()
        }
    }

    private fun connect() {
        val ipAddress = ipEditText.text?.toString()?.trim()
        val portString = portEditText.text?.toString()?.trim()
        val passcode = passcodeEditText.text?.toString()?.trim()

        if (ipAddress.isNullOrEmpty()) {
            Toast.makeText(this, "Please enter QLab IP address", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portString?.toIntOrNull() ?: 53000

        connectButton.isEnabled = false
        connectButton.text = "Connecting..."

        lifecycleScope.launch {
            val qLabManager = QLabOscManager.getInstance()
            val success = qLabManager.connect(ipAddress, port, passcode)

            if (success) {
                // Save connection settings
                settingsManager.saveConnectionSettings(ipAddress, port, passcode)

                // Navigate to control activity
                val intent = Intent(this@ConnectionActivity, ControlActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this@ConnectionActivity, "Failed to connect to QLab. Check passcode or connection.", Toast.LENGTH_LONG).show()
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.connect)
            }
        }
    }
}
