/*
 * wou - by Notch
 * Notch
 * Licensed Under GPL-3.0
 */

package com.notch.yt.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.notch.yt.R
import com.notch.yt.db.entities.Album
import com.notch.yt.db.entities.Artist
import com.notch.yt.db.entities.Playlist
import com.notch.yt.db.entities.Song
import com.notch.yt.innertube.models.AlbumItem
import com.notch.yt.innertube.models.ArtistItem
import com.notch.yt.innertube.models.PlaylistItem
import com.notch.yt.innertube.models.SongItem
import com.notch.yt.innertube.pages.HomePage
import com.notch.yt.models.MediaMetadata
import com.notch.yt.models.SimilarRecommendation
import com.notch.yt.models.toMediaMetadata
import com.notch.yt.playback.PlayerConnection
import com.notch.yt.playback.queues.YouTubeQueue

data class SpotifyGridItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val isLikedMusic: Boolean = false,
    val isPlaying: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null
)

fun buildSpotifyGridItems(
    likedSongsCount: Int,
    likedSongsTitle: String,
    localPlaylists: List<Playlist>,
    accountPlaylists: List<PlaylistItem>?,
    similarRecommendations: List<SimilarRecommendation>?,
    homePage: HomePage?,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection
): List<SpotifyGridItem> {
    val items = mutableListOf<SpotifyGridItem>()

    // 1. Liked Songs
    if (likedSongsCount > 0) {
        items.add(
            SpotifyGridItem(
                id = "auto_playlist_liked",
                title = likedSongsTitle,
                isLikedMusic = true,
                isPlaying = isPlaying && mediaMetadata?.id != null,
                onClick = { navController.navigate("auto_playlist/liked") }
            )
        )
    }

    // 2. User's Local Playlists
    localPlaylists.forEach { playlist ->
        items.add(
            SpotifyGridItem(
                id = "local_pl_${playlist.id}",
                title = playlist.playlist.name,
                thumbnailUrl = playlist.songThumbnails.firstOrNull(),
                isPlaying = false,
                onClick = { navController.navigate("local_playlist/${playlist.id}") }
            )
        )
    }

    // 3. User's Account Playlists (if signed in)
    accountPlaylists?.forEach { playlist ->
        items.add(
            SpotifyGridItem(
                id = "online_pl_${playlist.id}",
                title = playlist.title,
                thumbnailUrl = playlist.thumbnail,
                isPlaying = false,
                onClick = { navController.navigate("online_playlist/${playlist.id}") }
            )
        )
    }

    // 4. If fewer than 8 items: Similar to Artist recommendations
    // Per user instruction: "in 8 item grid if user dont have enough playlist so other will be similar to artist thing ok?"
    if (items.size < 8) {
        similarRecommendations?.forEach { rec ->
            if (items.size >= 8) return@forEach
            when (val titleObj = rec.title) {
                is Artist -> {
                    val thumb = titleObj.artist.thumbnailUrl ?: rec.items.firstNotNullOfOrNull { it.thumbnail }
                    items.add(
                        SpotifyGridItem(
                            id = "artist_${titleObj.id}",
                            title = titleObj.artist.name,
                            thumbnailUrl = thumb,
                            isPlaying = isPlaying && mediaMetadata?.artists?.any { it.id == titleObj.id } == true,
                            onClick = { navController.navigate("artist/${titleObj.id}") }
                        )
                    )
                }
                is Song -> {
                    items.add(
                        SpotifyGridItem(
                            id = "song_${titleObj.id}",
                            title = titleObj.song.title,
                            thumbnailUrl = titleObj.song.thumbnailUrl,
                            isPlaying = isPlaying && mediaMetadata?.id == titleObj.id,
                            onClick = {
                                playerConnection.playQueue(YouTubeQueue.radio(titleObj.toMediaMetadata()))
                            }
                        )
                    )
                }
                is Album -> {
                    items.add(
                        SpotifyGridItem(
                            id = "album_${titleObj.id}",
                            title = titleObj.album.title,
                            thumbnailUrl = titleObj.album.thumbnailUrl,
                            isPlaying = isPlaying && mediaMetadata?.album?.id == titleObj.id,
                            onClick = { navController.navigate("album/${titleObj.id}") }
                        )
                    )
                }
                else -> {}
            }

            rec.items.forEach { ytItem ->
                if (items.size >= 8) return@forEach
                when (ytItem) {
                    is PlaylistItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "rec_pl_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = false,
                                onClick = { navController.navigate("online_playlist/${ytItem.id}") }
                            )
                        )
                    }
                    is AlbumItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "rec_album_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = isPlaying && mediaMetadata?.album?.id == ytItem.id,
                                onClick = { navController.navigate("album/${ytItem.id}") }
                            )
                        )
                    }
                    is ArtistItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "rec_artist_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = false,
                                onClick = { navController.navigate("artist/${ytItem.id}") }
                            )
                        )
                    }
                    is SongItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "rec_song_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = isPlaying && mediaMetadata?.id == ytItem.id,
                                onClick = {
                                    playerConnection.playQueue(YouTubeQueue.radio(ytItem.toMediaMetadata()))
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    // 5. Fallback from YouTube Music home page sections if still under 8 items
    if (items.size < 8) {
        homePage?.sections?.forEach { section ->
            if (items.size >= 8) return@forEach
            section.items.forEach { ytItem ->
                if (items.size >= 8) return@forEach
                when (ytItem) {
                    is PlaylistItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "home_pl_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = false,
                                onClick = { navController.navigate("online_playlist/${ytItem.id}") }
                            )
                        )
                    }
                    is AlbumItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "home_album_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = isPlaying && mediaMetadata?.album?.id == ytItem.id,
                                onClick = { navController.navigate("album/${ytItem.id}") }
                            )
                        )
                    }
                    is SongItem -> {
                        items.add(
                            SpotifyGridItem(
                                id = "home_song_${ytItem.id}",
                                title = ytItem.title,
                                thumbnailUrl = ytItem.thumbnail,
                                isPlaying = isPlaying && mediaMetadata?.id == ytItem.id,
                                onClick = {
                                    playerConnection.playQueue(YouTubeQueue.radio(ytItem.toMediaMetadata()))
                                }
                            )
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    return items.distinctBy { it.id }.take(8)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpotifyQuickCard(
    item: SpotifyGridItem,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF282828))
            .combinedClickable(
                onClick = item.onClick,
                onLongClick = item.onLongClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.isLikedMusic) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4D0FB))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.favorite),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else if (item.thumbnailUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                    .background(Color(0xFF383838)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = Color.LightGray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (item.isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.volume_up),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SpotifyQuickGrid(
    items: List<SpotifyGridItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    SpotifyQuickCard(
                        item = item,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SpotifyFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else Color(0xFF282828)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.5.sp
            ),
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White
        )
    }
}

@Composable
fun SpotifyTopBar(
    accountImageUrl: String?,
    chips: List<HomePage.Chip>,
    selectedChip: HomePage.Chip?,
    onSelectChip: (HomePage.Chip?) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFF282828))
                .clickable(onClick = onProfileClick),
            contentAlignment = Alignment.Center
        ) {
            if (accountImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(accountImageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpotifyFilterChip(
                text = stringResource(R.string.filter_all),
                isSelected = selectedChip == null,
                onClick = {
                    if (selectedChip != null) {
                        onSelectChip(selectedChip)
                    }
                }
            )

            chips.forEach { chip ->
                SpotifyFilterChip(
                    text = chip.title,
                    isSelected = selectedChip == chip,
                    onClick = {
                        onSelectChip(chip)
                    }
                )
            }
        }
    }
}
