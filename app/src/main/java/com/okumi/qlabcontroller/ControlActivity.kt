package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {
    private lateinit var qLabManager: QLabOscManager

    private lateinit var previousCueText: TextView
    private lateinit var currentCueText: TextView
    private lateinit var nextCueText: TextView
    private lateinit var statusText: TextView

    private lateinit var previousButton: Button
    private lateinit var goButton: Button
    private lateinit var nextButton: Button
    private lateinit var panicButton: Button
    private lateinit var disconnectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control)

        qLabManager = QLabOscManager.getInstance()

        if (!qLabManager.isConnected()) {
            // If not connected, go back to connection screen
            navigateToConnection()
            return
        }

        initializeViews()
        setupClickListeners()
        startCueInfoUpdates()
    }

    private fun initializeViews() {
        previousCueText = findViewById(R.id.previousCueText)
        currentCueText = findViewById(R.id.currentCueText)
        nextCueText = findViewById(R.id.nextCueText)
        statusText = findViewById(R.id.statusText)

        previousButton = findViewById(R.id.previousButton)
        goButton = findViewById(R.id.goButton)
        nextButton = findViewById(R.id.nextButton)
        panicButton = findViewById(R.id.panicButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        statusText.text = "Connected: ${qLabManager.getConnectionInfo()}"
    }

    private fun setupClickListeners() {
        previousButton.setOnClickListener {
            sendPreviousCommand()
        }

        goButton.setOnClickListener {
            sendGoCommand()
        }

        nextButton.setOnClickListener {
            sendNextCommand()
        }

        panicButton.setOnClickListener {
            sendPanicCommand()
        }

        disconnectButton.setOnClickListener {
            disconnect()
        }
    }

    private fun startCueInfoUpdates() {
        lifecycleScope.launch {
            while (qLabManager.isConnected()) {
                updateCueInfo()
                delay(1000) // Update every second
            }
        }
    }

    private suspend fun updateCueInfo() {
        val cueInfo = qLabManager.getCurrentCueInfo()
        runOnUiThread {
            previousCueText.text = "Previous: ${cueInfo.previousCue}"
            currentCueText.text = "Current: ${cueInfo.currentCue}"
            nextCueText.text = "Next: ${cueInfo.nextCue}"
        }
    }

    private fun sendPreviousCommand() {
        lifecycleScope.launch {
            val success = qLabManager.sendPrevious()
            if (success) {
                showFeedback("Previous cue")
                updateCueInfo()
            } else {
                Toast.makeText(this@ControlActivity, "Failed to send Previous command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendGoCommand() {
        lifecycleScope.launch {
            val success = qLabManager.sendGo()
            if (success) {
                showFeedback("GO sent")
                updateCueInfo()
            } else {
                Toast.makeText(this@ControlActivity, "Failed to send GO command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendNextCommand() {
        lifecycleScope.launch {
            val success = qLabManager.sendNext()
            if (success) {
                showFeedback("Next cue")
                updateCueInfo()
            } else {
                Toast.makeText(this@ControlActivity, "Failed to send Next command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendPanicCommand() {
        lifecycleScope.launch {
            val success = qLabManager.sendPanic()
            if (success) {
                showFeedback("PANIC sent")
            } else {
                Toast.makeText(this@ControlActivity, "Failed to send PANIC command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun disconnect() {
        qLabManager.disconnect()
        navigateToConnection()
    }

    private fun navigateToConnection() {
        val intent = Intent(this, ConnectionActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun showFeedback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            qLabManager.disconnect()
        }
    }
}
