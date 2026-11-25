package moe.ouom.neriplayer.ui.screen.tab.home

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
 * File: moe.ouom.neriplayer.ui.screen.tab.home/HomeQqFeed
 */

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.artist.ArtistSongRow
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup
import moe.ouom.neriplayer.platform.qqmusic.track.toSongItem
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

/** 首页刷新按钮触发 QQ 推荐重新拉取。 */
object QqHomeFeedRefreshBus {
    private val _token = MutableStateFlow(0)
    val token: StateFlow<Int> = _token
    fun request() {
        _token.value += 1
    }
}

/**
 * 首页 QQ 音乐推荐数据。
 *
 * 读取"首页栏目"里两张卡片各自的音源设置：
 * - 私人雷达卡：qqmusic 时用 `personalRadarTracks()`
 * - 推荐/每日卡：qqmusic 时用 `dailyRecommendSongs()`
 */
class QqHomeFeedViewModel(app: Application) : AndroidViewModel(app) {
    data class State(
        val radarEnabled: Boolean = false,
        val dailyEnabled: Boolean = false,
        val radarSongs: List<SongItem> = emptyList(),
        val dailySongs: List<SongItem> = emptyList(),
        val loading: Boolean = false,
        val loginRequired: Boolean = false,
        val error: String? = null
    )

    private val _ui = MutableStateFlow(State())
    val ui: StateFlow<State> = _ui.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        // 设置变化（开关 / 音源）后自动重算；首页刷新按钮通过 Bus 触发重新拉取
        viewModelScope.launch {
            combine(
                AppContainer.settingsRepo.homeCardRadarFlow,
                AppContainer.settingsRepo.homeCardRecommendedFlow,
                AppContainer.settingsRepo.homeCardRadarSourceFlow,
                AppContainer.settingsRepo.homeCardRecommendedSourceFlow
            ) { radarOn, dailyOn, radarSource, dailySource ->
                val radarWanted = radarOn && radarSource == "qqmusic"
                val dailyWanted = dailyOn && dailySource == "qqmusic"
                _ui.value = _ui.value.copy(
                    radarEnabled = radarWanted,
                    dailyEnabled = dailyWanted
                )
                if (radarWanted || dailyWanted) {
                    load()
                } else {
                    _ui.value = _ui.value.copy(
                        radarSongs = emptyList(),
                        dailySongs = emptyList(),
                        loading = false
                    )
                }
            }.collect { }
        }
        viewModelScope.launch {
            QqHomeFeedRefreshBus.token.drop(1).collect { load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val settings = AppContainer.settingsRepo
            val radarEnabled = runCatching { settings.homeCardRadarFlow.first() }.getOrDefault(false)
            val dailyEnabled = runCatching { settings.homeCardRecommendedFlow.first() }.getOrDefault(false)
            val radarSource = runCatching { settings.homeCardRadarSourceFlow.first() }.getOrDefault("netease")
            val dailySource = runCatching { settings.homeCardRecommendedSourceFlow.first() }.getOrDefault("netease")
            val radarWanted = radarEnabled && radarSource == "qqmusic"
            val dailyWanted = dailyEnabled && dailySource == "qqmusic"
            _ui.value = _ui.value.copy(
                radarEnabled = radarWanted,
                dailyEnabled = dailyWanted,
                loading = radarWanted || dailyWanted,
                error = null
            )
            if (!radarWanted && !dailyWanted) {
                _ui.value = _ui.value.copy(radarSongs = emptyList(), dailySongs = emptyList())
                return@launch
            }
            val loggedIn = runCatching {
                AppContainer.qqMusicSessionStore.read().isLoggedIn
            }.getOrDefault(false)
            if (!loggedIn) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    loginRequired = true,
                    radarSongs = emptyList(),
                    dailySongs = emptyList()
                )
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val repo = AppContainer.qqMusicLibraryRepository
                    val radar = if (radarWanted) {
                        // 私人雷达每次只回 5 首且批次不同：聚合 3 批并去重。
                        // 单批失败不拖垮整体，至少拿到一批就更新（"换一批"必须可见地生效）。
                        val merged = LinkedHashMap<String, moe.ouom.neriplayer.platform.qqmusic.library.QQMusicTrack>()
                        var failures = 0
                        repeat(3) {
                            runCatching { repo.personalRadarTracks() }
                                .onSuccess { batch ->
                                    batch.forEach { track -> merged.putIfAbsent(track.songMid, track) }
                                }
                                .onFailure { failures += 1 }
                        }
                        if (merged.isEmpty() && failures > 0) {
                            error("私人雷达加载失败，请稍后重试")
                        }
                        merged.values.map { it.toSongItem() }
                    } else {
                        emptyList()
                    }
                    val daily = if (dailyWanted) {
                        repo.dailyRecommendSongs().map { it.toSongItem() }
                    } else {
                        emptyList()
                    }
                    radar to daily
                }
            }.onSuccess { (radar, daily) ->
                _ui.value = _ui.value.copy(
                    radarSongs = radar,
                    dailySongs = daily,
                    loading = false,
                    loginRequired = false,
                    error = null
                )
            }.onFailure { failure ->
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = failure.message ?: "加载 QQ 音乐推荐失败"
                )
            }
        }
    }
}

/** 首页 QQ 推荐区：仅在对应卡片的音源选择 QQ 音乐且开关打开时显示。 */
@Composable
internal fun QqHomeFeedSection(
    modifier: Modifier = Modifier,
    offlineMode: Boolean = false,
    onOpenQqDailySongs: () -> Unit = {},
    onOpenQqRadarSongs: () -> Unit = {},
    favoriteSongLookup: FavoriteSongLookup,
    onFavoriteToggle: (SongItem, Boolean) -> Unit = { _, _ -> },
    onShowSnackbar: (String) -> Unit = {}
) {
    val vm: QqHomeFeedViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!ui.radarEnabled && !ui.dailyEnabled) return
    if (offlineMode) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (ui.radarEnabled) {
            QqSongShelf(
                title = "私人雷达 · QQ 音乐",
                icon = Icons.Outlined.Radar,
                songs = ui.radarSongs,
                loading = ui.loading,
                loginRequired = ui.loginRequired,
                error = ui.error,
                onRefresh = vm::load,
                onViewAll = onOpenQqRadarSongs,
                offlineMode = offlineMode,
                favoriteSongLookup = favoriteSongLookup,
                onFavoriteToggle = onFavoriteToggle,
                onShowSnackbar = onShowSnackbar,
                onSongClick = { songs, index ->
                    PlayerManager.playPlaylist(songs, index)
                }
            )
        }
        if (ui.dailyEnabled) {
            Spacer(Modifier.size(8.dp))
            QqSongShelf(
                title = "每日推荐 · QQ 音乐",
                icon = Icons.Outlined.Explore,
                songs = ui.dailySongs,
                loading = ui.loading,
                loginRequired = ui.loginRequired,
                error = ui.error,
                onViewAll = onOpenQqDailySongs,
                offlineMode = offlineMode,
                favoriteSongLookup = favoriteSongLookup,
                onFavoriteToggle = onFavoriteToggle,
                onShowSnackbar = onShowSnackbar,
                onSongClick = { songs, index ->
                    PlayerManager.playPlaylist(songs, index)
                }
            )
        }
    }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SongCollectionSheet(
    title: String,
    songs: List<SongItem>,
    onDismiss: () -> Unit,
    favoriteSongLookup: FavoriteSongLookup,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "$title（${songs.size} 首）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                    ArtistSongRow(
                        index = index + 1,
                        song = song,
                        onClick = { PlayerManager.playPlaylist(songs, index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QqSongShelf(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    songs: List<SongItem>,
    loading: Boolean,
    loginRequired: Boolean = false,
    error: String?,
    onRefresh: (() -> Unit)? = null,
    onViewAll: (() -> Unit)? = null,
    favoriteSongLookup: FavoriteSongLookup,
    onFavoriteToggle: (SongItem, Boolean) -> Unit,
    onShowSnackbar: (String) -> Unit,
    offlineMode: Boolean,
    onSongClick: (List<SongItem>, Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 与网易云分区标题完全一致（SectionHeader：headlineSmall + ExtraBold）
            Box(Modifier.weight(1f)) {
                SectionHeader(icon = icon, title = title)
            }
            if (onRefresh != null) {
                androidx.compose.material3.TextButton(onClick = onRefresh) {
                    Text("换一批")
                }
            }
            if (onViewAll != null) {
                androidx.compose.material3.TextButton(onClick = onViewAll) {
                    Text("查看全部")
                }
            }
        }
        when {
            loginRequired && songs.isEmpty() -> Text(
                text = "QQ 音乐未登录，请先在 设置 → 账号 中登录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            loading && songs.isEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }

            error != null && songs.isEmpty() -> Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            songs.isEmpty() -> Text(
                text = "暂无 QQ 音乐推荐（请确认已登录 QQ 音乐）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            else -> ResponsiveSongPagerList(
                songs = songs,
                onSongClick = onSongClick,
                favoriteSongLookup = favoriteSongLookup,
                onFavoriteToggle = onFavoriteToggle,
                onShowSnackbar = onShowSnackbar,
                offlineMode = offlineMode
            )
        }
    }
}
