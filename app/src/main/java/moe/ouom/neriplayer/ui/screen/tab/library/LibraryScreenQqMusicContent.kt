package moe.ouom.neriplayer.ui.screen.tab.library

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
 * File: moe.ouom.neriplayer.ui.screen.tab.library/LibraryScreenQqMusicContent
 */

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicPlaylist
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/**
 * 我的歌单过滤（与 `filterNeteasePlaylists` 对齐）：只在本地按标题过滤。
 */
internal fun filterQqPlaylists(
    playlists: List<QQMusicPlaylist>,
    query: String
): List<QQMusicPlaylist> {
    val normalized = query.trim()
    if (normalized.isBlank()) return playlists
    return playlists.filter { playlist ->
        playlist.title.contains(normalized, ignoreCase = true)
    }
}

/**
 * QQ 音乐歌单行。
 *
 * 刻意与 `NeteasePlaylistRow` 保持完全一致的结构：
 * `Card + ListItem + 56dp 封面 + 播放量/歌曲数副标题 + 离线缓存图片请求`。
 * 平台差异只在数据来源，视觉与交互不应有差别。
 */
@Composable
internal fun QqMusicPlaylistRow(
    playlist: QQMusicPlaylist,
    cardShape: Shape,
    context: Context,
    offlineMode: Boolean,
    onClick: () -> Unit
) {
    LibraryPlaylistRow(
        title = playlist.title,
        subtitle = stringResource(
            CoreCommonR.string.home_play_count_format,
            formatPlayCount(context, playlist.playCount),
            playlist.trackCount
        ),
        coverUrl = playlist.coverUrl,
        cardShape = cardShape,
        context = context,
        offlineMode = offlineMode,
        onClick = onClick
    )
}
