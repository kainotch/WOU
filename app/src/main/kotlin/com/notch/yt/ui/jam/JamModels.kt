package com.notch.yt.ui.jam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object JamConstants {
    const val DEFAULT_JAM_SERVER_WS = "wss://wou-jam.onrender.com/ws"
    const val DEFAULT_JAM_SERVER_HTTP = "https://wou-jam.onrender.com"

    const val MSG_CREATE_ROOM = "create_room"
    const val MSG_JOIN_ROOM = "join_room"
    const val MSG_LEAVE_ROOM = "leave_room"
    const val MSG_SEND_CHAT = "send_chat"
    const val MSG_PLAYBACK_ACTION = "playback_action"
    const val MSG_PING = "ping"

    const val MSG_ROOM_CREATED = "room_created"
    const val MSG_SYNC_STATE = "sync_state"
    const val MSG_USER_JOINED = "user_joined"
    const val MSG_USER_LEFT = "user_left"
    const val MSG_CHAT_MESSAGE = "chat_message"
    const val MSG_SYNC_PLAYBACK = "sync_playback"
    const val MSG_QUEUE_UPDATE = "queue_update"
    const val MSG_QUEUE_UPDATED = "queue_updated"
    const val MSG_HOST_CHANGED = "host_changed"
    const val MSG_ROOM_MODE_CHANGED = "room_mode_changed"
    const val MSG_ERROR = "error"

    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_SEEK = "seek"
    const val ACTION_CHANGE_TRACK = "change_track"
    const val ACTION_QUEUE_ADD = "queue_add"
    const val ACTION_QUEUE_REMOVE = "queue_remove"
    const val ACTION_QUEUE_CLEAR = "queue_clear"
    const val ACTION_SKIP_NEXT = "skip_next"
    const val ACTION_SKIP_PREV = "skip_prev"

    const val MODE_HOST_ONLY = "host_only"
    const val MODE_COLLABORATIVE = "collaborative"
}

@Serializable
data class JamEnvelope(
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonElement? = null,
)

@Serializable
data class CreateRoomRequest(
    @SerialName("name") val name: String,
    @SerialName("room_mode") val roomMode: String = JamConstants.MODE_COLLABORATIVE,
)

@Serializable
data class RoomCreatedPayload(
    @SerialName("room_code") val roomCode: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = true,
    @SerialName("room_mode") val roomMode: String = JamConstants.MODE_COLLABORATIVE,
)

@Serializable
data class ChangeRoomModeRequest(
    @SerialName("room_mode") val roomMode: String,
)

@Serializable
data class JoinRoomRequest(
    @SerialName("room_code") val roomCode: String,
    @SerialName("name") val name: String,
)

@Serializable
data class TrackInfo(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0L,
)

@Serializable
data class PlaybackActionRequest(
    @SerialName("action") val action: String,
    @SerialName("track") val track: TrackInfo? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("client_time_ms") val clientTimeMs: Long = System.currentTimeMillis(),
    @SerialName("is_playing") val isPlaying: Boolean = true,
    @SerialName("queue") val queue: List<TrackInfo>? = null,
)

@Serializable
data class PlaybackState(
    @SerialName("current_track") val currentTrack: TrackInfo? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("server_time_ms") val serverTimeMs: Long = 0L,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("queue") val queue: List<TrackInfo> = emptyList(),
)

@Serializable
data class Participant(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("is_cohost") val isCoHost: Boolean = false,
    @SerialName("joined_at") val joinedAt: Long = 0L,
)

@Serializable
data class SyncStatePayload(
    @SerialName("room_code") val roomCode: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("host_id") val hostId: String = "",
    @SerialName("room_mode") val roomMode: String = JamConstants.MODE_COLLABORATIVE,
    @SerialName("playback") val playback: PlaybackState = PlaybackState(),
    @SerialName("participants") val participants: List<Participant> = emptyList(),
)

@Serializable
data class SyncPlaybackPayload(
    @SerialName("action") val action: String = "",
    @SerialName("from_user_id") val fromUserId: String = "",
    @SerialName("track") val track: TrackInfo? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("server_time_ms") val serverTimeMs: Long = 0L,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("queue") val queue: List<TrackInfo> = emptyList(),
)

@Serializable
data class QueueUpdatePayload(
    @SerialName("queue") val queue: List<TrackInfo> = emptyList(),
)

@Serializable
data class QueueUpdatedPayload(
    @SerialName("queue") val queue: List<TrackInfo> = emptyList(),
    @SerialName("host_id") val hostId: String? = null,
)

@Serializable
data class HostChangedPayload(
    @SerialName("new_host_id") val newHostId: String = "",
    @SerialName("new_host_name") val newHostName: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = true,
)

@Serializable
data class UserJoinedPayload(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("is_cohost") val isCoHost: Boolean = false,
    @SerialName("total_users") val totalUsers: Int = 0,
    @SerialName("participant") val participant: Participant? = null,
)

@Serializable
data class UserLeftPayload(
    @SerialName("user_id") val userId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("is_host") val isHost: Boolean = false,
    @SerialName("total_users") val totalUsers: Int = 0,
)

@Serializable
data class ErrorPayload(
    @SerialName("code") val code: String = "",
    @SerialName("message") val message: String = "",
)
