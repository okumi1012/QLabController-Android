package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {
    private lateinit var qLabManager: QLabOscManager

    private lateinit var previousCueText: TextView
    private lateinit var currentCueText: TextView
    private lateinit var nextCueText: TextView
    private lateinit var notesText: TextView
    private lateinit var notesCard: com.google.android.material.card.MaterialCardView
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
        notesText = findViewById(R.id.notesText)
        notesCard = findViewById(R.id.notesCard)
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
        LogManager.d("ControlActivity", "Updating UI - Notes: '${cueInfo.currentNotes}'")
        runOnUiThread {
            previousCueText.text = "Previous: ${cueInfo.previousCue}"
            currentCueText.text = "Current: ${cueInfo.currentCue}"
            nextCueText.text = "Next: ${cueInfo.nextCue}"

            // Show or hide notes card
            if (cueInfo.currentNotes.isNotEmpty()) {
                LogManager.d("ControlActivity", "Showing notes card with text: ${cueInfo.currentNotes}")
                notesText.text = cueInfo.currentNotes
                notesCard.visibility = android.view.View.VISIBLE
            } else {
                LogManager.d("ControlActivity", "Hiding notes card (empty notes)")
                notesCard.visibility = android.view.View.GONE
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
