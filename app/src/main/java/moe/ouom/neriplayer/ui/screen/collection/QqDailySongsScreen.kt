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
 * File: moe.ouom.neriplayer.ui.screen.collection/QqDailySongsScreen
 */

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.track.toSongItem

/**
 * QQ 音乐每日推荐整页：数据来自 `dailyRecommendSongs()`（dirid=202，30 首），
 * 页面本身复用 [SongCollectionScreen]。
 */
@Composable
internal fun QqDailySongsScreen(onBack: () -> Unit) {
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        runCatching {
            AppContainer.qqMusicLibraryRepository.dailyRecommendSongs()
                .map { it.toSongItem() }
        }.onSuccess { result ->
            songs = result
            loading = false
        }.onFailure { failure ->
            error = failure.message ?: "加载每日推荐失败"
            loading = false
        }
    }

    SongCollectionScreen(
        title = "每日推荐",
        subtitle = if (songs.isEmpty()) null else "QQ 音乐 · ${songs.size} 首",
        songs = songs,
        loading = loading,
        error = error,
        onRetry = { reloadToken++ },
        onBack = onBack
    )
}
