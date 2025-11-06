package com.okumi.qlabcontroller

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DebugLogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var logCountText: TextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_log)

        logText = findViewById(R.id.logText)
        logCountText = findViewById(R.id.logCountText)
        logScrollView = findViewById(R.id.logScrollView)

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            LogManager.clear()
            updateLogs()
        }

        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            updateLogs()
        }

        updateLogs()
    }

    private fun updateLogs() {
        val logs = LogManager.getAllLogs()
        logCountText.text = "${logs.size} logs"

        if (logs.isEmpty()) {
            logText.text = "No logs yet"
            return
        }

        val builder = SpannableStringBuilder()
        logs.forEach { log ->
            val start = builder.length
            val text = "${log.timestamp} [${log.level}] ${log.tag}: ${log.message}\n"
            builder.append(text)

            // Color code based on log level
            val color = when (log.level) {
                "E" -> Color.RED
                "W" -> Color.rgb(255, 140, 0) // Orange
                "I" -> Color.BLUE
                "D" -> Color.DKGRAY
                else -> Color.BLACK
            }

            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        logText.text = builder

        // Auto-scroll to bottom
        logScrollView.post {
            logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        updateLogs()
    }
}
