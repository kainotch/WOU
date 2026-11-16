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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
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
import com.notch.yt.innertube.models.*
import com.notch.yt.LocalDatabase
import com.notch.yt.LocalPlayerConnection
import com.notch.yt.R
import com.notch.yt.extensions.togglePlayPause
import com.notch.yt.models.toMediaMetadata
import com.notch.yt.playback.queues.YouTubeQueue
import com.notch.yt.ui.component.LocalMenuState
import com.notch.yt.ui.component.YouTubeListItem
import com.notch.yt.ui.menu.*
import com.notch.yt.viewmodels.OnlineSearchSuggestionViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

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
            if (viewState.history.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
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
                                    database.query {
                                        clearSearchHistory()
                                    }
                                }
                                .padding(4.dp)
                        )
                    }
                }

                items(viewState.history.take(5), key = { "history_${it.query}" }) { history ->
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
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickExploreChip(
                        title = "Charts",
                        iconRes = R.drawable.trending_up,
                        onClick = {
                            navController.navigate("charts_screen")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        pureBlack = pureBlack
                    )
                    QuickExploreChip(
                        title = "New Releases",
                        iconRes = R.drawable.auto_awesome,
                        onClick = {
                            navController.navigate("new_release")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        pureBlack = pureBlack
                    )
                    QuickExploreChip(
                        title = "Moods & Genres",
                        iconRes = R.drawable.explore_filled,
                        onClick = {
                            navController.navigate("mood_and_genres")
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        pureBlack = pureBlack
                    )
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Browse all",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            defaultBrowseCategories.chunked(2).forEach { row ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { cat ->
                            SpotifyCategoryCard(
                                category = cat,
                                onClick = {
                                    onQueryChange(TextFieldValue(cat.query))
                                    onSearch(cat.query)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
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
private fun QuickExploreChip(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (pureBlack) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(44.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (pureBlack) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpotifyCategoryCard(
    category: SearchBrowseCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(category.startColor, category.endColor),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.75f)
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .align(Alignment.BottomEnd)
                .graphicsLayer(rotationZ = 15f)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(category.iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

data class SearchBrowseCategory(
    val title: String,
    val query: String,
    val startColor: Color,
    val endColor: Color,
    val iconRes: Int = R.drawable.album,
)

val defaultBrowseCategories = listOf(
    SearchBrowseCategory("Pop", "Pop music", Color(0xFF8D67AB), Color(0xFFE8115B), R.drawable.album),
    SearchBrowseCategory("Hip-Hop", "Hip hop hits", Color(0xFFBA5D00), Color(0xFFE91429), R.drawable.album),
    SearchBrowseCategory("Rock", "Rock classics", Color(0xFFE91429), Color(0xFF7D1B28), R.drawable.album),
    SearchBrowseCategory("Dance & EDM", "Dance EDM hits", Color(0xFF006450), Color(0xFF1DB954), R.drawable.radio),
    SearchBrowseCategory("Indie", "Indie alternative", Color(0xFFE1118C), Color(0xFF8C1932), R.drawable.album),
    SearchBrowseCategory("R&B & Soul", "R&B soul hits", Color(0xFFDC148C), Color(0xFF4A154B), R.drawable.album),
    SearchBrowseCategory("Bollywood", "Bollywood top hits", Color(0xFFE13300), Color(0xFFF59B23), R.drawable.star),
    SearchBrowseCategory("Punjabi", "Punjabi hits", Color(0xFFF59B23), Color(0xFFD84000), R.drawable.star),
    SearchBrowseCategory("Chill & Lofi", "Lofi chill beats", Color(0xFF477D95), Color(0xFF1E3264), R.drawable.radio),
    SearchBrowseCategory("Workout", "Workout energy music", Color(0xFF4A5568), Color(0xFF27856A), R.drawable.trending_up),
    SearchBrowseCategory("Focus & Study", "Focus study instrumental", Color(0xFF503750), Color(0xFF8D67AB), R.drawable.radio),
    SearchBrowseCategory("Sleep & Relax", "Sleep deep relaxation", Color(0xFF1E3264), Color(0xFF006450), R.drawable.radio),
    SearchBrowseCategory("Party", "Party dance mix", Color(0xFFE13300), Color(0xFFE8115B), R.drawable.auto_awesome),
    SearchBrowseCategory("Gaming", "Gaming beats synthwave", Color(0xFF0D73EC), Color(0xFF1E3264), R.drawable.radio),
    SearchBrowseCategory("Romance", "Love romance songs", Color(0xFFB02897), Color(0xFFE91429), R.drawable.star),
    SearchBrowseCategory("Acoustic", "Acoustic covers", Color(0xFF7D4B32), Color(0xFF8C67AB), R.drawable.album),
)
