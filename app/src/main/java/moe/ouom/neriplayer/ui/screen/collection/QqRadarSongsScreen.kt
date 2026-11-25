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
 * File: moe.ouom.neriplayer.ui.screen.collection/QqRadarSongsScreen
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicTrack
import moe.ouom.neriplayer.platform.qqmusic.track.toSongItem

/**
 * QQ 音乐私人雷达整页：聚合 3 批并去重（每次接口只回 5 首），
 * 顶栏提供"换一批"，页面本身复用 [SongCollectionScreen]。
 */
@Composable
internal fun QqRadarSongsScreen(onBack: () -> Unit) {
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val repo = AppContainer.qqMusicLibraryRepository
                val merged = LinkedHashMap<String, QQMusicTrack>()
                var failures = 0
                repeat(3) {
                    runCatching { repo.personalRadarTracks() }
                        .onSuccess { batch ->
                            batch.forEach { merged.putIfAbsent(it.songMid, it) }
                        }
                        .onFailure { failures += 1 }
                }
                if (merged.isEmpty() && failures > 0) {
                    error("私人雷达加载失败，请稍后重试")
                }
                merged.values.map { it.toSongItem() }
            }
        }.onSuccess { result ->
            songs = result
            loading = false
        }.onFailure { failure ->
            error = failure.message ?: "加载私人雷达失败"
            loading = false
        }
    }

    SongCollectionScreen(
        title = "私人雷达",
        subtitle = if (songs.isEmpty()) null else "QQ 音乐 · ${songs.size} 首",
        songs = songs,
        loading = loading,
        error = error,
        onRetry = { reloadToken++ },
        onRefresh = { reloadToken++ },
        onBack = onBack
    )
}
