package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class ConnectionActivity : AppCompatActivity() {
    private lateinit var ipInputLayout: TextInputLayout
    private lateinit var portInputLayout: TextInputLayout
    private lateinit var ipEditText: TextInputEditText
    private lateinit var portEditText: TextInputEditText
    private lateinit var passcodeEditText: TextInputEditText
    private lateinit var connectButton: Button
    private lateinit var connectionStatusText: TextView
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
        val deviceName = intent.getStringExtra("DEVICE_NAME")
        val deviceSource = intent.getStringExtra("DEVICE_SOURCE")

        if (ipAddress != null) {
            ipEditText.setText(ipAddress)
            portEditText.setText(port.toString())
            connectionStatusText.visibility = View.VISIBLE
            connectionStatusText.text = buildString {
                append("Found ${deviceName ?: "QLab Workspace"}")
                if (!deviceSource.isNullOrBlank()) append(" via $deviceSource")
                append(". Confirm the OSC port and passcode.")
            }
        }
    }

    private fun initializeViews() {
        ipInputLayout = findViewById(R.id.ipInputLayout)
        portInputLayout = findViewById(R.id.portInputLayout)
        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        passcodeEditText = findViewById(R.id.passcodeEditText)
        connectButton = findViewById(R.id.connectButton)
        connectionStatusText = findViewById(R.id.connectionStatusText)
    }

    private fun loadSavedSettings() {
        settingsManager.ipAddress?.let { ipEditText.setText(it) }
        portEditText.setText(settingsManager.port.toString())
        passcodeEditText.text?.clear()
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

        ipInputLayout.error = null
        portInputLayout.error = null
        connectionStatusText.visibility = View.GONE

        if (ipAddress.isNullOrEmpty()) {
            ipInputLayout.error = "Enter the Mac IP address running QLab"
            return
        }

        val port = portString?.toIntOrNull() ?: 53000
        if (port !in 1..65535) {
            portInputLayout.error = "Use a port from 1 to 65535"
            return
        }

        connectButton.isEnabled = false
        connectButton.text = "Connecting..."

        lifecycleScope.launch {
            val qLabManager = QLabOscManager.getInstance()
            val success = qLabManager.connect(ipAddress, port, passcode)

            if (success) {
                // Save connection settings
                settingsManager.saveConnectionSettings(ipAddress, port)

                // Navigate to control activity
                val intent = Intent(this@ConnectionActivity, ControlActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                connectionStatusText.visibility = View.VISIBLE
                connectionStatusText.text = "Connection failed. Check QLab OSC access, IP, port, and passcode."
                Toast.makeText(this@ConnectionActivity, "Failed to connect to QLab", Toast.LENGTH_LONG).show()
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.connect)
            }
        }
    }
}
