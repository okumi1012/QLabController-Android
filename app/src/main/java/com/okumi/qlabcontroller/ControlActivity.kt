package com.okumi.qlabcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {
    private lateinit var qLabManager: QLabOscManager

    private lateinit var titleText: TextView
    private lateinit var infoButton: ImageButton
    private lateinit var qlabStatusChip: TextView
    private lateinit var syncStatusChip: TextView
    private lateinit var riskStatusChip: TextView
    private lateinit var followStatusChip: TextView
    private lateinit var netStatusChip: TextView
    private lateinit var groupStatusChip: TextView
    private lateinit var externalStatusChip: TextView
    private lateinit var localStatusChip: TextView
    private lateinit var cueInfoCard: MaterialCardView
    private lateinit var notesCard: MaterialCardView
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
    private lateinit var disconnectButton: Button
    private var cueRefreshJob: Job? = null

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
        qlabStatusChip = findViewById(R.id.qlabStatusChip)
        syncStatusChip = findViewById(R.id.syncStatusChip)
        riskStatusChip = findViewById(R.id.riskStatusChip)
        followStatusChip = findViewById(R.id.followStatusChip)
        netStatusChip = findViewById(R.id.netStatusChip)
        groupStatusChip = findViewById(R.id.groupStatusChip)
        externalStatusChip = findViewById(R.id.externalStatusChip)
        localStatusChip = findViewById(R.id.localStatusChip)
        cueInfoCard = findViewById(R.id.cueInfoCard)
        notesCard = findViewById(R.id.notesCard)
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
        disconnectButton = findViewById(R.id.disconnectButton)

        // Set title to workspace name
        titleText.text = qLabManager.getWorkspaceName()
        ShowHudUi.setStatusChip(qlabStatusChip, "QLAB", "normal")
        ShowHudUi.setStatusChip(syncStatusChip, "SYNC", "normal")
        ShowHudUi.setStatusChip(riskStatusChip, "RISK", "inactive")
        ShowHudUi.setStatusChip(followStatusChip, "FOL", "inactive")
        ShowHudUi.setStatusChip(netStatusChip, "NET", "normal")
        ShowHudUi.setStatusChip(groupStatusChip, "GROUP", "inactive")
        ShowHudUi.setStatusChip(externalStatusChip, "EXT", "inactive")
        ShowHudUi.setStatusChip(localStatusChip, "LOCAL", "inactive")
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

        disconnectButton.setOnClickListener {
            disconnect()
        }
    }

    private fun startCueInfoUpdates() {
        lifecycleScope.launch {
            var tick = 0
            while (qLabManager.isConnected()) {
                if (tick % 4 == 0) {
                    updateCueInfo()
                } else {
                    renderCueInfo(qLabManager.getDisplayedCueInfo())
                }
                tick++
                delay(250)
            }
        }
    }

    private suspend fun updateCueInfo() {
        val cueInfo = qLabManager.refreshCurrentCueInfo()
        LogManager.d("ControlActivity", "Updating UI - Notes: '${cueInfo.currentNotes}'")
        runOnUiThread {
            renderCueInfo(cueInfo)
        }
    }

    private fun renderCueInfo(cueInfo: CueInfo) {
        previous2CueText.text = cueInfo.previous2Cue
        previousCueText.text = cueInfo.previousCue
        currentCueText.text = cueInfo.currentCue
        nextCueText.text = cueInfo.nextCue
        next2CueText.text = cueInfo.next2Cue

        notesText.text = compactMemo(cueInfo)
        renderStatusChips(cueInfo)
        renderVisualState(cueInfo)
    }

    private fun compactMemo(cueInfo: CueInfo): String {
        val current = cueInfo.currentNotes.trim()
        val next = cueInfo.nextNotes.trim()
        return when {
            current.isNotEmpty() && next.isNotEmpty() -> "NOW $current\nNEXT $next"
            next.isNotEmpty() -> "NEXT $next"
            current.isNotEmpty() -> current
            else -> "MEMO ---"
        }
    }

    private fun renderStatusChips(cueInfo: CueInfo) {
        ShowHudUi.setStatusChip(qlabStatusChip, "QLAB", if (qLabManager.isConnected()) "normal" else "disconnected")
        ShowHudUi.setStatusChip(syncStatusChip, "SYNC", "normal")
        ShowHudUi.setStatusChip(riskStatusChip, "RISK", cueInfo.riskState)
        ShowHudUi.setStatusChip(followStatusChip, "FOL", if (cueInfo.hasFollowTiming) "warning" else "inactive")
        ShowHudUi.setStatusChip(groupStatusChip, "GROUP", if (cueInfo.isGroupCue) "correcting" else "inactive")
        ShowHudUi.setStatusChip(externalStatusChip, "EXT", if (cueInfo.isExternalCueChange) "warning" else "inactive")
        ShowHudUi.setStatusChip(localStatusChip, "LOCAL", if (cueInfo.isPredicted || cueInfo.isLocalMovement) "warning" else "inactive")
        when {
            cueInfo.isNetworkCorrected -> ShowHudUi.setStatusChip(netStatusChip, "NET", "correcting")
            cueInfo.isPredicted -> ShowHudUi.setStatusChip(netStatusChip, "NET", "warning")
            else -> ShowHudUi.setStatusChip(netStatusChip, "NET", "normal")
        }
    }

    private fun renderVisualState(cueInfo: CueInfo) {
        val strokeColor = when (cueInfo.riskState) {
            "danger" -> R.color.show_danger
            "warning" -> R.color.show_prepare
            else -> R.color.show_border
        }
        cueInfoCard.strokeColor = getColor(strokeColor)
        cueInfoCard.strokeWidth = resources.displayMetrics.density.times(
            if (cueInfo.riskState == "danger") 4f else if (cueInfo.riskState == "warning") 3f else 1f
        ).toInt()
        notesCard.strokeColor = getColor(if (cueInfo.hasFollowTiming) R.color.show_prepare else R.color.show_border)
        notesCard.strokeWidth = resources.displayMetrics.density.times(if (cueInfo.hasFollowTiming) 2f else 1f).toInt()

        if (cueInfo.riskState == "danger" || cueInfo.hasFollowTiming || cueInfo.isNetworkCorrected || cueInfo.isExternalCueChange) {
            pulse(cueInfoCard)
        }
        if (cueInfo.isNetworkCorrected) pulse(netStatusChip)
        if (cueInfo.isExternalCueChange) pulse(externalStatusChip)
        if (cueInfo.isPredicted || cueInfo.isLocalMovement) pulse(localStatusChip)
    }

    private fun pulse(view: android.view.View) {
        view.animate().cancel()
        view.alpha = 1f
        view.animate()
            .alpha(0.55f)
            .setDuration(120)
            .withEndAction {
                view.animate().alpha(1f).setDuration(180).start()
            }
            .start()
    }

    private fun sendPreviousCommand() {
        lifecycleScope.launch {
            setNavigationButtonsEnabled(false)
            renderCueInfo(qLabManager.predictPrevious())
            try {
                qLabManager.sendPrevious()
                scheduleFastCueRefresh()
            } catch (e: Exception) {
                setNavigationButtonsEnabled(true)
            }
        }
    }

    private fun sendGoCommand() {
        lifecycleScope.launch {
            setNavigationButtonsEnabled(false)
            renderCueInfo(qLabManager.predictGo())
            try {
                qLabManager.sendGo()
                scheduleFastCueRefresh()
            } catch (e: Exception) {
                setNavigationButtonsEnabled(true)
            }
        }
    }

    private fun sendNextCommand() {
        lifecycleScope.launch {
            setNavigationButtonsEnabled(false)
            renderCueInfo(qLabManager.predictNext())
            try {
                qLabManager.sendNext()
                scheduleFastCueRefresh()
            } catch (e: Exception) {
                setNavigationButtonsEnabled(true)
            }
        }
    }

    private fun scheduleFastCueRefresh() {
        cueRefreshJob?.cancel()
        cueRefreshJob = lifecycleScope.launch {
            try {
                delay(80)
                updateCueInfo()
            } finally {
                setNavigationButtonsEnabled(true)
            }
        }
    }

    private fun setNavigationButtonsEnabled(enabled: Boolean) {
        previousButton.isEnabled = enabled
        goButton.isEnabled = enabled
        nextButton.isEnabled = enabled
    }

    private fun sendPanicCommand() {
        lifecycleScope.launch {
            qLabManager.sendPanic()
        }
    }

    private fun showConnectionInfo() {
        val connectionInfo = qLabManager.getConnectionInfo()
        if (connectionInfo == null) {
            Toast.makeText(this, "Connection info not available", Toast.LENGTH_SHORT).show()
            return
        }

        val messageText = TextView(this).apply {
            text = buildString {
                append("Workspace: ${connectionInfo.workspaceName}\n\n")
                append("IP Address: ${connectionInfo.ipAddress}\n")
                append("Port: ${connectionInfo.port}\n")
                append("Passcode: ${if (connectionInfo.hasPasscode) "Provided for this session" else "Not provided"}")
            }
            setPadding(60, 40, 60, 40)
            textSize = 16f
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
        cueRefreshJob?.cancel()
        if (!isChangingConfigurations) {
            qLabManager.disconnect()
        }
    }
}
