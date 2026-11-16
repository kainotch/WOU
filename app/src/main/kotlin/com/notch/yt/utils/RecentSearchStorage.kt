/*
 * wou - by Notch
 * Notch
 * Licensed Under GPL-3.0
 */

package com.notch.yt.utils

import android.content.Context
import com.notch.yt.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RecentSearchSong(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toMediaMetadata(): MediaMetadata = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
        duration = 0,
        thumbnailUrl = thumbnailUrl
    )
}

object RecentSearchStorage {
    private const val FILENAME = "wou_recent_search_songs.json"
    private const val MAX_ITEMS = 25
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _recentSongs = MutableStateFlow<List<RecentSearchSong>>(emptyList())
    val recentSongs: StateFlow<List<RecentSearchSong>> = _recentSongs.asStateFlow()

    private var initialized = false

    private fun fileFor(context: Context): File = File(context.filesDir, FILENAME)

    fun init(context: Context) {
        if (!initialized) {
            _recentSongs.value = loadAll(context)
            initialized = true
        }
    }

    fun loadAll(context: Context): List<RecentSearchSong> {
        return try {
            val file = fileFor(context)
            if (!file.exists()) emptyList()
            else {
                val text = file.readText()
                if (text.isBlank()) emptyList()
                else json.decodeFromString<List<RecentSearchSong>>(text)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveClickedSong(context: Context, song: RecentSearchSong) {
        try {
            val current = loadAll(context).toMutableList()
            current.removeAll { it.id == song.id }
            current.add(0, song)
            val trimmed = current.take(MAX_ITEMS)
            _recentSongs.value = trimmed
            fileFor(context).writeText(json.encodeToString(trimmed))
        } catch (_: Exception) {
        }
    }

    fun removeSong(context: Context, songId: String) {
        try {
            val current = loadAll(context).toMutableList()
            current.removeAll { it.id == songId }
            _recentSongs.value = current
            fileFor(context).writeText(json.encodeToString(current))
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            _recentSongs.value = emptyList()
            val file = fileFor(context)
            if (file.exists()) {
                file.writeText("[]")
            }
        } catch (_: Exception) {
        }
    }
}
