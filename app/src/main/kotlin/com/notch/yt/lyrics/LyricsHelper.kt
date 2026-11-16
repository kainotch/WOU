/*
 * wou - by Notch
 * Notch
 * Licensed Under GPL-3.0
 */



package com.notch.yt.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.notch.yt.utils.GlobalLog
import com.notch.yt.constants.PreferredLyricsProvider
import com.notch.yt.constants.PreferredLyricsProviderKey
import com.notch.yt.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.notch.yt.extensions.toEnum
import com.notch.yt.models.MediaMetadata
import com.notch.yt.utils.dataStore
import com.notch.yt.utils.reportException
import com.notch.yt.utils.NetworkConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    private val baseProviders =
        listOf(
            SimpMusicLyricsProvider,
            BetterLyricsProvider,
            LrcLibLyricsProvider,
            KuGouLyricsProvider,
            YouTubeSubtitleLyricsProvider,
            YouTubeLyricsProvider,
        )

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null

    private fun cleanSongTitle(title: String): String {
        val cleaned = title
            .replace(Regex("""(?i)\s*[\(\[](?:(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|mv|lyric\s*video|lyrics?)|remastered|4k|hd|live|slowed(?:\s*\+\s*reverb)?|sped\s*up|bass\s*boosted|clean(?:\s*loop)?|\d+\s*(?:hour|min|k)|prod\.?.*|feat\.?.*|ft\.?.*)[\)\]]"""), "")
            .replace(Regex("""(?i)\s*\|\s*(?:(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|mv|lyric\s*video|lyrics?)|remastered|4k|hd|live).*"""), "")
            .replace(Regex("""(?i)\s*-\s*(?:(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|mv|lyric\s*video|lyrics?)|remastered|4k|hd|live).*"""), "")
            .trim()
        return cleaned.ifEmpty { title }
    }

    private fun cleanArtists(artists: List<MediaMetadata.Artist>): String {
        return artists
            .map { it.name.trim() }
            .filter { name ->
                name.isNotBlank() &&
                !name.equals("Video", ignoreCase = true) &&
                !name.equals("Episode", ignoreCase = true) &&
                !name.equals("Song", ignoreCase = true) &&
                !name.equals("Topic", ignoreCase = true) &&
                !name.equals("Various Artists", ignoreCase = true) &&
                !name.matches(Regex("""(?i)^\d+(?:\.\d+)?[kmb]?\s+views$""")) &&
                !name.matches(Regex("""(?i)^\d+:\d+(?:\s*views)?$"""))
            }
            .joinToString(", ")
    }

    private fun isSyncedLyrics(lyrics: String): Boolean {
        return TIMESTAMP_REGEX.containsMatchIn(lyrics) ||
                lyrics.contains("<p begin=", ignoreCase = true) ||
                lyrics.contains("<tt", ignoreCase = true)
    }

    private suspend fun fetchFromProvider(
        provider: LyricsProvider,
        mediaMetadata: MediaMetadata,
        cleanTitle: String,
        cleanArtist: String,
        timeoutMs: Long,
    ): String? {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // 1. Try cleanTitle with cleanArtist
                val res1 = provider.getLyrics(
                    mediaMetadata.id,
                    cleanTitle,
                    cleanArtist,
                    mediaMetadata.album?.title,
                    mediaMetadata.duration,
                ).getOrNull()
                if (res1 != null && isMeaningfulLyrics(res1)) return@withTimeoutOrNull res1

                // 2. If cleanArtist was non-blank, try without artist (title only)
                if (cleanArtist.isNotBlank()) {
                    val res2 = provider.getLyrics(
                        mediaMetadata.id,
                        cleanTitle,
                        "",
                        mediaMetadata.album?.title,
                        mediaMetadata.duration,
                    ).getOrNull()
                    if (res2 != null && isMeaningfulLyrics(res2)) return@withTimeoutOrNull res2
                }

                // 3. If cleanTitle contains " X " / " x " (mashup/remix), try base title before " X "
                if (cleanTitle.contains(Regex("""\s+[xX]\s+"""))) {
                    val baseTitle = cleanTitle.split(Regex("""\s+[xX]\s+"""), limit = 2)[0].trim()
                    if (baseTitle.isNotBlank()) {
                        val res3 = provider.getLyrics(
                            mediaMetadata.id,
                            baseTitle,
                            cleanArtist,
                            mediaMetadata.album?.title,
                            mediaMetadata.duration,
                        ).getOrNull()
                        if (res3 != null && isMeaningfulLyrics(res3)) return@withTimeoutOrNull res3
                    }
                }

                // 4. If cleanTitle differs from raw title, try raw title
                if (cleanTitle != mediaMetadata.title) {
                    val res4 = provider.getLyrics(
                        mediaMetadata.id,
                        mediaMetadata.title,
                        cleanArtist,
                        mediaMetadata.album?.title,
                        mediaMetadata.duration,
                    ).getOrNull()
                    if (res4 != null && isMeaningfulLyrics(res4)) return@withTimeoutOrNull res4
                }

                null
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun getLyrics(mediaMetadata: MediaMetadata, preferredProviderOnly: Boolean = false): String {
        currentLyricsJob?.cancel()

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
            return cached.lyrics
        }
        
        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString { it.name }}, Album: ${mediaMetadata.album?.title})")

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }
        
        if (!isNetworkAvailable) {
            GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
            return LYRICS_NOT_FOUND
        }

        val ordered = orderedProviders()
        val providers = if (preferredProviderOnly) listOf(ordered.first()) else ordered
        val enabledProviders = providers.filter { it.isEnabled(context) }
        if (enabledProviders.isEmpty()) {
            return LYRICS_NOT_FOUND
        }

        var cleanArtist = cleanArtists(mediaMetadata.artists)
        var cleanTitle = cleanSongTitle(mediaMetadata.title)

        if (cleanArtist.isBlank() && " - " in cleanTitle) {
            val parts = cleanTitle.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                cleanArtist = parts[0].trim()
                cleanTitle = parts[1].trim()
            }
        }

        if (preferredProviderOnly || enabledProviders.size == 1) {
            val winner = fetchFromProvider(enabledProviders.first(), mediaMetadata, cleanTitle, cleanArtist, 4000L)
            val result = winner ?: LYRICS_NOT_FOUND
            if (result != LYRICS_NOT_FOUND) {
                cache.put(mediaMetadata.id, listOf(LyricsResult(enabledProviders.first().name, result)))
            }
            return result
        }

        val preferred = enabledProviders.first()
        val others = enabledProviders.drop(1)
        val winner = CompletableDeferred<LyricsResult>()
        val fallbackPlainLyrics = AtomicReference<LyricsResult?>(null)
        val pendingCount = AtomicInteger(enabledProviders.size)
        val allJobs = mutableListOf<Job>()
        val startOthersTrigger = CompletableDeferred<Unit>()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun launchProvider(provider: LyricsProvider) {
            val job = scope.launch {
                try {
                    val lyrics = fetchFromProvider(provider, mediaMetadata, cleanTitle, cleanArtist, 3500L)
                    if (lyrics != null && isMeaningfulLyrics(lyrics)) {
                        val result = LyricsResult(provider.name, lyrics)
                        if (isSyncedLyrics(lyrics)) {
                            winner.complete(result)
                        } else {
                            fallbackPlainLyrics.compareAndSet(null, result)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (provider == preferred && !winner.isCompleted) {
                        startOthersTrigger.complete(Unit)
                    }

                    if (pendingCount.decrementAndGet() == 0 && !winner.isCompleted) {
                        val fallback = fallbackPlainLyrics.get()
                        if (fallback != null) {
                            winner.complete(fallback)
                        } else {
                            winner.complete(LyricsResult("None", LYRICS_NOT_FOUND))
                        }
                    }
                }
            }
            allJobs.add(job)
        }

        // Launch preferred provider immediately
        launchProvider(preferred)

        // Launch supervisor to trigger others either after 400ms headstart or as soon as preferred finishes
        val orchestratorJob = scope.launch {
            withTimeoutOrNull(400L) {
                startOthersTrigger.await()
            }
            if (!winner.isCompleted) {
                others.forEach { launchProvider(it) }
            }
        }
        allJobs.add(orchestratorJob)

        val finalResult = try {
            withTimeoutOrNull(4500L) {
                winner.await()
            } ?: fallbackPlainLyrics.get() ?: LyricsResult("None", LYRICS_NOT_FOUND)
        } catch (e: Exception) {
            fallbackPlainLyrics.get() ?: LyricsResult("None", LYRICS_NOT_FOUND)
        } finally {
            allJobs.forEach { it.cancel() }
            scope.cancel()
        }

        if (finalResult.lyrics != LYRICS_NOT_FOUND) {
            cache.put(mediaMetadata.id, listOf(finalResult))
        }

        return finalResult.lyrics
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        songAlbum: String?,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }

        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true
        }
        
        if (!isNetworkAvailable) {
            return
        }

        val cleanArtist = if (songArtists.matches(Regex("""(?i).*\b(?:views|video|episode)\b.*"""))) "" else songArtists
        var effTitle = cleanSongTitle(songTitle)
        var effArtist = cleanArtist
        if (effArtist.isBlank() && " - " in effTitle) {
            val parts = effTitle.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                effArtist = parts[0].trim()
                effTitle = parts[1].trim()
            }
        }
        val allResult = mutableListOf<LyricsResult>()
        val providers = orderedProviders()
        currentLyricsJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            providers.forEach { provider ->
                if (provider.isEnabled(context)) {
                    try {
                        provider.getAllLyrics(mediaId, effTitle, effArtist, songAlbum, duration) lyricsCallback@{ lyrics ->
                            if (!isMeaningfulLyrics(lyrics)) return@lyricsCallback
                            val result = LyricsResult(provider.name, lyrics)
                            allResult += result
                            callback(result)
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
            }
            cache.put(cacheKey, allResult)
        }

        currentLyricsJob?.join()
    }

    private suspend fun orderedProviders(): List<LyricsProvider> {
        val preferred =
            context.dataStore.data
                .first()[PreferredLyricsProviderKey]
                .toEnum(PreferredLyricsProvider.LRCLIB)

        val first =
            when (preferred) {
                PreferredLyricsProvider.LRCLIB -> LrcLibLyricsProvider
                PreferredLyricsProvider.KUGOU -> KuGouLyricsProvider
                PreferredLyricsProvider.BETTER_LYRICS -> BetterLyricsProvider
                PreferredLyricsProvider.SIMPMUSIC -> SimpMusicLyricsProvider
            }

        return listOf(first) + baseProviders.filterNot { provider -> provider == first }
    }

    private fun isMeaningfulLyrics(lyrics: String): Boolean {
        val normalized =
            lyrics
                .replace("\uFEFF", "")
                .replace(INVISIBLE_CHARS_REGEX, "")
                .trim { it.isWhitespace() || it == '\u00A0' }

        if (normalized.isEmpty()) return false
        if (normalized == LYRICS_NOT_FOUND) return false

        val remaining =
            TIMESTAMP_REGEX
                .replace(normalized, "")
                .replace(INVISIBLE_CHARS_REGEX, "")
                .trim { it.isWhitespace() || it == '\u00A0' }

        return remaining.any { !it.isWhitespace() && it != '\u00A0' }
    }

    fun cancelCurrentLyricsJob() {
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
        private val TIMESTAMP_REGEX = Regex("""\[[0-9]{1,2}:[0-9]{2}(?:\.[0-9]{1,3})?]""")
        private val INVISIBLE_CHARS_REGEX = Regex("""[\u200B\u200C\u200D\u2060\u00AD]""")
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)
