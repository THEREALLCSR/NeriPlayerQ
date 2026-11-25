package moe.ouom.neriplayer.ui.screen.debug

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
 * File: moe.ouom.neriplayer.ui.screen.debug/QqMusicApiProbeScreen
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Comment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.QqMusicApiProbeViewModel

/**
 * QQ 音乐接口调试页。
 *
 * 与网易云/哔哩哔哩接口调试页保持同一套布局（DebugProbeKit），
 * 覆盖目前 QQ 音源用到的接口：登录态、我的歌单、歌单详情、歌曲/歌单/歌手搜索、
 * 歌手作品、VKey 播放解析、评论列表。
 */
@Composable
fun QqMusicApiProbeScreen() {
    val vm: QqMusicApiProbeViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    DebugProbePage(
        title = "QQ 音乐接口调试",
        description = "覆盖当前 QQ 音源已接入的接口；每次调用会把原始 JSON 复制到剪贴板。"
    ) {
        Column {
            DebugProbeSection(title = "登录态") {
                Text(
                    text = ui.authSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                DebugProbeAction(
                    title = "读取登录信息",
                    icon = Icons.Rounded.AccountCircle,
                    onClick = vm::callSessionAndCopy
                )
            }

            DebugProbeSection(title = "我的歌单") {
                DebugProbeFieldRow {
                    DebugProbeField(
                        value = ui.dissid,
                        onValueChange = vm::onDissidChange,
                        label = "歌单 dissid（数字 id）",
                        numeric = true,
                        inRow = true
                    )
                }
                DebugProbeAction(
                    title = "拉取我的歌单",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callUserPlaylistsAndCopy
                )
                DebugProbeAction(
                    title = "复制歌单原始字段（诊断）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callUserPlaylistsRawAndCopy
                )
                DebugProbeAction(
                    title = "拉取歌单详情（前 20 首）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callPlaylistTracksAndCopy
                )
                DebugProbeAction(
                    title = "复制歌单详情原始字段（诊断）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callPlaylistDetailRawAndCopy
                )
            }

            DebugProbeSection(title = "搜索") {
                DebugProbeFieldRow {
                    DebugProbeField(
                        value = ui.keyword,
                        onValueChange = vm::onKeywordChange,
                        label = "关键词",
                        inRow = true
                    )
                }
                DebugProbeAction(
                    title = "搜索歌曲",
                    icon = Icons.Rounded.Search,
                    onClick = vm::callSearchSongsAndCopy
                )
                DebugProbeAction(
                    title = "搜索歌单",
                    icon = Icons.Rounded.Search,
                    onClick = vm::callSearchPlaylistsAndCopy
                )
                DebugProbeAction(
                    title = "搜索歌手",
                    icon = Icons.Rounded.Search,
                    onClick = vm::callSearchSingersAndCopy
                )
                DebugProbeAction(
                    title = "歌手作品（取第一个搜索结果）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callSingerSongsAndCopy
                )
                DebugProbeAction(
                    title = "复制歌手专辑原始字段（诊断）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callSingerAlbumsRawAndCopy
                )
                DebugProbeAction(
                    title = "搜索专辑",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callSearchAlbumsAndCopy
                )
                DebugProbeAction(
                    title = "复制搜索专辑原始字段（诊断）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callSearchAlbumRawAndCopy
                )
                DebugProbeAction(
                    title = "复制搜索歌单原始字段（诊断）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callSearchPlaylistRawAndCopy
                )
                DebugProbeFieldRow {
                    DebugProbeField(
                        value = ui.albumMid,
                        onValueChange = vm::onAlbumMidChange,
                        label = "专辑 albumMid",
                        inRow = true
                    )
                }
                DebugProbeAction(
                    title = "专辑曲目（前 20 首）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callAlbumTracksAndCopy
                )
            }

            DebugProbeSection(title = "首页推荐") {
                DebugProbeAction(
                    title = "每日推荐（dirid=202）",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callDailyRecommendAndCopy
                )
                DebugProbeAction(
                    title = "私人雷达 / 猜你喜欢",
                    icon = Icons.Rounded.QueueMusic,
                    onClick = vm::callPersonalRadarAndCopy
                )
            }

            DebugProbeSection(title = "播放与评论") {
                DebugProbeFieldRow {
                    DebugProbeField(
                        value = ui.songMid,
                        onValueChange = vm::onSongMidChange,
                        label = "songMid",
                        inRow = true
                    )
                }
                DebugProbeFieldRow {
                    DebugProbeField(
                        value = ui.commentTopId,
                        onValueChange = vm::onCommentTopIdChange,
                        label = "评论 topid（数字歌曲 id）",
                        numeric = true,
                        inRow = true
                    )
                }
                DebugProbeAction(
                    title = "解析 VKey（无损）",
                    icon = Icons.Rounded.PlayArrow,
                    onClick = vm::callVKeyAndCopy
                )
                DebugProbeAction(
                    title = "拉取评论（前 20 条）",
                    icon = Icons.Rounded.Comment,
                    onClick = vm::callCommentsAndCopy
                )
            }

            if (ui.lastMessage.isNotBlank()) {
                DebugProbeSection(title = "最近结果") {
                    Text(
                        text = ui.lastMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    if (ui.lastJsonPreview.isNotBlank()) {
                        Text(
                            text = ui.lastJsonPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
