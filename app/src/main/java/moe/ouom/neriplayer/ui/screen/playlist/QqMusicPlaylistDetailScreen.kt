package moe.ouom.neriplayer.ui.screen.playlist

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
 * File: moe.ouom.neriplayer.ui.screen.playlist/QqMusicPlaylistDetailScreen
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.util.format.formatPlayCount
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicTrack
import moe.ouom.neriplayer.platform.qqmusic.track.toSongItem
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import moe.ouom.neriplayer.ui.component.playlist.PlaylistSelectionActions
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.tab.library.LibraryInlineSearchField
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.ui.viewmodel.playlist.QqPlaylistDetailViewModel

/**
 * QQ 音乐歌单详情页。
 *
 * 与网易云详情页对齐的能力：
 *   · 播放全部 / 随机播放 / 加入队列；
 *   · 单击播放，长按进入多选（多选后可删除 / 加入队列 / 添加到其它歌单）；
 *   · 行尾"更多"：下一首播放、加入队列、添加到歌单、从歌单移除；
 *   · 顶部"+"：搜索 QQ 音乐并把结果加入当前歌单。
 *
 * 分页依赖服务端返回的 total：CgiGetDiss 会过滤无版权/下架歌曲，
 * 只按"本页是否满 100"判断会提前停止（表现为只显示前 98 首）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun QqMusicPlaylistDetailScreen(
    playlist: QQMusicPlaylist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: QqPlaylistDetailViewModel = viewModel(
        key = "qq_playlist_detail_view_model_${playlist.id}"
    )
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val tracks = ui.tracks
    val total = ui.total
    val loading = ui.loading
    val error = ui.error
    val loadingMore = ui.loadingMore

    var menuTrack by remember(playlist.id) { mutableStateOf<QQMusicTrack?>(null) }
    var selectionMode by remember(playlist.id) { mutableStateOf(false) }
    var selectedMids by remember(playlist.id) { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember(playlist.id) { mutableStateOf(false) }
    var actionMessage by remember(playlist.id) { mutableStateOf<String?>(null) }
    // 歌单级管理（重命名 / 删除）
    var localTitle by remember(playlist.id) { mutableStateOf(playlist.title) }
    var showPlaylistMenu by remember(playlist.id) { mutableStateOf(false) }
    var showRenameDialog by remember(playlist.id) { mutableStateOf(false) }
    var renameText by remember(playlist.id) { mutableStateOf(playlist.title) }
    var showDeletePlaylistDialog by remember(playlist.id) { mutableStateOf(false) }

    // 评论区
    var commentTrack by remember { mutableStateOf<QQMusicTrack?>(null) }
    var comments by remember { mutableStateOf<List<moe.ouom.neriplayer.data.model.comments.SongComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var commentPage by remember { mutableIntStateOf(0) }
    var commentsHasMore by remember { mutableStateOf(false) }
    var commentsTotal by remember { mutableStateOf<Int?>(null) }
    var commentNumericId by remember { mutableStateOf<Long?>(null) }

    // 添加到其它歌单（单首或多选）
    var songsToAdd by remember { mutableStateOf<List<QQMusicTrack>?>(null) }
    var addTargets by remember { mutableStateOf<List<QQMusicPlaylist>>(emptyList()) }
    var addTargetsLoading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    // 搜索并添加歌曲到当前歌单
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SongSearchInfo>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // 歌单内搜索（与网易云详情页的搜索图标一致）
    var showTrackSearch by remember(playlist.id) { mutableStateOf(false) }
    var trackQuery by remember(playlist.id) { mutableStateOf("") }
    val visibleTracks = remember(tracks, trackQuery) {
        val normalized = trackQuery.trim()
        if (normalized.isBlank()) {
            tracks
        } else {
            tracks.filter { track ->
                track.title.contains(normalized, ignoreCase = true) ||
                    track.artist.contains(normalized, ignoreCase = true)
            }
        }
    }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val playingSongId = currentSong?.audioId

    LaunchedEffect(playlist.id) {
        viewModel.start(playlist)
    }

    // 滚动到底自动加载下一页；去重与偏移推进由 ViewModel 统一处理
    LaunchedEffect(ui.nextBegin, ui.total, ui.loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (!ui.loading && ui.tracks.size < ui.total && lastVisible >= ui.tracks.size - 4) {
                    viewModel.loadMore()
                }
            }
    }

    // 分页失败提示转成页面提示
    LaunchedEffect(ui.message) {
        ui.message?.let { message ->
            actionMessage = message
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(songsToAdd) {
        if (songsToAdd == null) return@LaunchedEffect
        addTargetsLoading = true
        addTargets = runCatching {
            AppContainer.qqMusicLibraryRepository.userPlaylists()
        }.getOrDefault(emptyList())
        addTargetsLoading = false
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶栏：与网易云/哔哩哔哩一致，TopAppBar 自带状态栏内边距 ─────────
            TopAppBar(
                title = {
                    Text(
                        text = if (selectionMode) "已选 ${selectedMids.size} 首" else localTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectionMode) {
                                selectionMode = false
                                selectedMids = emptySet()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (selectionMode) {
                                Icons.Filled.Close
                            } else {
                                Icons.AutoMirrored.Filled.ArrowBack
                            },
                            contentDescription = if (selectionMode) "退出多选" else "返回"
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                val selected = tracks.filter { it.songMid in selectedMids }
                                selected.forEach {
                                    moe.ouom.neriplayer.core.download.GlobalDownloadManager
                                        .startDownload(context, it.toSongItem())
                                }
                                actionMessage = "已加入下载队列（${selected.size} 首）"
                            },
                            enabled = selectedMids.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "下载所选")
                        }
                        IconButton(
                            onClick = { songsToAdd = tracks.filter { it.songMid in selectedMids } },
                            enabled = selectedMids.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "添加到歌单")
                        }
                        IconButton(
                            onClick = {
                                tracks.filter { it.songMid in selectedMids }
                                    .forEach { PlayerManager.addToQueueEnd(it.toSongItem()) }
                                actionMessage = "已加入队列"
                            },
                            enabled = selectedMids.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "加入队列")
                        }
                        IconButton(
                            onClick = { pendingDelete = true },
                            enabled = selectedMids.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "从歌单移除")
                        }
                        // 接入项目现成的多选组件，补上全选入口（保留上面 4 个已有操作按钮）。
                        // 该组件自带全选 CheckBox；QQ 歌单页没有"导出到本地歌单"能力，
                        // 按实际能力传 canExport=false / onExport 空实现，不虚构功能。
                        val allSelected = selectedMids.size == tracks.size && tracks.isNotEmpty()
                        PlaylistSelectionActions(
                            allSelected = allSelected,
                            onToggleSelectAll = {
                                if (allSelected) {
                                    selectedMids = emptySet()
                                } else {
                                    selectedMids = tracks.map { it.songMid }.toSet()
                                }
                            },
                            canExport = false,
                            onExport = {}
                        )
                    } else {
                        IconButton(onClick = { showTrackSearch = !showTrackSearch }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索歌曲")
                        }
                        IconButton(
                            onClick = {
                                tracks.forEach {
                                    moe.ouom.neriplayer.core.download.GlobalDownloadManager
                                        .startDownload(context, it.toSongItem())
                                }
                                actionMessage = "已加入下载队列（${tracks.size} 首）"
                            },
                            enabled = tracks.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "下载全部")
                        }
                        IconButton(onClick = { showSearchDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加歌曲")
                        }
                        if (playlist.dirId > 0L) {
                            Box {
                                IconButton(onClick = { showPlaylistMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "歌单操作")
                                }
                                DropdownMenu(
                                    expanded = showPlaylistMenu,
                                    onDismissRequest = { showPlaylistMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名歌单") },
                                        onClick = {
                                            showPlaylistMenu = false
                                            renameText = localTitle
                                            showRenameDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除歌单") },
                                        onClick = {
                                            showPlaylistMenu = false
                                            showDeletePlaylistDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            AnimatedVisibility(
                visible = showTrackSearch,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                // 复用媒体库的搜索框组件，保持与列表页一致的圆角与内边距
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    LibraryInlineSearchField(
                        query = trackQuery,
                        onQueryChange = { trackQuery = it },
                        placeholderResId = CoreCommonR.string.playlist_search_hint
                    )
                }
            }

            // ── 歌单信息：与网易云详情头部保持同一套视觉（大封面 + 标题 + 播放量）──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(112.dp).clip(neriRoundedShape(16.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = localTitle,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            CoreCommonR.string.playlist_play_count_format,
                            formatPlayCount(context, playlist.playCount),
                            if (total > 0) total else playlist.trackCount
                        ) + " · QQ 音乐",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            actionMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── 操作区 ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val songs = tracks.map { it.toSongItem() }
                        if (songs.isNotEmpty()) PlayerManager.playPlaylist(songs, 0)
                    },
                    enabled = tracks.isNotEmpty()
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("播放全部")
                }
                FilledTonalButton(
                    onClick = {
                        val songs = tracks.map { it.toSongItem() }.shuffled()
                        if (songs.isNotEmpty()) PlayerManager.playPlaylist(songs, 0)
                    },
                    enabled = tracks.isNotEmpty()
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("随机播放")
                }
                FilledTonalButton(
                    onClick = { tracks.forEach { PlayerManager.addToQueueEnd(it.toSongItem()) } },
                    enabled = tracks.isNotEmpty()
                ) { Text("加入队列") }
                if (playlist.dirId <= 0L) {
                    // 公开歌单没有写句柄（dirId）：不能直接改，但可以把歌曲收藏进自己的歌单。
                    FilledTonalButton(
                        onClick = { songsToAdd = tracks },
                        enabled = tracks.isNotEmpty()
                    ) { Text("收藏歌单") }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                error != null -> Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = { viewModel.reload() }) { Text("重试") }
                }

                tracks.isEmpty() -> Text(
                    "这个歌单里还没有歌曲",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    contentPadding = PaddingValues(bottom = 16.dp + miniPlayerHeight)
                ) {
                    itemsIndexed(visibleTracks, key = { index, track -> "$index-${track.songMid}" }) { index, track ->
                        val isPlaying = playingSongId == track.songMid
                        val selected = track.songMid in selectedMids
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedMids = toggleSelection(selectedMids, track.songMid)
                                        } else {
                                            val songs = tracks.map { it.toSongItem() }
                                            val position = tracks
                                                .indexOfFirst { it.songMid == track.songMid }
                                                .coerceIn(0, (songs.size - 1).coerceAtLeast(0))
                                            PlayerManager.playPlaylist(songs, position)
                                        }
                                    },
                                    onLongClick = {
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedMids = setOf(track.songMid)
                                        } else {
                                            selectedMids = toggleSelection(selectedMids, track.songMid)
                                        }
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectionMode) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        selectedMids = toggleSelection(selectedMids, track.songMid)
                                    },
                                    modifier = Modifier.width(28.dp)
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isPlaying) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.width(28.dp)
                                )
                            }
                            AsyncImage(
                                model = track.coverUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(44.dp).clip(neriRoundedShape(6.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isPlaying) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!selectionMode) {
                                Box {
                                    IconButton(onClick = { menuTrack = track }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                                    }
                                    DropdownMenu(
                                        expanded = menuTrack?.songMid == track.songMid,
                                        onDismissRequest = { menuTrack = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("下一首播放") },
                                            onClick = {
                                                PlayerManager.addToQueueNext(track.toSongItem())
                                                menuTrack = null
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("加入队列") },
                                            onClick = {
                                                PlayerManager.addToQueueEnd(track.toSongItem())
                                                menuTrack = null
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("添加到歌单") },
                                            onClick = {
                                                songsToAdd = listOf(track)
                                                menuTrack = null
                                            }
                                        )
                                        if (playlist.dirId > 0L) {
                                            DropdownMenuItem(
                                                text = { Text("从歌单移除") },
                                                onClick = {
                                                    menuTrack = null
                                                    selectedMids = setOf(track.songMid)
                                                    pendingDelete = true
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("下载") },
                                            onClick = {
                                                moe.ouom.neriplayer.core.download.GlobalDownloadManager
                                                    .startDownload(context, track.toSongItem())
                                                actionMessage = "已加入下载队列：${track.title}"
                                                menuTrack = null
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 移除确认 ────────────────────────────────────────────────────────────
    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("从歌单移除") },
            text = { Text("确定把选中的 ${selectedMids.size} 首歌从「${playlist.title}」移除吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = false
                        val mids = selectedMids.toList()
                        if (mids.isEmpty()) return@TextButton
                        scope.launch {
                            runCatching {
                                AppContainer.qqMusicLibraryRepository.removeSongsFromPlaylist(playlist, mids)
                            }.onSuccess { count ->
                                actionMessage = "已移除 $count 首"
                                selectionMode = false
                                selectedMids = emptySet()
                                viewModel.reload()
                            }.onFailure { failure ->
                                actionMessage = failure.message ?: "移除失败"
                            }
                        }
                    }
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("取消") }
            }
        )
    }

    // ── 重命名歌单 ──────────────────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = renameText.trim()
                        if (title.isEmpty()) {
                            actionMessage = "歌单名称不能为空"
                            return@TextButton
                        }
                        showRenameDialog = false
                        viewModel.rename(title) { renamed ->
                            localTitle = renamed
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 删除歌单 ────────────────────────────────────────────────────────────
    if (showDeletePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePlaylistDialog = false },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${localTitle}」吗？该操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        viewModel.delete { onBack() }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlaylistDialog = false }) { Text("取消") }
            }
        )
    }

    // ── 添加到其它歌单 ──────────────────────────────────────────────────────
    val pendingSongs = songsToAdd
    if (pendingSongs != null) {
        AlertDialog(
            onDismissRequest = { if (!adding) songsToAdd = null },
            title = { Text("添加到歌单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("共 ${pendingSongs.size} 首", style = MaterialTheme.typography.bodySmall)
                    when {
                        addTargetsLoading -> Box(
                            Modifier.fillMaxWidth().heightIn(min = 80.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        addTargets.isEmpty() -> Text(
                            "没有可用的 QQ 音乐歌单",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(addTargets, key = { it.id }) { target ->
                                ListItem(
                                    headlineContent = {
                                        Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = { Text("${target.trackCount} 首") },
                                    modifier = Modifier.clickable {
                                        if (adding) return@clickable
                                        adding = true
                                        scope.launch {
                                            runCatching {
                                                AppContainer.qqMusicLibraryRepository.addSongsToPlaylist(
                                                    playlist = target,
                                                    songMids = pendingSongs.map { it.songMid }
                                                )
                                            }.onSuccess { count ->
                                                actionMessage = "已添加 $count 首到「${target.title}」"
                                                songsToAdd = null
                                                if (selectionMode) {
                                                    selectionMode = false
                                                    selectedMids = emptySet()
                                                }
                                                viewModel.reload()
                                            }.onFailure { failure ->
                                                actionMessage = failure.message ?: "添加失败"
                                            }
                                            adding = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { songsToAdd = null }) { Text("关闭") }
            }
        )
    }

    // ── 搜索并添加歌曲到当前歌单 ────────────────────────────────────────────
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("添加歌曲到「${playlist.title}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchKeyword,
                            onValueChange = { searchKeyword = it },
                            label = { Text("搜索 QQ 音乐") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                if (searching || searchKeyword.isBlank()) return@TextButton
                                searching = true
                                searchError = null
                                scope.launch {
                                    runCatching {
                                        AppContainer.qqMusicSearchApi.search(searchKeyword, 1)
                                    }.onSuccess { results ->
                                        searchResults = results
                                    }.onFailure { failure ->
                                        searchError = failure.message ?: "搜索失败"
                                    }
                                    searching = false
                                }
                            }
                        ) { Text(if (searching) "搜索中…" else "搜索") }
                    }
                    searchError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    if (searchResults.isNotEmpty()) {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(searchResults, key = { it.id }) { result ->
                                ListItem(
                                    headlineContent = {
                                        Text(result.songName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(result.singer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            runCatching {
                                                AppContainer.qqMusicLibraryRepository.addSongsToPlaylist(
                                                    playlist = playlist,
                                                    songMids = listOf(result.id)
                                                )
                                            }.onSuccess {
                                                actionMessage = "已添加「${result.songName}」"
                                                showSearchDialog = false
                                                viewModel.reload()
                                            }.onFailure { failure ->
                                                searchError = failure.message ?: "添加失败"
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchDialog = false }) { Text("关闭") }
            }
        )
    }
    // ── 评论区 ──────────────────────────────────────────────────────────────
    val commentSong = commentTrack
    if (commentSong != null) {
        AlertDialog(
            onDismissRequest = { commentTrack = null },
            title = { Text("评论 · ${commentSong.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    commentsTotal?.let { count ->
                        Text("共 $count 条评论", style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        commentsLoading && comments.isEmpty() -> Box(
                            Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        commentsError != null && comments.isEmpty() -> Text(
                            commentsError.orEmpty(),
                            color = MaterialTheme.colorScheme.error
                        )

                        comments.isEmpty() -> Text(
                            "还没有评论",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(
                                comments,
                                key = { comment -> comment.id.ifBlank { comment.content.hashCode().toString() } }
                            ) { comment ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            comment.username,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            comment.content,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    trailingContent = if (comment.likeCount > 0L) {
                                        {
                                            Text(
                                                "♥ ${comment.likeCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                            if (commentsHasMore) {
                                item {
                                    TextButton(
                                        onClick = {
                                            val nextPage = commentPage + 1
                                            commentsLoading = true
                                            commentsError = null
                                            scope.launch {
                                                runCatching {
                                                    AppContainer.qqMusicCommentRepository.loadComments(
                                                        source = moe.ouom.neriplayer.data.model.comments.CommentSource(
                                                            platform = moe.ouom.neriplayer.data.model.comments.CommentPlatform.QQ_MUSIC,
                                                            resourceId = 1L,
                                                            secondaryId = commentSong.songMid
                                                        ),
                                                        page = nextPage,
                                                        pageSize = 20
                                                    )
                                                }.onSuccess { page ->
                                                    comments = comments + page.comments
                                                    commentPage = page.page
                                                    commentsHasMore = page.hasMore
                                                }.onFailure { failure ->
                                                    commentsError = failure.message ?: "加载评论失败"
                                                }
                                                commentsLoading = false
                                            }
                                        }
                                    ) { Text(if (commentsLoading) "加载中…" else "加载更多评论") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { commentTrack = null }) { Text("关闭") }
            }
        )
    }
}

private fun toggleSelection(current: Set<String>, mid: String): Set<String> =
    if (mid in current) current - mid else current + mid

/** 每次请求的条数（服务端单页上限 200，这里取 100 兼顾速度与稳定性）。 */
private const val COMMENT_PAGE_SIZE = 20

// QQ 曲目 → SongItem 的映射已下沉到 platform 层（唯一实现），
// 见 moe.ouom.neriplayer.platform.qqmusic.track.toSongItem。
