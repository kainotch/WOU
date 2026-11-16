package com.notch.yt.ui.jam

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.notch.yt.innertube.models.WatchEndpoint
import com.notch.yt.models.MediaMetadata
import com.notch.yt.playback.PlayerConnection
import com.notch.yt.playback.queues.YouTubeQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            if (!state.isHost && state.roomMode == JamConstants.MODE_HOST_ONLY) return

            val player = playerConnection?.player ?: return
            val meta = playerConnection?.mediaMetadata?.value ?: return
            sendPlaybackAction(
                action = JamConstants.ACTION_CHANGE_TRACK,
                track = meta.toTrackInfo(),
                positionMs = 0L,
                isPlaying = player.isPlaying
            )
        }
    }

    fun bindPlayerConnection(connection: PlayerConnection) {
        if (playerConnection == connection) return
        playerConnection?.player?.removeListener(playerListener)
        playerConnection = connection
        connection.player.addListener(playerListener)
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

    fun sendPlaybackAction(
        action: String,
        track: TrackInfo? = null,
        positionMs: Long = 0L,
        isPlaying: Boolean = true
    ) {
        val state = _uiState.value
        if (!state.isConnected) return
        val req = PlaybackActionRequest(
            action = action,
            track = track,
            positionMs = positionMs,
            clientTimeMs = System.currentTimeMillis(),
            isPlaying = isPlaying
        )
        val payload = json.encodeToJsonElement(req)
        sendEnvelope(JamEnvelope(type = JamConstants.MSG_PLAYBACK_ACTION, payload = payload))
    }

    private fun connectWebSocket(onConnected: () -> Unit) {
        if (webSocket != null && _uiState.value.isConnected) {
            onConnected()
            return
        }

        pendingAction = onConnected
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
                    _uiState.value = _uiState.value.copy(isConnected = true, errorMessage = null)
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
                scope.launch {
                    _uiState.value = _uiState.value.copy(isConnected = false, isConnecting = false)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                scope.launch {
                    _uiState.value = _uiState.value.copy(
                        isConnected = false,
                        isConnecting = false,
                        errorMessage = t.message ?: "Failed to connect to Jam server"
                    )
                }
            }
        })
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
                    val updated = _uiState.value.participants.filter { it.userId != payload.participant.userId } + payload.participant
                    _uiState.value = _uiState.value.copy(participants = updated)
                }

                JamConstants.MSG_USER_LEFT -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<UserLeftPayload>(it)
                    } ?: return
                    val updated = _uiState.value.participants.filter { it.userId != payload.userId }
                    _uiState.value = _uiState.value.copy(participants = updated)
                }

                JamConstants.MSG_SYNC_PLAYBACK -> {
                    val payload = envelope.payload?.let {
                        json.decodeFromJsonElement<SyncPlaybackPayload>(it)
                    } ?: return

                    if (payload.fromUserId.isNotBlank() && payload.fromUserId == _uiState.value.userId) {
                        return // Skip echo of our own action
                    }

                    _uiState.value = _uiState.value.copy(
                        currentTrack = payload.track ?: _uiState.value.currentTrack,
                        isPlaying = payload.isPlaying,
                        positionMs = payload.positionMs
                    )

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
                delay(500)
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
