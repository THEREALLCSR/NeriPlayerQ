package moe.ouom.neriplayer.core.player.media

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
 * File: moe.ouom.neriplayer.core.player.media/PlaybackMediaItemFactorySourceTest
 */

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现"同一首歌不同功能判定不一致"的播放侧。
 *
 * 播放侧的 album 兜底写的是 `startsWith("QQMusic")`（大小写敏感），
 * 而评论侧写的是 `startsWith("QQMusic", ignoreCase = true)`。
 * 导入数据若把前缀写成小写 `qqmusic`，评论能识别、播放不能——
 * 播放会把它当网易云歌，缓存键与 URL 解析都错。
 *
 * 这两条测试在当前代码上必须是红的；统一到 `sourceOf` 后转绿。
 */
class PlaybackMediaItemFactorySourceTest {

    private fun legacyQqSong(album: String, channelId: String? = null) = SongItem(
        id = 1L,
        name = "旧数据歌曲",
        artist = "歌手",
        album = album,
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        channelId = channelId,
    )

    @Test
    fun `lowercase QQ album tag is recognized case insensitively`() {
        assertTrue(
            "评论侧对 album 前缀忽略大小写，播放侧也必须忽略；" +
                "否则小写的 qqmusic 前缀会被当成网易云歌",
            PlaybackMediaItemFactory.isQqSource(legacyQqSong("qqmusic|003bL7Wv1fXeVi")),
        )
    }

    @Test
    fun `uppercase channel id is normalized before matching`() {
        assertTrue(
            "channelId 应先 trim + lowercase 再匹配；" +
                "写成 QQMUSIC 的歌在评论侧能识别，播放侧不能",
            PlaybackMediaItemFactory.isQqSource(legacyQqSong("某专辑", channelId = "QQMUSIC")),
        )
    }

    @Test
    fun `channel id with surrounding spaces is normalized before matching`() {
        assertTrue(
            "channelId 两侧的空格应被忽略；判定规则只允许存在一处",
            PlaybackMediaItemFactory.isQqSource(legacyQqSong("某专辑", channelId = " qqmusic ")),
        )
    }
}
