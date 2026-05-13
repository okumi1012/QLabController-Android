package com.okumi.qlabcontroller

import android.os.Bundle
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

        findViewById<Button>(R.id.goButton).setOnClickListener {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            findViewById<TextView>(R.id.operatorActionText).text =
                "Mock operator action logged at $time. No OSC/TCP command was sent."
            binder.prependEvent(time, "operator", "Mock GO pressed for ${state.qlab.nextCue.id} / MA2 ${state.ma2.nextCue.cue}")
        }
    }
}
