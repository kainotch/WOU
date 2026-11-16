/*
 * wou - by Notch
 * Notch
 * Licensed Under GPL-3.0
 */

package com.notch.yt.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.notch.yt.LocalDatabase
import com.notch.yt.LocalPlayerConnection
import com.notch.yt.R
import com.notch.yt.extensions.togglePlayPause
import com.notch.yt.innertube.models.*
import com.notch.yt.models.toMediaMetadata
import com.notch.yt.playback.queues.YouTubeQueue
import com.notch.yt.ui.component.LocalMenuState
import com.notch.yt.ui.component.YouTubeListItem
import com.notch.yt.ui.menu.*
import com.notch.yt.utils.RecentSearchSong
import com.notch.yt.utils.RecentSearchStorage
import com.notch.yt.viewmodels.OnlineSearchSuggestionViewModel
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchScreen(
    query: String,
    onQueryChange: (TextFieldValue) -> Unit,
    navController: NavController,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    pureBlack: Boolean,
    viewModel: OnlineSearchSuggestionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val scope = rememberCoroutineScope()

    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val viewState by viewModel.viewState.collectAsState()

    val lazyListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        RecentSearchStorage.init(context)
    }

    val recentSongs by RecentSearchStorage.recentSongs.collectAsState()

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        viewModel.query.value = query
    }

    LazyColumn(
        state = lazyListState,
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        ),
        modifier = Modifier
            .fillMaxSize()
            .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        if (query.isEmpty()) {
            // Top two clean boxes: Music and Podcasts
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SpotifyTopBox(
                        title = "Music",
                        iconRes = R.drawable.music_note,
                        backgroundColor = Color(0xFFDC148C),
                        onClick = {
                            onQueryChange(TextFieldValue("Music"))
                            onSearch("Music")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    SpotifyTopBox(
                        title = "Podcasts",
                        iconRes = R.drawable.graphic_eq,
                        backgroundColor = Color(0xFF006450),
                        onClick = {
                            onQueryChange(TextFieldValue("Podcasts"))
                            onSearch("Podcasts")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Below them: users recent songs he clicked
            if (recentSongs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent searches",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.clear),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    RecentSearchStorage.clear(context)
                                }
                                .padding(4.dp)
                        )
                    }
                }

                items(recentSongs, key = { it.id }) { song ->
                    RecentSearchSongItem(
                        song = song,
                        pureBlack = pureBlack,
                        onClick = {
                            RecentSearchStorage.saveClickedSong(context, song)
                            playerConnection.playQueue(
                                YouTubeQueue.radio(song.toMediaMetadata())
                            )
                            onDismiss()
                        },
                        onDelete = {
                            RecentSearchStorage.removeSong(context, song.id)
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item {
                Spacer(Modifier.height(120.dp))
            }
        } else {
            items(viewState.history, key = { "history_${it.query}" }) { history ->
                SuggestionItem(
                    query = history.query,
                    online = false,
                    onClick = {
                        onSearch(history.query)
                        onDismiss()
                    },
                    onDelete = {
                        database.query {
                            delete(history)
                        }
                    },
                    onFillTextField = {
                        onQueryChange(TextFieldValue(history.query, TextRange(history.query.length)))
                    },
                    modifier = Modifier.animateItem(),
                    pureBlack = pureBlack
                )
            }

            items(viewState.suggestions, key = { "suggestion_$it" }) { query ->
                SuggestionItem(
                    query = query,
                    online = true,
                    onClick = {
                        onSearch(query)
                        onDismiss()
                    },
                    onFillTextField = {
                        onQueryChange(TextFieldValue(query, TextRange(query.length)))
                    },
                    modifier = Modifier.animateItem(),
                    pureBlack = pureBlack
                )
            }

            if (viewState.items.isNotEmpty() && viewState.history.size + viewState.suggestions.size > 0) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.top_results),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (pureBlack) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(viewState.items.distinctBy { it.id }, key = { "item_${it.id}" }) { item ->
                YouTubeListItem(
                    item = item,
                    isActive = when (item) {
                        is SongItem -> mediaMetadata?.id == item.id
                        is AlbumItem -> mediaMetadata?.album?.id == item.id
                        else -> false
                    },
                    isPlaying = isPlaying,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    when (item) {
                                        is SongItem -> YouTubeSongMenu(
                                            song = item,
                                            navController = navController,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                        is AlbumItem -> YouTubeAlbumMenu(
                                            albumItem = item,
                                            navController = navController,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                        is ArtistItem -> YouTubeArtistMenu(
                                            artist = item,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                        is PlaylistItem -> YouTubePlaylistMenu(
                                            playlist = item,
                                            coroutineScope = scope,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier
                        .combinedClickable(
                            onClick = {
                                when (item) {
                                    is SongItem -> {
                                        RecentSearchStorage.saveClickedSong(
                                            context,
                                            RecentSearchSong(
                                                id = item.id,
                                                title = item.title,
                                                artist = item.artists.joinToString { it.name },
                                                thumbnailUrl = item.thumbnail
                                            )
                                        )
                                        if (item.id == mediaMetadata?.id) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                YouTubeQueue.radio(item.toMediaMetadata())
                                            )
                                            onDismiss()
                                        }
                                    }
                                    is AlbumItem -> {
                                        navController.navigate("album/${item.id}")
                                        onDismiss()
                                    }
                                    is ArtistItem -> {
                                        navController.navigate("artist/${item.id}")
                                        onDismiss()
                                    }
                                    is PlaylistItem -> {
                                        navController.navigate("online_playlist/${item.id}")
                                        onDismiss()
                                    }
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    when (item) {
                                        is SongItem -> YouTubeSongMenu(
                                            song = item,
                                            navController = navController,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                        is AlbumItem -> YouTubeAlbumMenu(
                                            albumItem = item,
                                            navController = navController,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                        is ArtistItem -> YouTubeArtistMenu(
                                            artist = item,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                        is PlaylistItem -> YouTubePlaylistMenu(
                                            playlist = item,
                                            coroutineScope = coroutineScope,
                                            onDismiss = {
                                                menuState.dismiss()
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        )
                        .animateItem()
                )
            }

            item {
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

@Composable
fun SuggestionItem(
    modifier: Modifier = Modifier,
    query: String,
    online: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
    onFillTextField: () -> Unit,
    pureBlack: Boolean
) {
    val iconContainerColor = if (pureBlack) {
        Color.White.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    }

    val iconTint = if (pureBlack) {
        Color.White.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = iconContainerColor,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Icon(
                painterResource(if (online) R.drawable.search else R.drawable.history),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Text(
            text = query,
            style = MaterialTheme.typography.bodyLarge,
            color = if (pureBlack) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!online) {
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                    tint = if (pureBlack) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        IconButton(onClick = onFillTextField) {
            Icon(
                painter = painterResource(R.drawable.arrow_top_left),
                contentDescription = null,
                tint = if (pureBlack) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SpotifyTopBox(
    title: String,
    iconRes: Int,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .size(24.dp)
                .align(Alignment.BottomEnd)
        )
    }
}

@Composable
private fun RecentSearchSongItem(
    song: RecentSearchSong,
    pureBlack: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (pureBlack) Color.DarkGray else MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (song.artist.isNotBlank()) "Song • ${song.artist}" else "Song",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
