package moe.ouom.neriplayer.ui.screen.artist

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
 * File: moe.ouom.neriplayer.ui.screen.artist/QqMusicSingerDetailScreen
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicAlbum
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicSinger
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicTrack
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.collection.AddSongsToPlaylistDialog
import moe.ouom.neriplayer.platform.qqmusic.track.toSongItem
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.artist.QqSingerDetailViewModel

/**
 * QQ 音乐歌手详情页。
 *
 * 结构与网易云/哔哩哔哩/YouTube 的创作者页保持一致：
 * - 平板/宽屏用 [CreatorDetailAdaptiveLayout] 分栏（左资料、右作品）；
 * - 手机单列滚动；
 * - 歌曲行复用 [ArtistSongRow]，加载/错误/空态复用 [ErrorBlock]/[EmptyBlock]；
 * - 数据流由 [QqSingerDetailViewModel] 统一管理（加载、分页、重试、提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QqMusicSingerDetailScreen(
    singer: QQMusicSinger,
    onBack: () -> Unit,
    onAlbumClick: (QQMusicAlbum) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: QqSingerDetailViewModel = viewModel(
        key = "qq_singer_detail_view_model_${singer.mid}"
    )
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val isTabletDevice = LocalConfiguration.current.smallestScreenWidthDp >= 600

    // 歌曲行"更多操作"菜单（仿 QQ 歌单页 menuTrack 范式）
    var menuTrack by remember(singer.mid) { mutableStateOf<QQMusicTrack?>(null) }
    var songsToAdd by remember(singer.mid) { mutableStateOf<List<QQMusicTrack>?>(null) }

    LaunchedEffect(singer.mid) {
        selectedTab = 0
        viewModel.start(singer)
    }

    LaunchedEffect(ui.tracks.size, ui.total, ui.loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (!ui.loading && ui.tracks.size < ui.total && lastVisible >= ui.tracks.size - 4) {
                    viewModel.loadMore()
                }
            }
    }

    val playAll: () -> Unit = {
        val songs = ui.tracks.map { it.toSongItem() }
        if (songs.isNotEmpty()) PlayerManager.playPlaylist(songs, 0)
    }
    val shuffleAll: () -> Unit = {
        val songs = ui.tracks.map { it.toSongItem() }.shuffled()
        if (songs.isNotEmpty()) PlayerManager.playPlaylist(songs, 0)
    }
    val downloadAll: () -> Unit = {
        ui.tracks.forEach { GlobalDownloadManager.startDownload(context, it.toSongItem()) }
        viewModel.setMessage("已加入下载队列（${ui.tracks.size} 首）")
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：复用 Material3 TopAppBar，自带 statusBars 内边距（与网易云/哔哩哔哩一致）
            TopAppBar(
                title = {
                    Text(
                        text = singer.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            CreatorDetailAdaptiveLayout(
                tabletDevice = isTabletDevice,
                miniPlayerHeight = miniPlayerHeight,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                profile = {
                    QqSingerProfileCard(
                        singer = singer,
                        description = ui.description,
                        total = ui.total,
                        message = ui.message,
                        actionsEnabled = ui.tracks.isNotEmpty(),
                        onPlayAll = playAll,
                        onShuffleAll = shuffleAll,
                        onDownloadAll = downloadAll
                    )
                }
            ) { splitLayout ->
                when {
                    ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    ui.error != null -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        ErrorBlock(error = ui.error.orEmpty(), onRetry = viewModel::retry)
                    }

                    ui.tracks.isEmpty() -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        EmptyBlock(text = "没有找到这位歌手的作品")
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = if (splitLayout) 8.dp else 0.dp,
                            bottom = 24.dp + if (splitLayout) 0.dp else miniPlayerHeight
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (!splitLayout) {
                            item(key = "qq_singer_profile") {
                                QqSingerProfileCard(
                                    singer = singer,
                                    description = ui.description,
                                    total = ui.total,
                                    message = ui.message,
                                    actionsEnabled = ui.tracks.isNotEmpty(),
                                    onPlayAll = playAll,
                                    onShuffleAll = shuffleAll,
                                    onDownloadAll = downloadAll
                                )
                            }
                        }
                        item(key = "qq_singer_work_tabs") {
                            TabRow(
                                selectedTabIndex = selectedTab,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Tab(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    text = { Text(stringResource(CoreCommonR.string.artist_tab_songs)) },
                                    icon = { Icon(Icons.Outlined.MusicNote, contentDescription = null) }
                                )
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    text = { Text(stringResource(CoreCommonR.string.artist_tab_albums)) },
                                    icon = { Icon(Icons.Outlined.LibraryMusic, contentDescription = null) }
                                )
                            }
                        }
                        if (selectedTab == 0) {
                            itemsIndexed(
                                ui.tracks,
                                key = { index, track -> "$index-${track.songMid}" }
                            ) { index, track ->
                                Box {
                                    ArtistSongRow(
                                        index = index + 1,
                                        song = track.toSongItem(),
                                        onClick = {
                                            val songs = ui.tracks.map { it.toSongItem() }
                                            PlayerManager.playPlaylist(
                                                songs,
                                                index.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
                                            )
                                        },
                                        onMore = { menuTrack = track }
                                    )
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
                                        DropdownMenuItem(
                                            text = { Text("下载") },
                                            onClick = {
                                                GlobalDownloadManager.startDownload(context, track.toSongItem())
                                                viewModel.setMessage("已加入下载队列：${track.title}")
                                                menuTrack = null
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(
                                ui.albums,
                                key = { _, album -> album.id }
                            ) { index, album ->
                                // albums 与 qqAlbums 由同一原始列表按序生成，按 index 取回含 mid 的原对象跳转；
                                // 越界时静默忽略，且 mid 为空由导航层 navigateToQqMusicAlbum 兜底。
                                ArtistAlbumRow(
                                    album = album,
                                    onClick = { ui.qqAlbums.getOrNull(index)?.let(onAlbumClick) }
                                )
                            }
                            if (ui.albums.isEmpty()) {
                                item(key = "qq_singer_albums_empty") {
                                    EmptyBlock(text = "暂无专辑")
                                }
                            }
                        }
                        if (ui.loadingMore) {
                            item(key = "qq_singer_loading_more") {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 添加到歌单：复用通用 AddSongsToPlaylistDialog（平台无关 SongItem API）──
    val pendingSongs = songsToAdd
    if (pendingSongs != null) {
        AddSongsToPlaylistDialog(
            songs = pendingSongs.map { it.toSongItem() },
            onDismiss = { songsToAdd = null },
            onResult = { message -> viewModel.setMessage(message) }
        )
    }
}

/**
 * 歌手资料卡：头像 + 名字 + 作品数 + 简介 + 操作按钮。
 *
 * 对应网易云的 `NeteaseArtistProfileCard`，简介样式参照其 ArtistBriefDescription（maxLines 收敛）。
 */
@Composable
private fun QqSingerProfileCard(
    singer: QQMusicSinger,
    description: String,
    total: Int,
    message: String?,
    actionsEnabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onDownloadAll: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = singer.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp).clip(neriRoundedShape(24.dp))
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = singer.name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append(if (total > 0) "$total 首作品" else "QQ 音乐歌手")
                append(" · QQ 音乐")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (description.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlayAll, enabled = actionsEnabled) { Text("播放全部") }
            FilledTonalButton(onClick = onShuffleAll, enabled = actionsEnabled) { Text("随机") }
        }
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(onClick = onDownloadAll, enabled = actionsEnabled) { Text("下载全部") }
        message?.let { text ->
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
