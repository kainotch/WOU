package com.notch.yt.ui.jam

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.notch.yt.extensions.metadata
import com.notch.yt.extensions.toMediaItem
import com.notch.yt.innertube.models.WatchEndpoint
import com.notch.yt.models.MediaMetadata
import com.notch.yt.playback.PlayerConnection
import com.notch.yt.playback.queues.YouTubeQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class JamUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val roomCode: String? = null,
    val userId: String? = null,
    val userName: String = "",
    val isHost: Boolean = false,
    val hostName: String = "",
    val roomMode: String = JamConstants.MODE_COLLABORATIVE,
    val participants: List<Participant> = emptyList(),
    val totalUsers: Int = 1,
    val queue: List<TrackInfo> = emptyList(),
    val currentTrack: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val errorMessage: String? = null,
)

object JamManager {
    private const val TAG = "JamManager"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var pendingAction: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(JamUiState())
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    private var playerConnection: PlayerConnection? = null
    private var isApplyingRemoteUpdate = false
    private var lastRemoteAppliedTrackId: String? = null
    private var lastBroadcastTrackId: String? = null

    private var userInitiatedLeave = false
    private var reconnectJob: Job? = null
    private var queueSyncJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isApplyingRemoteUpdate) return
            val state = _uiState.value
            if (!state.isConnected || state.roomCode == null) return
            if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return

            val player = playerConnection?.player ?: return
            val meta = playerConnection?.mediaMetadata?.value
            val track = meta?.toTrackInfo() ?: state.currentTrack
            sendPlaybackAction(
                action = if (isPlaying) JamConstants.ACTION_PLAY else JamConstants.ACTION_PAUSE,
                track = track,
                positionMs = player.currentPosition,
                isPlaying = isPlaying
            )
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isApplyingRemoteUpdate) return
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val state = _uiState.value
            if (!state.isConnected || state.roomCode == null) return
            if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return

            val player = playerConnection?.player ?: return
            val meta = playerConnection?.mediaMetadata?.value
            val track = meta?.toTrackInfo() ?: state.currentTrack
            sendPlaybackAction(
                action = JamConstants.ACTION_SEEK,
                track = track,
                positionMs = newPosition.positionMs,
                isPlaying = player.isPlaying
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isApplyingRemoteUpdate) return
            val state = _uiState.value
            if (!state.isConnected || state.roomCode == null) return

            // Automatic queue transitions (AUTO) must ONLY be driven by the host.
            // A guest's local player must NEVER broadcast automatic end-of-track transitions.
            if (!state.isHost && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                return
            }

            if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return

            val player = playerConnection?.player ?: return
            val meta = mediaItem?.metadata ?: playerConnection?.mediaMetadata?.value ?: return
            val trackInfo = meta.toTrackInfo()
            if (trackInfo.id.isBlank()) return

            // Deduplicate to avoid broadcast loops
            if (trackInfo.id == lastRemoteAppliedTrackId) return
            if (trackInfo.id == lastBroadcastTrackId && player.currentPosition < 1000L) return

            lastBroadcastTrackId = trackInfo.id
            sendPlaybackAction(
                action = JamConstants.ACTION_CHANGE_TRACK,
                track = trackInfo,
                positionMs = 0L,
                isPlaying = player.isPlaying
            )

            if (state.isHost) {
                syncExoPlayerQueueToJam()
            }
        }
    }

    fun bindPlayerConnection(connection: PlayerConnection) {
        if (playerConnection == connection) return
        playerConnection?.player?.removeListener(playerListener)
        queueSyncJob?.cancel()
        playerConnection = connection
        connection.player.addListener(playerListener)

        // Sync host's ExoPlayer queue to Jam queue whenever it changes
        queueSyncJob = scope.launch {
            connection.queueWindows.collect { windows ->
                val state = _uiState.value
                if (state.isConnected && state.isHost) {
                    val currentIdx = connection.currentMediaItemIndex.value
                    if (windows.isNotEmpty() && currentIdx < windows.size) {
                        val upcoming = windows.drop(currentIdx + 1).take(15).mapNotNull { it.mediaItem.metadata?.toTrackInfo() }
                        if (upcoming != state.queue) {
                            syncHostQueue(upcoming)
                        }
                    }
                }
            }
        }
    }

    private fun syncExoPlayerQueueToJam() {
        val conn = playerConnection ?: return
        val player = conn.player
        val currentIdx = player.currentMediaItemIndex
        val totalCount = player.mediaItemCount
        if (currentIdx + 1 < totalCount) {
            val upcoming = mutableListOf<TrackInfo>()
            for (i in (currentIdx + 1) until minOf(totalCount, currentIdx + 16)) {
                val item = player.getMediaItemAt(i)
                val itemMeta = item.metadata
                if (itemMeta != null && itemMeta.id.isNotBlank()) {
                    upcoming.add(itemMeta.toTrackInfo())
                }
            }
            if (upcoming.isNotEmpty()) {
                syncHostQueue(upcoming)
            }
        }
    }

    fun sanitizeDisplayName(name: String?): String {
        if (name.isNullOrBlank()) return "Listener"
        val lower = name.lowercase().trim()
        if (lower.contains("sdk") ||
            lower.contains("gphone") ||
            lower.contains("x86") ||
            lower.contains("emulator") ||
            lower.contains("generic") ||
            lower.startsWith("android_") ||
            lower.contains("build")
        ) {
            return "Listener"
        }
        return name.replace("_", " ").trim().ifBlank { "Listener" }
    }

    fun createJam(name: String, collaborative: Boolean = true) {
        userInitiatedLeave = false
        reconnectJob?.cancel()
        val validName = sanitizeDisplayName(name)
        val mode = if (collaborative) JamConstants.MODE_COLLABORATIVE else JamConstants.MODE_HOST_ONLY
        _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)

        val doCreate = {
            val payload = json.encodeToJsonElement(CreateRoomRequest(name = validName, roomMode = mode))
            val envelope = JamEnvelope(type = JamConstants.MSG_CREATE_ROOM, payload = payload)
            sendEnvelope(envelope)
        }

        connectWebSocket { doCreate() }
    }

    fun joinJam(code: String, name: String) {
        userInitiatedLeave = false
        reconnectJob?.cancel()
        val validCode = code.trim().uppercase()
        val validName = sanitizeDisplayName(name)
        if (validCode.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a room code")
            return
        }

        _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)

        val doJoin = {
            val payload = json.encodeToJsonElement(JoinRoomRequest(roomCode = validCode, name = validName))
            val envelope = JamEnvelope(type = JamConstants.MSG_JOIN_ROOM, payload = payload)
            sendEnvelope(envelope)
        }

        connectWebSocket { doJoin() }
    }

    fun leaveJam() {
        userInitiatedLeave = true
        reconnectJob?.cancel()
        if (_uiState.value.isConnected) {
            val envelope = JamEnvelope(type = JamConstants.MSG_LEAVE_ROOM)
            sendEnvelope(envelope)
        }
        try {
            webSocket?.close(1000, "Leaving Jam")
        } catch (_: Exception) {}
        webSocket = null
        _uiState.value = JamUiState()
    }

    fun setRoomMode(collaborative: Boolean) {
        val state = _uiState.value
        if (!state.isConnected || !state.isHost) return
        val mode = if (collaborative) JamConstants.MODE_COLLABORATIVE else JamConstants.MODE_HOST_ONLY
        val payload = json.encodeToJsonElement(ChangeRoomModeRequest(roomMode = mode))
        sendEnvelope(JamEnvelope(type = JamConstants.MSG_ROOM_MODE_CHANGED, payload = payload))
        _uiState.value = state.copy(roomMode = mode)
    }

    fun addToQueue(track: TrackInfo) {
        val state = _uiState.value
        if (!state.isConnected) return
        if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return
        val updated = state.queue + track
        _uiState.value = state.copy(queue = updated)
        sendPlaybackAction(action = JamConstants.ACTION_QUEUE_ADD, track = track, queue = updated)
        if (state.isHost) {
            playerConnection?.addToQueue(track.toMediaMetadata().toMediaItem())
        }
    }

    fun removeFromQueue(track: TrackInfo) {
        val state = _uiState.value
        if (!state.isConnected) return
        if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return
        val updated = state.queue.filter { it.id != track.id }
        _uiState.value = state.copy(queue = updated)
        sendPlaybackAction(action = JamConstants.ACTION_QUEUE_REMOVE, track = track, queue = updated)
    }

    fun clearQueue() {
        val state = _uiState.value
        if (!state.isConnected) return
        if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return
        _uiState.value = state.copy(queue = emptyList())
        sendPlaybackAction(action = JamConstants.ACTION_QUEUE_CLEAR, queue = emptyList())
    }

    fun syncHostQueue(upcomingTracks: List<TrackInfo>) {
        val state = _uiState.value
        if (!state.isConnected || !state.isHost) return
        _uiState.value = state.copy(queue = upcomingTracks)
        val payload = json.encodeToJsonElement(QueueUpdatePayload(queue = upcomingTracks))
        sendEnvelope(JamEnvelope(type = JamConstants.MSG_QUEUE_UPDATE, payload = payload))
    }

    fun sendPlaybackAction(
        action: String,
        track: TrackInfo? = null,
        positionMs: Long = 0L,
        isPlaying: Boolean = true,
        queue: List<TrackInfo>? = null,
    ) {
        val state = _uiState.value
        if (!state.isConnected) return
        val req = PlaybackActionRequest(
            action = action,
            track = track,
            positionMs = positionMs,
            clientTimeMs = System.currentTimeMillis(),
            isPlaying = isPlaying,
            queue = queue ?: state.queue,
        )
        val payload = json.encodeToJsonElement(req)
        sendEnvelope(JamEnvelope(type = JamConstants.MSG_PLAYBACK_ACTION, payload = payload))
    }

    private fun connectWebSocket(onConnected: (() -> Unit)? = null) {
        if (webSocket != null && _uiState.value.isConnected) {
            onConnected?.invoke()
            return
        }

        if (onConnected != null) {
            pendingAction = onConnected
        }

        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}

        val request = Request.Builder()
            .url(JamConstants.DEFAULT_JAM_SERVER_WS)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to ${JamConstants.DEFAULT_JAM_SERVER_WS}")
                scope.launch {
                    reconnectJob?.cancel()
                    _uiState.value = _uiState.value.copy(isConnected = true, isConnecting = false, errorMessage = null)
                    pendingAction?.invoke()
                    pendingAction = null
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    handleServerMessage(text)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason ($code)")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        scope.launch {
            if (userInitiatedLeave) {
                _uiState.value = JamUiState()
                return@launch
            }
            val roomCode = _uiState.value.roomCode
            if (roomCode != null) {
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isConnecting = true,
                    errorMessage = "Reconnecting to Jam..."
                )
                scheduleReconnect()
            } else {
                _uiState.value = _uiState.value.copy(isConnected = false, isConnecting = false)
            }
        }
    }

    private fun scheduleReconnect() {
        if (userInitiatedLeave || _uiState.value.roomCode == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            while (!userInitiatedLeave && !_uiState.value.isConnected && _uiState.value.roomCode != null) {
                attempt++
                val delayMs = (attempt * 1200L).coerceAtMost(5000L)
                delay(delayMs)
                if (userInitiatedLeave) break
                val code = _uiState.value.roomCode ?: break
                val name = _uiState.value.userName
                Log.d(TAG, "Reconnecting attempt $attempt to Jam room $code...")
                try {
                    connectWebSocket {
                        val payload = json.encodeToJsonElement(JoinRoomRequest(roomCode = code, name = name))
                        sendEnvelope(JamEnvelope(type = JamConstants.MSG_JOIN_ROOM, payload = payload))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt error", e)
                }
            }
        }
    }

    private fun sendEnvelope(envelope: JamEnvelope) {
        val text = json.encodeToString(JamEnvelope.serializer(), envelope)
        webSocket?.send(text)
    }

    private fun handleServerMessage(text: String) {
        try {
            val envelope = json.decodeFromString(JamEnvelope.serializer(), text)
            when (envelope.type) {
                JamConstants.MSG_ROOM_CREATED -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<RoomCreatedPayload>(it)
                    } ?: return
                    val hostParticipant = Participant(
                        userId = payload.userId,
                        name = payload.name,
                        isHost = true,
                        joinedAt = System.currentTimeMillis()
                    )
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isConnected = true,
                        roomCode = payload.roomCode,
                        userId = payload.userId,
                        userName = payload.name,
                        isHost = true,
                        hostName = payload.name,
                        roomMode = payload.roomMode,
                        participants = listOf(hostParticipant),
                        totalUsers = 1,
                        errorMessage = null
                    )

                    // If host already playing a track, broadcast it to the room
                    val player = playerConnection?.player
                    val meta = playerConnection?.mediaMetadata?.value
                    if (player != null && meta != null) {
                        sendPlaybackAction(
                            action = JamConstants.ACTION_CHANGE_TRACK,
                            track = meta.toTrackInfo(),
                            positionMs = player.currentPosition,
                            isPlaying = player.isPlaying
                        )
                    }
                    syncExoPlayerQueueToJam()
                }

                JamConstants.MSG_SYNC_STATE -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<SyncStatePayload>(it)
                    } ?: return

                    val hostName = payload.participants.find { it.isHost || it.userId == payload.hostId }?.name ?: "Host"

                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        isConnected = true,
                        roomCode = payload.roomCode,
                        userId = payload.userId,
                        userName = payload.name,
                        isHost = payload.isHost,
                        hostName = hostName,
                        roomMode = payload.roomMode,
                        participants = payload.participants,
                        totalUsers = payload.participants.size,
                        queue = payload.playback.queue,
                        currentTrack = payload.playback.currentTrack,
                        isPlaying = payload.playback.isPlaying,
                        positionMs = payload.playback.positionMs,
                        errorMessage = null
                    )

                    if (!payload.isHost) {
                        applyRemotePlayback(
                            action = JamConstants.ACTION_CHANGE_TRACK,
                            track = payload.playback.currentTrack,
                            positionMs = payload.playback.positionMs,
                            serverTimeMs = payload.playback.serverTimeMs,
                            isPlaying = payload.playback.isPlaying
                        )
                    }
                }

                JamConstants.MSG_USER_JOINED -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<UserJoinedPayload>(it)
                    } ?: return
                    val p = payload.participant ?: Participant(
                        userId = payload.userId,
                        name = payload.name,
                        isHost = payload.isHost,
                        isCoHost = payload.isCoHost,
                        joinedAt = System.currentTimeMillis()
                    )
                    val updated = _uiState.value.participants.filter { it.userId != p.userId } + p
                    val count = if (payload.totalUsers > 0) payload.totalUsers else updated.size
                    _uiState.value = _uiState.value.copy(
                        participants = updated,
                        totalUsers = count
                    )
                }

                JamConstants.MSG_USER_LEFT -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<UserLeftPayload>(it)
                    } ?: return
                    val updated = _uiState.value.participants.filter { it.userId != payload.userId }
                    val count = if (payload.totalUsers > 0) payload.totalUsers else updated.size
                    _uiState.value = _uiState.value.copy(
                        participants = updated,
                        totalUsers = count
                    )
                }

                JamConstants.MSG_HOST_CHANGED -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<HostChangedPayload>(it)
                    } ?: return
                    val isSelf = payload.newHostId == _uiState.value.userId
                    val updatedParticipants = _uiState.value.participants.map {
                        it.copy(isHost = it.userId == payload.newHostId)
                    }
                    _uiState.value = _uiState.value.copy(
                        isHost = isSelf,
                        hostName = payload.newHostName.ifBlank { payload.name },
                        participants = updatedParticipants
                    )
                }

                JamConstants.MSG_QUEUE_UPDATED -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<QueueUpdatedPayload>(it)
                    } ?: return
                    _uiState.value = _uiState.value.copy(queue = payload.queue)
                }

                JamConstants.MSG_SYNC_PLAYBACK -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<SyncPlaybackPayload>(it)
                    } ?: return

                    if (payload.fromUserId.isNotBlank() && payload.fromUserId == _uiState.value.userId) {
                        return // Skip echo of our own action
                    }

                    val updatedQueue = if (payload.queue.isNotEmpty() || payload.action.startsWith("queue_")) {
                        payload.queue
                    } else _uiState.value.queue

                    _uiState.value = _uiState.value.copy(
                        currentTrack = payload.track ?: _uiState.value.currentTrack,
                        isPlaying = payload.isPlaying,
                        positionMs = payload.positionMs,
                        queue = updatedQueue
                    )

                    // If host and a guest added a track to queue, add to host's local player queue
                    if (_uiState.value.isHost && payload.action == JamConstants.ACTION_QUEUE_ADD && payload.track != null) {
                        val track = payload.track
                        val p = playerConnection?.player
                        val count = p?.mediaItemCount ?: 0
                        val currentQueueIds = (0 until count).mapNotNull { p?.getMediaItemAt(it)?.mediaId }.toSet()
                        if (!currentQueueIds.contains(track.id)) {
                            playerConnection?.addToQueue(track.toMediaMetadata().toMediaItem())
                        }
                    }

                    applyRemotePlayback(
                        action = payload.action,
                        track = payload.track,
                        positionMs = payload.positionMs,
                        serverTimeMs = payload.serverTimeMs,
                        isPlaying = payload.isPlaying
                    )
                }

                JamConstants.MSG_ROOM_MODE_CHANGED -> {
                    envelope.payload?.let {
                        val req = json.decodeFromJsonElement<ChangeRoomModeRequest>(it)
                        _uiState.value = _uiState.value.copy(roomMode = req.roomMode)
                    }
                }

                JamConstants.MSG_ERROR -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<ErrorPayload>(it)
                    }
                    val msg = payload?.message ?: "An error occurred"
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        errorMessage = msg
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $text", e)
        }
    }

    private fun applyRemotePlayback(
        action: String,
        track: TrackInfo?,
        positionMs: Long,
        serverTimeMs: Long,
        isPlaying: Boolean
    ) {
        val conn = playerConnection ?: return
        scope.launch(Dispatchers.Main) {
            isApplyingRemoteUpdate = true
            lastRemoteAppliedTrackId = track?.id
            try {
                // 1. Check if track needs loading
                if (track != null && track.id.isNotBlank()) {
                    val currentId = conn.mediaMetadata.value?.id
                    if (currentId != track.id) {
                        val endpoint = WatchEndpoint(videoId = track.id)
                        val preload = track.toMediaMetadata()
                        conn.playQueue(YouTubeQueue(endpoint = endpoint, preloadItem = preload))
                    }
                }

                // 2. Network transit delay compensation
                val now = System.currentTimeMillis()
                val transit = if (serverTimeMs > 0 && now > serverTimeMs) {
                    (now - serverTimeMs).coerceAtMost(3000L)
                } else 0L
                val targetPos = positionMs + (if (isPlaying) transit else 0L)

                // 3. Apply position seek if action is seek or drift is substantial (> 1500ms)
                val currentPos = conn.player.currentPosition
                if (action == JamConstants.ACTION_SEEK || kotlin.math.abs(currentPos - targetPos) > 1500L) {
                    conn.player.seekTo(targetPos.coerceAtLeast(0L))
                }

                // 4. Play/Pause alignment
                if (isPlaying && !conn.player.isPlaying) {
                    conn.player.play()
                } else if (!isPlaying && conn.player.isPlaying) {
                    conn.player.pause()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply remote playback", e)
            } finally {
                delay(1200)
                isApplyingRemoteUpdate = false
            }
        }
    }
}

fun MediaMetadata.toTrackInfo(): TrackInfo = TrackInfo(
    id = id,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    album = album?.title,
    artworkUrl = thumbnailUrl,
    durationMs = duration * 1000L,
)

fun TrackInfo.toMediaMetadata(): MediaMetadata = MediaMetadata(
    id = id,
    title = title,
    artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
    duration = (durationMs / 1000).toInt(),
    thumbnailUrl = artworkUrl,
    album = album?.let { MediaMetadata.Album(id = "", title = it) }
)
