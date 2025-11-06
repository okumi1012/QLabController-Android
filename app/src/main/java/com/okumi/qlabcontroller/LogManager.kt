package com.okumi.qlabcontroller

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private val logs = mutableListOf<LogEntry>()
    private const val MAX_LOGS = 500
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var enabled = false

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val tag: String,
        val message: String
    )

    fun setEnabled(enable: Boolean) {
        enabled = enable
        if (!enable) {
            clear()
        }
    }

    fun isEnabled(): Boolean = enabled

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        addLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        addLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        addLog("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            android.util.Log.e(tag, message, throwable)
            addLog("E", tag, "$message: ${throwable.message}\n${throwable.stackTraceToString()}")
        } else {
            android.util.Log.e(tag, message)
            addLog("E", tag, message)
        }
    }

    private fun addLog(level: String, tag: String, message: String) {
        if (!enabled) return

        synchronized(logs) {
            logs.add(LogEntry(
                timestamp = dateFormat.format(Date()),
                level = level,
                tag = tag,
                message = message
            ))

            // Keep only the last MAX_LOGS entries
            if (logs.size > MAX_LOGS) {
                logs.removeAt(0)
            }
        }
    }

    fun getAllLogs(): List<LogEntry> {
        synchronized(logs) {
            return logs.toList()
        }
    }

    fun getLogsAsString(): String {
        synchronized(logs) {
            return logs.joinToString("\n") {
                "${it.timestamp} [${it.level}] ${it.tag}: ${it.message}"
            }
        }
    }

    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
}
