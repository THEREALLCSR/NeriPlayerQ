package moe.ouom.neriplayer.ui.screen.collection

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * File: moe.ouom.neriplayer.ui.screen.collection/SongCollectionScreen
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.artist.EmptyBlock
import moe.ouom.neriplayer.ui.screen.artist.ErrorBlock
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.util.format.formatDuration

/**
 * 通用"歌曲集合"整页，结构对齐网易云歌单页：
 * TopAppBar（返回）、标题/数量、播放全部/随机/下载、歌曲列表；
 * 支持长按进入多选，多选后可批量下载、加入队列、全选。
 *
 * 任何平台只要给出 `List<SongItem>` 就能复用（QQ 每日推荐、网易云每日推荐等）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SongCollectionScreen(
    title: String,
    songs: List<SongItem>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onAddToPlaylist: ((List<SongItem>) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var addDialogSongs by remember { mutableStateOf<List<SongItem>?>(null) }
    val appContext = LocalContext.current.applicationContext
    val localPlaylistRepo = remember(appContext) {
        moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.getInstance(appContext)
    }
    val localPlaylists by localPlaylistRepo.playlists.collectAsStateWithLifecycle()
    val favoriteSongs = remember(localPlaylists, appContext) {
        localPlaylists.firstOrNull {
            moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
                .isSystemPlaylist(it, appContext)
        }?.songs.orEmpty()
    }
    val favoriteLookup = remember(favoriteSongs) {
        moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup(favoriteSongs)
    }
    val scope = rememberCoroutineScope()

    fun toggle(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    val selectedSongs = songs.filter { it.id in selectedIds }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {            TopAppBar(
                title = {
                    Text(
                        text = if (selectionMode) "已选 ${selectedIds.size} 首" else title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectionMode) exitSelection() else onBack()
                        }
                    ) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "退出多选" else "返回"
                        )
                    }
                },
                actions = {
                    if (selectionMode) {                        IconButton(
                            onClick = {
                                selectedSongs.forEach {
                                    GlobalDownloadManager.startDownload(context, it)
                                }
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "下载所选")
                        }
                        IconButton(
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    if (onAddToPlaylist != null) {
                                        onAddToPlaylist(selectedSongs)
                                    } else {
                                        addDialogSongs = selectedSongs
                                    }
                                }
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "添加到歌单所选"
                            )
                        }
                        IconButton(
                            onClick = {
                                selectedSongs.forEach { PlayerManager.addToQueueEnd(it) }
                                exitSelection()
                            },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "加入队列")
                        }
                        IconButton(
                            onClick = {
                                if (selectedIds.size == songs.size) {
                                    selectedIds = emptySet()
                                } else {
                                    selectedIds = songs.mapTo(mutableSetOf()) { it.id }
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = "全选")
                        }
                    } else if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "换一批")
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                error != null -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    ErrorBlock(error = error, onRetry = { onRetry?.invoke() })
                }

                songs.isEmpty() -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    EmptyBlock(text = "暂无歌曲")
                }

                else -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = subtitle ?: "共 ${songs.size} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { PlayerManager.playPlaylist(songs, 0) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("播放全部")
                            }
                            FilledTonalButton(onClick = { PlayerManager.playPlaylist(songs.shuffled(), 0) }) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("随机")
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (onAddToPlaylist != null) {
                                        onAddToPlaylist(songs)
                                    } else {
                                        addDialogSongs = songs
                                    }
                                },
                                enabled = songs.isNotEmpty()
                            ) {
                                Text("添加到歌单")
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp + miniPlayerHeight
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                            val selected = song.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (selectionMode) {
                                                toggle(song.id)
                                            } else {
                                                PlayerManager.playPlaylist(songs, index)
                                            }
                                        },
                                        onLongClick = {
                                            if (!selectionMode) selectionMode = true
                                            toggle(song.id)
                                        }
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { toggle(song.id) },
                                        modifier = Modifier.width(28.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp)
                                    )
                                }
                                AsyncImage(
                                    model = song.coverUrl,
                                    contentDescription = song.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(44.dp).clip(neriRoundedShape(8.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = song.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = formatDuration(song.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val isFavorite = favoriteLookup.contains(song)
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (isFavorite) {
                                                localPlaylistRepo.removeFromFavorites(song)
                                            } else {
                                                localPlaylistRepo.addToFavorites(song)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isFavorite) {
                                            Icons.Filled.Favorite
                                        } else {
                                            Icons.Outlined.FavoriteBorder
                                        },
                                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                                        tint = if (isFavorite) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addDialogSongs != null) {
        AddSongsToPlaylistDialog(
            songs = addDialogSongs.orEmpty(),
            onDismiss = { addDialogSongs = null }
        )
    }
}
