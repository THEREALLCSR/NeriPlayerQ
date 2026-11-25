package moe.ouom.neriplayer.ui.screen.nowplaying

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/NowPlayingQqSingerNavigation
 */

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicSinger
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel

/**
 * 播放页「点歌手名 → QQ 歌手页」的解析与导航闭环，范式与
 * [NowPlayingScreen] 的 `openCurrentNeteaseArtist` 完全一致（音源判断 → VM 解析 → 导航/降级）。
 *
 * 独立成文件的原因：`NowPlayingScreen.kt` 受 `<2000` 物理行预算约束且已贴线，
 * 这段闭环放不进去；音源判定仍复用 [isQqSingerNavigationSource] 内的
 * `MusicSources.isQqMusic`，没有在别处写裸 channelId 规则。
 */
@Composable
internal fun rememberOpenCurrentQqSinger(
    currentSong: SongItem?,
    nowPlayingViewModel: NowPlayingViewModel,
    snackbarHostState: SnackbarHostState,
    onEnterQqSinger: (QQMusicSinger) -> Unit,
    onNavigateUp: () -> Unit,
): () -> Unit {
    val screenScope = rememberCoroutineScope()
    val resources = LocalResources.current
    var resolvingQqSinger by remember { mutableStateOf(false) }
    return {
        val song = currentSong
        if (song != null && isQqSingerNavigationSource(song) && !resolvingQqSinger) {
            resolvingQqSinger = true
            nowPlayingViewModel.resolveQqSinger(
                song = song,
                onResult = { singer ->
                    resolvingQqSinger = false
                    onEnterQqSinger(singer)
                    onNavigateUp()
                },
                onError = {
                    resolvingQqSinger = false
                    screenScope.launch {
                        snackbarHostState.showNeriSnackbar(
                            resources.getString(CoreCommonR.string.artist_not_available)
                        )
                    }
                }
            )
        }
    }
}
