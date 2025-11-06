package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {
    private lateinit var qLabManager: QLabOscManager

    private lateinit var titleText: TextView
    private lateinit var infoButton: ImageButton
    private lateinit var previous2CueText: TextView
    private lateinit var previousCueText: TextView
    private lateinit var currentCueText: TextView
    private lateinit var nextCueText: TextView
    private lateinit var next2CueText: TextView
    private lateinit var notesText: TextView

    private lateinit var previousButton: Button
    private lateinit var goButton: Button
    private lateinit var nextButton: Button
    private lateinit var panicButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
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
        titleText = findViewById(R.id.titleText)
        infoButton = findViewById(R.id.infoButton)
        previous2CueText = findViewById(R.id.previous2CueText)
        previousCueText = findViewById(R.id.previousCueText)
        currentCueText = findViewById(R.id.currentCueText)
        nextCueText = findViewById(R.id.nextCueText)
        next2CueText = findViewById(R.id.next2CueText)
        notesText = findViewById(R.id.notesText)

        previousButton = findViewById(R.id.previousButton)
        goButton = findViewById(R.id.goButton)
        nextButton = findViewById(R.id.nextButton)
        panicButton = findViewById(R.id.panicButton)
        pauseButton = findViewById(R.id.pauseButton)
        resumeButton = findViewById(R.id.resumeButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        // Set title to workspace name
        titleText.text = qLabManager.getWorkspaceName()
    }

    private fun setupClickListeners() {
        infoButton.setOnClickListener {
            showConnectionInfo()
        }

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

        pauseButton.setOnClickListener {
            sendPauseCommand()
        }

        resumeButton.setOnClickListener {
            sendResumeCommand()
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
        LogManager.d("ControlActivity", "Updating UI - Notes: '${cueInfo.currentNotes}'")
        runOnUiThread {
            previous2CueText.text = cueInfo.previous2Cue
            previousCueText.text = cueInfo.previousCue
            currentCueText.text = cueInfo.currentCue
            nextCueText.text = cueInfo.nextCue
            next2CueText.text = cueInfo.next2Cue

            // Always show notes card, with default text if empty
            notesText.text = if (cueInfo.currentNotes.isNotEmpty()) {
                cueInfo.currentNotes
            } else {
                "No notes for this cue"
            }
        }
    }

    private fun sendPreviousCommand() {
        lifecycleScope.launch {
            qLabManager.sendPrevious()
            updateCueInfo()
        }
    }

    private fun sendGoCommand() {
        lifecycleScope.launch {
            qLabManager.sendGo()
            updateCueInfo()
        }
    }

    private fun sendNextCommand() {
        lifecycleScope.launch {
            qLabManager.sendNext()
            updateCueInfo()
        }
    }

    private fun sendPanicCommand() {
        lifecycleScope.launch {
            qLabManager.sendPanic()
        }
    }

    private fun sendPauseCommand() {
        lifecycleScope.launch {
            qLabManager.sendPause()
        }
    }

    private fun sendResumeCommand() {
        lifecycleScope.launch {
            qLabManager.sendResume()
        }
    }

    private fun showConnectionInfo() {
        val connectionInfo = qLabManager.getConnectionInfo()
        if (connectionInfo == null) {
            Toast.makeText(this, "Connection info not available", Toast.LENGTH_SHORT).show()
            return
        }

        // Create a custom view for the dialog
        val messageText = TextView(this).apply {
            text = buildString {
                append("Workspace: ${connectionInfo.workspaceName}\n\n")
                append("IP Address: ${connectionInfo.ipAddress}\n")
                append("Port: ${connectionInfo.port}\n")
                append("Passcode: ••••••••\n\n")
                append("Tap passcode to reveal")
            }
            setPadding(60, 40, 60, 40)
            textSize = 16f
        }

        var passcodeVisible = false
        messageText.setOnClickListener {
            passcodeVisible = !passcodeVisible
            messageText.text = buildString {
                append("Workspace: ${connectionInfo.workspaceName}\n\n")
                append("IP Address: ${connectionInfo.ipAddress}\n")
                append("Port: ${connectionInfo.port}\n")
                append("Passcode: ${if (passcodeVisible) connectionInfo.passcode else "••••••••"}\n\n")
                append("Tap passcode to ${if (passcodeVisible) "hide" else "reveal"}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Connection Information")
            .setView(messageText)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun disconnect() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect")
            .setMessage("Are you sure you want to disconnect from QLab?")
            .setPositiveButton("Disconnect") { _, _ ->
                qLabManager.disconnect()
                navigateToConnection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToConnection() {
        val intent = Intent(this, ConnectionActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            qLabManager.disconnect()
        }
    }
}
