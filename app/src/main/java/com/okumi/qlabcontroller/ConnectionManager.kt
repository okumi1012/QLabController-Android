package com.okumi.qlabcontroller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConnectionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "qlab_connections"
        private const val KEY_CONNECTIONS = "saved_connections"
    }

    fun getSavedConnections(): List<SavedConnection> {
        val jsonString = prefs.getString(KEY_CONNECTIONS, "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val connections = mutableListOf<SavedConnection>()

        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            connections.add(
                SavedConnection(
                    id = json.getString("id"),
                    workspaceName = json.getString("workspaceName"),
                    ipAddress = json.getString("ipAddress"),
                    port = json.getInt("port"),
                    passcode = json.optString("passcode", ""),
                    lastConnected = json.getLong("lastConnected")
                )
            )
        }

        // Sort by lastConnected descending
        return connections.sortedByDescending { it.lastConnected }
    }

    fun saveConnection(connection: SavedConnection) {
        val connections = getSavedConnections().toMutableList()

        // Remove existing connection with same IP and port
        connections.removeAll { it.ipAddress == connection.ipAddress && it.port == connection.port }

        // Add new connection at the beginning
        connections.add(0, connection.copy(lastConnected = System.currentTimeMillis()))

        // Keep only last 10 connections
        val trimmedConnections = connections.take(10)

        // Save to preferences
        val jsonArray = JSONArray()
        trimmedConnections.forEach { conn ->
            jsonArray.put(JSONObject().apply {
                put("id", conn.id)
                put("workspaceName", conn.workspaceName)
                put("ipAddress", conn.ipAddress)
                put("port", conn.port)
                put("passcode", conn.passcode)
                put("lastConnected", conn.lastConnected)
            })
        }

        prefs.edit().putString(KEY_CONNECTIONS, jsonArray.toString()).apply()
    }

    fun deleteConnection(connectionId: String) {
        val connections = getSavedConnections().toMutableList()
        connections.removeAll { it.id == connectionId }

        val jsonArray = JSONArray()
        connections.forEach { conn ->
            jsonArray.put(JSONObject().apply {
                put("id", conn.id)
                put("workspaceName", conn.workspaceName)
                put("ipAddress", conn.ipAddress)
                put("port", conn.port)
                put("passcode", conn.passcode)
                put("lastConnected", conn.lastConnected)
            })
        }

        prefs.edit().putString(KEY_CONNECTIONS, jsonArray.toString()).apply()
    }

    fun createConnection(workspaceName: String, ipAddress: String, port: Int, passcode: String = ""): SavedConnection {
        return SavedConnection(
            id = UUID.randomUUID().toString(),
            workspaceName = workspaceName,
            ipAddress = ipAddress,
            port = port,
            passcode = passcode,
            lastConnected = System.currentTimeMillis()
        )
    }
}
