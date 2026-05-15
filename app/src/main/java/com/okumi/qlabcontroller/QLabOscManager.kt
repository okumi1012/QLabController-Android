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
import org.json.JSONArray
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
    private val pendingResponseLock = Any()
    private val pendingJsonResponses = mutableMapOf<String, MutableList<CompletableDeferred<JSONObject>>>()

    private var currentCueId: String? = null
    private var currentCueNotes: String = ""
    private val localCueList = mutableListOf<CueData>()
    private val cueStateReconciler = CueStateReconciler()
    private val requestedChildCueIds = mutableSetOf<String>()
    private var workspaceId: String? = null
    private var workspaceName: String = "QLab Controller"
    private var mainCueListId: String? = null

    private var connectedIpAddress: String? = null
    private var connectedPort: Int = DEFAULT_PORT
    private var connectedWithPasscode = false

    companion object {
        private const val TAG = "QLabOscManager"
        private const val DEFAULT_PORT = 53000
        private const val RECEIVE_PORT = 53001
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RESPONSE_TIMEOUT_MS = 700L
        private val CUE_NOTES_ADDRESS_REGEX = Regex("/cue_id/([^/]+)/notes$")

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
        val notes: String = "",
        val parentId: String? = null,
        val orderIndex: Int = 0,
        val isRunning: Boolean = false
    )

    data class ConnectionInfo(
        val workspaceName: String,
        val ipAddress: String,
        val port: Int,
        val hasPasscode: Boolean
    )

    private data class OscMessage(
        val address: String,
        val arguments: List<Any>
    )

    private data class PaddedString(
        val value: String,
        val nextOffset: Int
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

            val gotWorkspace = requestJson("/workspaces", CONNECT_TIMEOUT_MS) != null
            val wsId = synchronized(stateLock) { workspaceId }
            if (!gotWorkspace || wsId.isNullOrEmpty()) {
                LogManager.w(TAG, "QLab did not return a workspace before timeout")
                disconnect()
                return@withContext false
            }

            val connectAddress = "/workspace/$wsId/connect"
            val connectJson = requestJson(connectAddress, CONNECT_TIMEOUT_MS) {
                if (passcode.isNullOrEmpty()) {
                    sendOscMessage(connectAddress)
                } else {
                    sendOscMessageWithStringArg(connectAddress, passcode)
                }
            }
            if (connectJson?.optString("status", "")?.lowercase() != "ok") {
                LogManager.w(TAG, "QLab rejected the connection or did not respond")
                disconnect()
                return@withContext false
            }

            sendOscMessageWithBooleanArg("/udpKeepAlive", true)
            sendOscMessageWithIntArg("/updates", 1)
            sendOscMessageWithIntArg("/workspace/$wsId/updates", 1)
            loadCueLists()
            refreshPlaybackPosition()

            LogManager.d(TAG, "Connected to QLab at $ipAddress:$port")
            true
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to connect to QLab", e)
            disconnect()
            false
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
            val message = parseOscMessage(data)
            if (message == null) {
                LogManager.w(TAG, "Received invalid OSC packet (${data.size} bytes)")
                return
            }

            LogManager.d(TAG, "Received OSC message (${data.size} bytes), address: '${message.address}'")

            if (message.address.startsWith("/update/")) {
                handleUpdateMessage(message.address, message.arguments)
                return
            }

            val jsonStr = message.arguments.firstOrNull {
                it is String && it.trimStart().startsWith("{")
            } as? String
            if (jsonStr == null) {
                LogManager.d(TAG, "No JSON in OSC message")
                return
            }

            val json = JSONObject(jsonStr)
            when {
                json.has("data") -> handleDataResponse(json)
                json.has("uniqueID") -> handleCueResponse(json)
                else -> LogManager.d(TAG, "JSON response without data: ${json.keys().asSequence().toList()}")
            }

            val requestAddress = json.optString("address", "")
            if (requestAddress.isNotEmpty()) {
                completeJsonResponse(requestAddress, json)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error parsing OSC reply", e)
        }
    }

    private fun parseOscMessage(data: ByteArray): OscMessage? {
        val address = readPaddedString(data, 0) ?: return null
        if (address.value.isEmpty()) return null

        val typeTags = readPaddedString(data, address.nextOffset) ?: return OscMessage(address.value, emptyList())
        val tags = typeTags.value.removePrefix(",")
        var offset = typeTags.nextOffset
        val arguments = mutableListOf<Any>()

        tags.forEach { tag ->
            when (tag) {
                's' -> {
                    val arg = readPaddedString(data, offset) ?: return@forEach
                    arguments.add(arg.value)
                    offset = arg.nextOffset
                }
                'i' -> {
                    if (offset + 4 <= data.size) {
                        arguments.add(ByteBuffer.wrap(data, offset, 4).int)
                        offset += 4
                    }
                }
                'T' -> arguments.add(true)
                'F' -> arguments.add(false)
                else -> LogManager.w(TAG, "Unsupported OSC type tag: $tag")
            }
        }

        return OscMessage(address.value, arguments)
    }

    private fun readPaddedString(data: ByteArray, offset: Int): PaddedString? {
        if (offset >= data.size) return null

        var end = offset
        while (end < data.size && data[end] != 0.toByte()) {
            end++
        }
        if (end >= data.size) return null

        val value = String(data, offset, end - offset, StandardCharsets.UTF_8)
        val nextOffset = ((end + 1 + 3) / 4) * 4
        return PaddedString(value, nextOffset)
    }

    private fun handleUpdateMessage(address: String, arguments: List<Any>) {
        try {
            LogManager.d(TAG, "Update message: $address")

            when {
                address.contains("/playbackPosition") -> {
                    synchronized(stateLock) {
                        setCurrentCueLocked(arguments.firstOrNull() as? String, keepExistingOnAmbiguous = true)
                    }
                    requestCurrentCueNotes()
                }

                address.matches(Regex("/update/workspace/[^/]+$")) -> {
                    scope.launch { loadCueLists() }
                }

                address.endsWith("/disconnect") -> disconnect()
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Error handling update message", e)
        }
    }

    private fun handleDataResponse(json: JSONObject) {
        try {
            val requestAddress = json.optString("address", "")
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

            if (requestAddress == "/workspaces" || firstItem.has("hasPasscode") || firstItem.has("version")) {
                synchronized(stateLock) {
                    workspaceId = firstItem.optString("uniqueID")
                    workspaceName = firstItem.optString("displayName", "QLab Controller")
                }
                LogManager.d(TAG, "Got workspace: $workspaceName (ID: $workspaceId)")
                return
            }

            val childParentId = QLabCueStateParser.childParentIdFromRequest(requestAddress)
            if (childParentId != null) {
                val isMainCueList = synchronized(stateLock) { childParentId == mainCueListId }
                synchronized(stateLock) {
                    if (isMainCueList) {
                        replaceCueList(data)
                    } else {
                        replaceChildCueListLocked(childParentId, data)
                    }
                }
                requestChildrenForKnownGroups()
                requestPlaybackPosition()
                return
            }

            if (firstItem.has("cues")) {
                val cuesArray = firstItem.optJSONArray("cues")
                synchronized(stateLock) {
                    mainCueListId = firstItem.optString("uniqueID")
                    replaceCueList(cuesArray)
                }
                requestChildrenForKnownGroups()
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
            requestChildrenForKnownGroups()
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
                val responseCueId = CUE_NOTES_ADDRESS_REGEX.find(requestAddress)?.groupValues?.getOrNull(1)
                if (responseCueId == null || cueMatchesCurrentLocked(responseCueId)) {
                    currentCueNotes = dataString
                }
            }
            return
        }

        val isPlaybackPositionResponse = requestAddress.contains("/playbackPosition") ||
            requestAddress.contains("/playhead")
        val normalizedCueId = dataString.normalizeCueId()
        if (isPlaybackPositionResponse && normalizedCueId != null) {
            var changed = false
            synchronized(stateLock) {
                changed = currentCueId != normalizedCueId
                setCurrentCueLocked(normalizedCueId)
            }
            if (changed) {
                requestCurrentCueNotes()
            }
        } else if (isPlaybackPositionResponse && dataString.equals("none", ignoreCase = true)) {
            synchronized(stateLock) {
                setCurrentCueLocked(null)
            }
        } else if (isPlaybackPositionResponse) {
            LogManager.w(TAG, "Ignoring ambiguous playback position '$dataString'")
        }
    }

    private fun setCurrentCueLocked(cueId: String?, keepExistingOnAmbiguous: Boolean = false) {
        val normalizedCueId = cueId.normalizeCueId()
        if (normalizedCueId == null && keepExistingOnAmbiguous && currentCueId != null) {
            LogManager.w(TAG, "Keeping current cue '$currentCueId' while QLab reports ambiguous position '$cueId'")
            return
        }
        if (normalizedCueId == null) {
            currentCueId = null
            currentCueNotes = ""
            cueStateReconciler.reset()
            return
        }
        currentCueId = normalizedCueId
        val corrected = cueStateReconciler.setNetworkCue(normalizedCueId)
        if (corrected) {
            LogManager.w(TAG, "Cue prediction corrected by network state: $normalizedCueId")
        }
        currentCueNotes = normalizedCueId?.let { id ->
            localCueList.firstOrNull { it.uniqueId == id || it.number == id }?.notes
        }.orEmpty()
    }

    private fun cueMatchesCurrentLocked(cueId: String): Boolean {
        val current = currentCueId ?: return false
        if (current == cueId) return true
        return localCueList.firstOrNull { it.uniqueId == cueId }?.number == current
    }

    private fun replaceCueList(cuesArray: JSONArray?) {
        localCueList.clear()
        requestedChildCueIds.clear()
        localCueList.addAll(QLabCueStateParser.parseCueArray(cuesArray))
        renumberCueListLocked()
        LogManager.d(TAG, "Loaded ${localCueList.size} cues")
    }

    private fun replaceChildCueListLocked(parentId: String, cuesArray: JSONArray?) {
        val children = QLabCueStateParser.parseCueArray(cuesArray, parentId)
        val descendants = mutableSetOf(parentId)
        var changed: Boolean
        do {
            changed = false
            localCueList.forEach { cue ->
                val cueParentId = cue.parentId
                if (cueParentId != null && cueParentId in descendants && descendants.add(cue.uniqueId)) {
                    changed = true
                }
            }
        } while (changed)

        localCueList.removeAll { cue -> cue.parentId?.let { it in descendants } == true }
        val parentIndex = localCueList.indexOfFirst { it.uniqueId == parentId }
        if (parentIndex == -1) {
            localCueList.addAll(children)
        } else {
            localCueList.addAll(parentIndex + 1, children)
        }
        renumberCueListLocked()
        LogManager.d(TAG, "Loaded ${children.size} child cues for $parentId; flattened list has ${localCueList.size} cues")
    }

    private fun renumberCueListLocked() {
        for (index in localCueList.indices) {
            localCueList[index] = localCueList[index].copy(orderIndex = index)
        }
    }

    private fun requestChildrenForKnownGroups() {
        val groupCueIds = synchronized(stateLock) {
            localCueList.filter { cue ->
                cue.uniqueId.isNotBlank() &&
                    cue.type.contains("group", ignoreCase = true) &&
                    cue.uniqueId !in requestedChildCueIds
            }.map { it.uniqueId }.take(24)
        }
        groupCueIds.forEach { cueId ->
            synchronized(stateLock) {
                requestedChildCueIds.add(cueId)
            }
            requestCuesFromList(cueId)
        }
    }

    private fun handleCueResponse(json: JSONObject) {
        val requestAddress = json.optString("address", "")
        if (!requestAddress.contains("/playbackPosition") && !requestAddress.contains("/playhead")) return

        synchronized(stateLock) {
            setCurrentCueLocked(json.optString("uniqueID"), keepExistingOnAmbiguous = true)
        }
        LogManager.d(TAG, "Current cue ID: $currentCueId")
    }

    private suspend fun loadCueLists() {
        val wsId = synchronized(stateLock) { workspaceId }
        wsId?.let { requestJson("/workspace/$it/cueLists") }
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

    private suspend fun refreshPlaybackPosition(): Boolean {
        val wsId = synchronized(stateLock) { workspaceId }
        val command = wsId?.let { "/workspace/$it/playbackPosition" } ?: "/playbackPosition"
        val json = requestJson(command)
        return json?.optString("status", "")?.lowercase() == "ok"
    }

    private fun requestCurrentCueNotes() {
        val currentCue = synchronized(stateLock) {
            localCueList.firstOrNull { it.uniqueId == currentCueId || it.number == currentCueId }
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
                val wsId = synchronized(stateLock) { workspaceId }
                wsId?.let {
                    sendOscMessageWithIntArg("/workspace/$it/updates", 0)
                }
                sendOscMessageWithIntArg("/updates", 0)
                sendOscMessageWithBooleanArg("/udpKeepAlive", false)
                wsId?.let { sendOscMessage("/workspace/$it/disconnect") }
            }

            receiveJob?.cancel()
            receiveJob = null
            socket?.close()
            socket = null
            receiveSocket?.close()
            receiveSocket = null
            isConnected = false

            synchronized(stateLock) {
                localCueList.clear()
                requestedChildCueIds.clear()
                cueStateReconciler.reset()
                currentCueId = null
                currentCueNotes = ""
                workspaceId = null
                mainCueListId = null
                workspaceName = "QLab Controller"
                connectedWithPasscode = false
                connectedIpAddress = null
                connectedPort = DEFAULT_PORT
            }

            failPendingResponses()
            LogManager.d(TAG, "Disconnected from QLab")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error disconnecting from QLab", e)
        }
    }

    fun predictGo(): CueInfo = predictCueNavigation(CueStateReconciler.Action.Go)

    fun predictPrevious(): CueInfo = predictCueNavigation(CueStateReconciler.Action.Previous)

    fun predictNext(): CueInfo = predictCueNavigation(CueStateReconciler.Action.Next)

    fun getDisplayedCueInfo(): CueInfo {
        return synchronized(stateLock) {
            QLabCueStateParser.buildCueInfo(
                cues = localCueList,
                displayedCueId = cueStateReconciler.displayedCueId() ?: currentCueId,
                currentNotes = currentCueNotes,
                flags = cueStateReconciler.consumeFlags()
            )
        }
    }

    private fun predictCueNavigation(action: CueStateReconciler.Action): CueInfo {
        return synchronized(stateLock) {
            cueStateReconciler.predict(action, localCueList, currentCueId)
            val displayedCueId = cueStateReconciler.displayedCueId()
            localCueList.firstOrNull { it.uniqueId == displayedCueId || it.number == displayedCueId }?.let {
                currentCueNotes = it.notes
            }
            QLabCueStateParser.buildCueInfo(
                cues = localCueList,
                displayedCueId = displayedCueId ?: currentCueId,
                currentNotes = currentCueNotes,
                flags = CueStateReconciler.Flags(
                    networkCorrected = false,
                    externalCueChange = false,
                    localMovement = true
                )
            ).copy(isPredicted = true)
        }
    }

    suspend fun sendGo(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/go" } ?: "/go"
        val result = sendOscMessage(command)
        if (result) {
            delay(120)
            refreshPlaybackPosition()
        }
        result
    }

    suspend fun sendPanic(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/panic" } ?: "/panic"
        sendOscMessage(command)
    }

    suspend fun sendPrevious(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/playbackPosition/previous" } ?: "/previous"
        val result = sendOscMessage(command)
        if (result) {
            delay(120)
            refreshPlaybackPosition()
        }
        result
    }

    suspend fun sendNext(): Boolean = withContext(Dispatchers.IO) {
        val command = synchronized(stateLock) { workspaceId }?.let { "/workspace/$it/playbackPosition/next" } ?: "/next"
        val result = sendOscMessage(command)
        if (result) {
            delay(120)
            refreshPlaybackPosition()
        }
        result
    }

    suspend fun refreshCurrentCueInfo(): CueInfo = withContext(Dispatchers.IO) {
        refreshPlaybackPosition()

        synchronized(stateLock) {
            QLabCueStateParser.buildCueInfo(
                cues = localCueList,
                displayedCueId = cueStateReconciler.displayedCueId() ?: currentCueId,
                currentNotes = currentCueNotes,
                flags = cueStateReconciler.consumeFlags()
            )
        }
    }

    suspend fun getCurrentCueInfo(): CueInfo = refreshCurrentCueInfo()

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

    private suspend fun requestJson(
        address: String,
        timeoutMs: Long = RESPONSE_TIMEOUT_MS,
        send: () -> Boolean = { sendOscMessage(address) }
    ): JSONObject? {
        val deferred = CompletableDeferred<JSONObject>()
        synchronized(pendingResponseLock) {
            pendingJsonResponses.getOrPut(address) { mutableListOf() }.add(deferred)
        }

        if (!send()) {
            removePendingResponse(address, deferred)
            return null
        }

        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            removePendingResponse(address, deferred)
        }
    }

    private fun completeJsonResponse(address: String, json: JSONObject) {
        val waiters = synchronized(pendingResponseLock) {
            pendingJsonResponses.remove(address)
        }.orEmpty()
        waiters.forEach { it.complete(json) }
    }

    private fun removePendingResponse(address: String, deferred: CompletableDeferred<JSONObject>) {
        synchronized(pendingResponseLock) {
            val waiters = pendingJsonResponses[address] ?: return
            waiters.remove(deferred)
            if (waiters.isEmpty()) {
                pendingJsonResponses.remove(address)
            }
        }
    }

    private fun failPendingResponses() {
        val waiters = synchronized(pendingResponseLock) {
            val all = pendingJsonResponses.values.flatten()
            pendingJsonResponses.clear()
            all
        }
        waiters.forEach { it.completeExceptionally(IllegalStateException("Disconnected")) }
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
