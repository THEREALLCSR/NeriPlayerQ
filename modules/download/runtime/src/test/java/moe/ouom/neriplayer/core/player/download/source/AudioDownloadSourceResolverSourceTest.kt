package moe.ouom.neriplayer.core.player.download.source

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
 * File: moe.ouom.neriplayer.core.player.download.source/AudioDownloadSourceResolverSourceTest
 */

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现"同一首歌不同功能判定不一致"的下载侧。
 *
 * 旧数据 / 导入数据没有 `channelId`，来源只写在 `album` 前缀里（`SongSourceTags.QQ_MUSIC`）。
 * 播放侧与评论侧都有这个兜底，下载侧只有 `channelId == "qqmusic"` 一条规则。
 *
 * 后果不只是"少个 Referer"：`AudioDownloadManagerAttempt` 用这个判定选解析器，
 * 答"不是 QQ"就走 `else → resolveNetease`，QQ 歌被当成网易云歌解析。
 *
 * 这两条测试在当前代码上必须是红的；统一到 `sourceOf` 后转绿。
 */
class AudioDownloadSourceResolverSourceTest {

    private fun legacyQqSong(album: String) = SongItem(
        id = 1L,
        name = "旧数据歌曲",
        artist = "歌手",
        album = album,
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
    )

    @Test
    fun `legacy QQ song with album tag is recognized as QQ`() {
        assertTrue(
            "旧数据只有 album 前缀 QQMusic、没有 channelId；" +
                "下载判定必须与播放侧一致，否则会被交给网易云解析器",
            AudioDownloadSourceResolver.isQqSource(legacyQqSong("QQMusic|003bL7Wv1fXeVi")),
        )
    }

    @Test
    fun `lowercase QQ album tag is recognized case insensitively`() {
        assertTrue(
            "评论侧的 album 前缀兜底忽略大小写，下载侧也必须忽略；" +
                "否则同一首歌评论能用、下载却走错解析器",
            AudioDownloadSourceResolver.isQqSource(legacyQqSong("qqmusic|003bL7Wv1fXeVi")),
        )
    }

    @Test
    fun `uppercase channel id is normalized before matching`() {
        val song = legacyQqSong("某专辑").copy(channelId = "QQMUSIC")
        assertTrue(
            "channelId 的大小写不应影响来源判定；评论侧已 trim+lowercase，其它侧也要一致",
            AudioDownloadSourceResolver.isQqSource(song),
        )
    }
}
