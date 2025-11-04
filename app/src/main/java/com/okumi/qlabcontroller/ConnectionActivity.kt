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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)

        initializeViews()
        setupClickListeners()
    }

    private fun initializeViews() {
        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        passcodeEditText = findViewById(R.id.passcodeEditText)
        connectButton = findViewById(R.id.connectButton)
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
                // Navigate to control activity
                val intent = Intent(this@ConnectionActivity, ControlActivity::class.java)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this@ConnectionActivity, "Failed to connect to QLab", Toast.LENGTH_LONG).show()
                connectButton.isEnabled = true
                connectButton.text = getString(R.string.connect)
            }
        }
    }
}
