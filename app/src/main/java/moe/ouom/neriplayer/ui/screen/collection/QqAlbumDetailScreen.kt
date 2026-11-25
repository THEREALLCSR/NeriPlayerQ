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
 * File: moe.ouom.neriplayer.ui.screen.collection/QqAlbumDetailScreen
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
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicAlbum
import moe.ouom.neriplayer.platform.qqmusic.track.toSongItem

/**
 * QQ 音乐专辑详情页：复用通用 [SongCollectionScreen]。
 *
 * 曲目通过 `albumTracksPage` 拉取（第一页 100 首足够覆盖常见专辑）。
 */
@Composable
internal fun QqAlbumDetailScreen(
    album: QQMusicAlbum,
    onBack: () -> Unit
) {
    var songs by remember(album.mid) { mutableStateOf<List<SongItem>>(emptyList()) }
    var loading by remember(album.mid) { mutableStateOf(true) }
    var error by remember(album.mid) { mutableStateOf<String?>(null) }
    var reloadToken by remember(album.mid) { mutableIntStateOf(0) }

    LaunchedEffect(album.mid, reloadToken) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                AppContainer.qqMusicLibraryRepository.albumTracksPage(
                    albumMid = album.mid,
                    begin = 0,
                    limit = 100
                ).tracks.map { it.toSongItem() }
            }
        }.onSuccess { result ->
            songs = result
            loading = false
        }.onFailure { failure ->
            error = failure.message ?: "加载专辑失败"
            loading = false
        }
    }

    val subtitle = listOfNotNull(
        album.singer.takeIf { it.isNotBlank() },
        album.publishDate?.takeIf { it.isNotBlank() },
        if (songs.isNotEmpty()) "${songs.size} 首" else null
    ).joinToString(" · ")

    SongCollectionScreen(
        title = album.title,
        subtitle = subtitle.ifBlank { null },
        songs = songs,
        loading = loading,
        error = error,
        onRetry = { reloadToken++ },
        onBack = onBack
    )
}
