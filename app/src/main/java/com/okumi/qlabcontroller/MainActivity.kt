package com.okumi.qlabcontroller

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var qLabManager: QLabOscManager

    private lateinit var ipEditText: TextInputEditText
    private lateinit var portEditText: TextInputEditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView
    private lateinit var goButton: Button
    private lateinit var panicButton: Button

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        qLabManager = QLabOscManager()

        initializeViews()
        setupClickListeners()
    }

    private fun initializeViews() {
        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)
        goButton = findViewById(R.id.goButton)
        panicButton = findViewById(R.id.panicButton)
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }

        goButton.setOnClickListener {
            sendGoCommand()
        }

        panicButton.setOnClickListener {
            sendPanicCommand()
        }
    }

    private fun connect() {
        val ipAddress = ipEditText.text?.toString()?.trim()
        val portString = portEditText.text?.toString()?.trim()

        if (ipAddress.isNullOrEmpty()) {
            Toast.makeText(this, "Please enter QLab IP address", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portString?.toIntOrNull() ?: 53000

        lifecycleScope.launch {
            val success = qLabManager.connect(ipAddress, port)

            if (success) {
                isConnected = true
                updateConnectionStatus(true)
                Toast.makeText(this@MainActivity, "Connected to QLab", Toast.LENGTH_SHORT).show()
            } else {
                isConnected = false
                updateConnectionStatus(false)
                Toast.makeText(this@MainActivity, "Failed to connect to QLab", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun disconnect() {
        qLabManager.disconnect()
        isConnected = false
        updateConnectionStatus(false)
        Toast.makeText(this, "Disconnected from QLab", Toast.LENGTH_SHORT).show()
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            statusText.text = "Status: Connected"
            connectButton.text = getString(R.string.disconnect)
            goButton.isEnabled = true
            panicButton.isEnabled = true

            // Disable editing connection details while connected
            ipEditText.isEnabled = false
            portEditText.isEnabled = false
        } else {
            statusText.text = "Status: Disconnected"
            connectButton.text = getString(R.string.connect)
            goButton.isEnabled = false
            panicButton.isEnabled = false

            // Enable editing connection details when disconnected
            ipEditText.isEnabled = true
            portEditText.isEnabled = true
        }
    }

    private fun sendGoCommand() {
        lifecycleScope.launch {
            val success = qLabManager.sendGo()

            if (success) {
                showFeedback("GO sent")
            } else {
                Toast.makeText(this@MainActivity, "Failed to send GO command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendPanicCommand() {
        lifecycleScope.launch {
            val success = qLabManager.sendPanic()

            if (success) {
                showFeedback("PANIC sent")
            } else {
                Toast.makeText(this@MainActivity, "Failed to send PANIC command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFeedback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        qLabManager.disconnect()
    }
}
