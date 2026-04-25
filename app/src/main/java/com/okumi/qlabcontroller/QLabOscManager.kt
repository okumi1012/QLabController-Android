package com.okumi.qlabcontroller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class QLabOscManager private constructor() {
    private var socket: DatagramSocket? = null
    private var receiveSocket: DatagramSocket? = null

    @Volatile
    private var isConnected = false

    private var qLabAddress: InetAddress? = null
    private var qLabPort: Int = DEFAULT_PORT
    private var receiveJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()

    private var currentCueId: String? = null
    private var currentCueNotes: String = ""
    private val cueList = mutableListOf<CueData>()
    private var workspaceId: String? = null
    private var workspaceName: String = "QLab Controller"
    private var mainCueListId: String? = null

    private var connectedIpAddress: String? = null
    private var connectedPort: Int = DEFAULT_PORT
    private var connectedWithPasscode = false

    private var workspaceResponse: CompletableDeferred<Boolean>? = null
    private var connectResponse: CompletableDeferred<Boolean>? = null

    companion object {
        private const val TAG = "QLabOscManager"
        private const val DEFAULT_PORT = 53000
        private const val RECEIVE_PORT = 53001
        private const val CONNECT_TIMEOUT_MS = 3_000L

        @Volatile
        private var instance: QLabOscManager? = null

        fun getInstance(): QLabOscManager {
            return instance ?: synchronized(this) {
                instance ?: QLabOscManager().also { instance = it }
            }
        }
    }

    data class CueData(
        val uniqueId: String,
        val number: String,
        val name: String,
        val type: String,
        val notes: String = ""
    )

    data class ConnectionInfo(
        val workspaceName: String,
        val ipAddress: String,
        val port: Int,
        val hasPasscode: Boolean
    )

    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT, passcode: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            qLabAddress = InetAddress.getByName(ipAddress)
            qLabPort = port
            connectedIpAddress = ipAddress
            connectedPort = port
            connectedWithPasscode = !passcode.isNullOrEmpty()

            socket = DatagramSocket()
            receiveSocket = try {
                DatagramSocket(RECEIVE_PORT)
            } catch (e: Exception) {
                LogManager.w(TAG, "Receive port $RECEIVE_PORT unavailable, using an ephemeral port")
                DatagramSocket()
            }

            isConnected = true
            startReceiveLoop()

            val replyPort = receiveSocket?.localPort ?: RECEIVE_PORT
            if (!sendOscMessageWithIntArg("/udpReplyPort", replyPort)) {
                disconnect()
                return@withContext false
            }

            workspaceResponse = CompletableDeferred()
            requestWorkspaces()
            val gotWorkspace = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                workspaceResponse?.await()
            } == true

            val wsId = synchronized(stateLock) { workspaceId }
            if (!gotWorkspace || wsId.isNullOrEmpty()) {
                LogManager.w(TAG, "QLab did not return a workspace before timeout")
                disconnect()
                return@withContext false
            }

            connectResponse = CompletableDeferred()
            val sentConnect = if (passcode.isNullOrEmpty()) {
                sendOscMessage("/workspace/$wsId/connect")
            } else {
                sendOscMessageWithStringArg("/workspace/$wsId/connect", passcode)
            }

            val authSuccess = sentConnect && (withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                connectResponse?.await()
            } == true)

            if (!authSuccess) {
                LogManager.w(TAG, "QLab rejected the connection or did not respond")
                disconnect()
                return@withContext false
            }

            sendOscMessageWithBooleanArg("/udpKeepAlive", true)
            requestCueLists()
            delay(300)

            LogManager.d(TAG, "Connected to QLab at $ipAddress:$port")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to connect to QLab", e)
            disconnect()
            false
        } finally {
            workspaceResponse = null
            connectResponse = null
        }
    }

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val buffer = ByteArray(65536)
            while (isActive && receiveSocket != null) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket?.receive(packet)
                    parseOscReply(packet.data.copyOf(packet.length))
                } catch (e: Exception) {
                    if (isActive && isConnected) {
                        LogManager.e(TAG, "Error receiving OSC reply", e)
                    }
                }
            }
        }
    }

    private fun parseOscReply(data: ByteArray) {
        try {
            val message = String(data, StandardCharsets.UTF_8)
            val firstNull = message.indexOf('\u0000')
            val address = if (firstNull > 0) message.substring(0, firstNull) else ""

            LogManager.d(TAG, "Received OSC message (${data.size} bytes), address: '$address'")

            if (address.startsWith("/update/")) {
                handleUpdateMessage(address, message)
                return
            }

            val jsonStart = message.indexOf('{')
            if (jsonStart < 0) {
                LogManager.d(TAG, "No JSON in message, raw content (first 100 chars): ${message.take(100)}")
                return
            }

            val jsonEnd = message.lastIndexOf('}')
            if (jsonEnd <= jsonStart) {
                LogManager.w(TAG, "No valid JSON found in message")
                return
            }

            val jsonStr = message.substring(jsonStart, jsonEnd + 1)
            val json = JSONObject(jsonStr)
            handleStatusResponse(json)

            when {
                json.has("data") -> handleDataResponse(json)
                json.has("uniqueID") -> handleCueResponse(json)
                else -> LogManager.d(TAG, "JSON response without data: ${json.keys().asSequence().toList()}")
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error parsing OSC reply", e)
        }
    }

    private fun handleStatusResponse(json: JSONObject) {
        val requestAddress = json.optString("address", "")
        val status = json.optString("status", "").lowercase()
        if (connectResponse == null || (requestAddress.isNotEmpty() && !requestAddress.contains("/connect"))) return

        when (status) {
            "ok" -> connectResponse?.complete(true)
            "badpass", "error" -> connectResponse?.complete(false)
        }
    }

    private fun handleUpdateMessage(address: String, fullMessage: String) {
        try {
            LogManager.d(TAG, "Update message: $address")

            when {
                address.contains("/playbackPosition") -> {
                    val typeTagStart = fullMessage.indexOf(',')
                    if (typeTagStart > 0) {
                        val afterTypeTag = fullMessage.substring(typeTagStart + 4)
                        val cueIdEnd = afterTypeTag.indexOf('\u0000')
                        synchronized(stateLock) {
                            currentCueId = if (cueIdEnd > 0) afterTypeTag.substring(0, cueIdEnd) else null
                        }
                        requestCurrentCueNotes()
                    }
                }

                address.matches(Regex("/update/workspace/[^/]+$")) -> requestCueLists()
                address.endsWith("/disconnect") -> disconnect()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling update message", e)
        }
    }

    private fun handleDataResponse(json: JSONObject) {
        try {
            val dataValue = json.opt("data")
            if (dataValue is String) {
                handleStringDataResponse(json, dataValue)
                return
            }

            val data = json.optJSONArray("data")
            if (data == null || data.length() == 0) {
                LogManager.w(TAG, "Response has no data array or data is empty")
                return
            }

            val firstItem = data.getJSONObject(0)

            if (firstItem.has("displayName") || firstItem.has("hasPasscode") || firstItem.has("version")) {
                synchronized(stateLock) {
                    workspaceId = firstItem.optString("uniqueID")
                    workspaceName = firstItem.optString("displayName", "QLab Controller")
                }
                workspaceResponse?.complete(true)
                LogManager.d(TAG, "Got workspace: $workspaceName (ID: $workspaceId)")
                return
            }

            if (firstItem.has("cues")) {
                val cuesArray = firstItem.optJSONArray("cues")
                synchronized(stateLock) {
                    mainCueListId = firstItem.optString("uniqueID")
                    replaceCueList(cuesArray)
                }
                requestPlaybackPosition()
                scope.launch {
                    delay(100)
                    requestCurrentCueNotes()
                }
                return
            }

            if (firstItem.optString("type") == "Cue List") {
                synchronized(stateLock) {
                    mainCueListId = firstItem.optString("uniqueID")
                }
                mainCueListId?.let { requestCuesFromList(it) }
                return
            }

            synchronized(stateLock) {
                replaceCueList(data)
            }
            requestPlaybackPosition()
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling data response", e)
        }
    }

    private fun handleStringDataResponse(json: JSONObject, dataString: String) {
        val requestAddress = json.optString("address", "")
        LogManager.d(TAG, "Data is a string from $requestAddress: $dataString")

        if (requestAddress.contains("/notes")) {
            synchronized(stateLock) {
                currentCueNotes = dataString
            }
            return
        }

        if (dataString.isNotEmpty() && dataString != "none") {
            var changed = false
            synchronized(stateLock) {
                changed = currentCueId != dataString
                currentCueId = dataString
            }
            if (changed) {
                requestCurrentCueNotes()
            }
        }
    }

    private fun replaceCueList(cuesArray: org.json.JSONArray?) {
        cueList.clear()
        if (cuesArray == null) return

        for (i in 0 until cuesArray.length()) {
            val cue = cuesArray.getJSONObject(i)
            val cueName = cue.optString("name", "").ifEmpty {
                cue.optString("listName", "Untitled")
            }
            val cueNotes = cue.optString("notes", "").ifEmpty {
                cue.optString("note", "")
            }

            cueList.add(
                CueData(
                    uniqueId = cue.optString("uniqueID", ""),
                    number = cue.optString("number", ""),
                    name = cueName,
                    type = cue.optString("type", ""),
                    notes = cueNotes
                )
            )
        }
        LogManager.d(TAG, "Loaded ${cueList.size} cues")
    }

    private fun handleCueResponse(json: JSONObject) {
        synchronized(stateLock) {
            currentCueId = json.optString("uniqueID")
        }
        LogManager.d(TAG, "Current cue ID: $currentCueId")
    }

    private fun requestWorkspaces() {
        sendOscMessage("/workspaces")
    }

    private fun requestCueLists() {
        val wsId = synchronized(stateLock) { workspaceId }
        wsId?.let { sendOscMessage("/workspace/$it/cueLists") }
    }

    private fun requestCuesFromList(cueListId: String) {
        val wsId = synchronized(stateLock) { workspaceId }
        wsId?.let { sendOscMessage("/workspace/$it/cue_id/$cueListId/children") }
    }

    private fun requestPlaybackPosition() {
        val wsId = synchronized(stateLock) { workspaceId }
        val command = wsId?.let { "/workspace/$it/playbackPosition" } ?: "/playbackPosition"
        sendOscMessage(command)
    }

    private fun requestCurrentCueNotes() {
        val currentCue = synchronized(stateLock) {
            cueList.firstOrNull { it.uniqueId == currentCueId || it.number == currentCueId }
        }

        if (currentCue != null && currentCue.uniqueId.isNotEmpty()) {
            val wsId = synchronized(stateLock) { workspaceId }
            val command = wsId?.let { "/workspace/$it/cue_id/${currentCue.uniqueId}/notes" }
                ?: "/cue_id/${currentCue.uniqueId}/notes"
            sendOscMessage(command)
        }
    }

    fun disconnect() {
        try {
            if (isConnected) {
                sendOscMessageWithBooleanArg("/udpKeepAlive", false)
            }

            receiveJob?.cancel()
            receiveJob = null
            socket?.close()
            socket = null
            receiveSocket?.close()
            receiveSocket = null
            isConnected = false

            synchronized(stateLock) {
                cueList.clear()
                currentCueId = null
                currentCueNotes = ""
                workspaceId = null
                mainCueListId = null
                workspaceName = "QLab Controller"
                connectedWithPasscode = false
            }

            workspaceResponse?.complete(false)
            connectResponse?.complete(false)
            LogManager.d(TAG, "Disconnected from QLab")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error disconnecting from QLab", e)
        }
    }

    suspend fun sendGo(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/go" } ?: "/go"
        val result = sendOscMessage(command)
        if (result) {
            delay(100)
            requestPlaybackPosition()
        }
        result
    }

    suspend fun sendPanic(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/panic" } ?: "/panic"
        sendOscMessage(command)
    }

    suspend fun sendPrevious(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/playhead/previous" } ?: "/previous"
        val result = sendOscMessage(command)
        if (result) {
            delay(100)
            requestPlaybackPosition()
        }
        result
    }

    suspend fun sendNext(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/playhead/next" } ?: "/next"
        val result = sendOscMessage(command)
        if (result) {
            delay(100)
            requestPlaybackPosition()
        }
        result
    }

    suspend fun getCurrentCueInfo(): CueInfo = withContext(Dispatchers.IO) {
        requestPlaybackPosition()
        delay(50)

        synchronized(stateLock) {
            val currentIndex = cueList.indexOfFirst {
                it.uniqueId == currentCueId || it.number == currentCueId
            }

            fun cueText(index: Int): String {
                if (index !in cueList.indices) return "---"
                val cue = cueList[index]
                return "${cue.number} ${cue.name}"
            }

            CueInfo(
                previous2Cue = cueText(currentIndex - 2),
                previousCue = cueText(currentIndex - 1),
                currentCue = cueText(currentIndex),
                nextCue = cueText(currentIndex + 1),
                next2Cue = cueText(currentIndex + 2),
                currentNotes = currentCueNotes
            )
        }
    }

    suspend fun renameCue(cueId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/cue_id/$cueId/name" } ?: "/cue_id/$cueId/name"
        sendOscMessageWithStringArg(command, newName)
    }

    private fun sendOscMessage(address: String): Boolean {
        return sendPacket(address, buildOscMessage(address))
    }

    private fun sendOscMessageWithStringArg(address: String, arg: String): Boolean {
        return sendPacket(address, buildOscMessageWithStringArg(address, arg))
    }

    private fun sendOscMessageWithIntArg(address: String, arg: Int): Boolean {
        return sendPacket(address, buildOscMessageWithIntArg(address, arg))
    }

    private fun sendOscMessageWithBooleanArg(address: String, arg: Boolean): Boolean {
        return sendPacket(address, buildOscMessageWithBooleanArg(address, arg))
    }

    private fun sendPacket(address: String, oscBytes: ByteArray): Boolean {
        if (!isConnected || socket == null || qLabAddress == null) {
            LogManager.w(TAG, "Not connected to QLab")
            return false
        }

        return try {
            val packet = DatagramPacket(oscBytes, oscBytes.size, qLabAddress, qLabPort)
            socket?.send(packet)
            LogManager.d(TAG, "Sent OSC message: $address")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to send OSC message: $address", e)
            false
        }
    }

    private fun buildOscMessage(address: String): ByteArray {
        return buildOscMessage(address, "", ByteArray(0))
    }

    private fun buildOscMessageWithStringArg(address: String, arg: String): ByteArray {
        val argBytes = paddedBytes(arg.toByteArray(StandardCharsets.UTF_8))
        return buildOscMessage(address, "s", argBytes)
    }

    private fun buildOscMessageWithIntArg(address: String, arg: Int): ByteArray {
        val argBytes = ByteBuffer.allocate(4).putInt(arg).array()
        return buildOscMessage(address, "i", argBytes)
    }

    private fun buildOscMessageWithBooleanArg(address: String, arg: Boolean): ByteArray {
        return buildOscMessage(address, if (arg) "T" else "F", ByteArray(0))
    }

    private fun buildOscMessage(address: String, typeTags: String, payload: ByteArray): ByteArray {
        val addressBytes = paddedBytes(address.toByteArray(StandardCharsets.UTF_8))
        val typeTagBytes = paddedBytes(",$typeTags".toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.allocate(addressBytes.size + typeTagBytes.size + payload.size).apply {
            put(addressBytes)
            put(typeTagBytes)
            put(payload)
        }.array()
    }

    private fun paddedBytes(bytes: ByteArray): ByteArray {
        val paddedSize = ((bytes.size + 1 + 3) / 4) * 4
        return ByteBuffer.allocate(paddedSize).apply {
            put(bytes)
        }.array()
    }

    fun isConnected(): Boolean = isConnected

    fun getWorkspaceName(): String = synchronized(stateLock) { workspaceName }

    fun getConnectionInfo(): ConnectionInfo? {
        return if (isConnected && connectedIpAddress != null) {
            ConnectionInfo(
                workspaceName = getWorkspaceName(),
                ipAddress = connectedIpAddress!!,
                port = connectedPort,
                hasPasscode = connectedWithPasscode
            )
        } else {
            null
        }
    }
}
