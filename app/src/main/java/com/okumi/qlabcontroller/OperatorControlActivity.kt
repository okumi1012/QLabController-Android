package com.okumi.qlabcontroller

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OperatorControlActivity : AppCompatActivity() {
    private lateinit var binder: ShowStateBinder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operator_control)

        val state = MockShowStateRepository(this).loadState()
        binder = ShowStateBinder(this)
        binder.bind(state)

        val goButton = findViewById<Button>(R.id.goButton)
        val armButton = findViewById<View>(R.id.armButton) as? Button
        val panicButton = findViewById<View>(R.id.panicButton) as? Button
        val stopButton = findViewById<View>(R.id.stopButton) as? Button
        val operationStateText = findViewById<View>(R.id.operationStateText) as? TextView
        val actionText = findViewById<TextView>(R.id.operatorActionText)
        val requiresArm = listOf(state.qlab.nextCue.risk, state.ma2.nextCue.risk).any { risk ->
            risk.equals("high", ignoreCase = true) || risk.equals("danger", ignoreCase = true)
        }
        var isArmed = !requiresArm

        fun updateOperationState() {
            goButton.isEnabled = isArmed
            goButton.alpha = if (isArmed) 1f else 0.45f
            operationStateText?.text = if (requiresArm && !isArmed) {
                "High-risk next cue. ARM is required before mock GO is allowed."
            } else {
                "Mock GO is armed. No OSC/TCP command will be sent."
            }
        }

        armButton?.visibility = if (requiresArm) View.VISIBLE else View.GONE
        armButton?.setOnClickListener {
            isArmed = true
            updateOperationState()
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            actionText.text = "Mock ARM enabled at $time for high-risk next cue."
            binder.prependEvent(time, "operator", "Mock ARM enabled for ${state.qlab.nextCue.id} / MA2 ${state.ma2.nextCue.cue}")
        }

        goButton.setOnClickListener {
            if (!isArmed) {
                actionText.text = "ARM required before mock GO."
                return@setOnClickListener
            }
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            actionText.text = "Mock operator action logged at $time. No OSC/TCP command was sent."
            binder.prependEvent(time, "operator", "Mock GO pressed for ${state.qlab.nextCue.id} / MA2 ${state.ma2.nextCue.cue}")
            if (requiresArm) {
                isArmed = false
                updateOperationState()
            }
        }

        panicButton?.setOnClickListener {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            actionText.text = "Mock PANIC logged at $time. No live command was sent."
            binder.prependEvent(time, "operator", "Mock PANIC pressed")
        }

        stopButton?.setOnClickListener {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            actionText.text = "Mock STOP logged at $time. No live command was sent."
            binder.prependEvent(time, "operator", "Mock STOP pressed")
        }

        updateOperationState()
    }
}
