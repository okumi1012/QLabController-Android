package com.okumi.qlabcontroller

data class SavedConnection(
    val id: String,  // Unique ID for this saved connection
    val workspaceName: String,
    val ipAddress: String,
    val port: Int,
    val passcode: String = "",
    val lastConnected: Long = System.currentTimeMillis()
)
