package moe.ouom.neriplayer.data.model.music

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
 * File: moe.ouom.neriplayer.data.model.music/MusicSourceResolverTest
 */

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MusicSources.sourceOf] 的契约。
 *
 * 改造前这四套规则散在四个模块里，彼此不一致；这里一次性锁住优先级与归一化行为，
 * 让"播放/下载/评论/歌单对话框共用同一个答案"成为可验证的事实。
 */
class MusicSourceResolverTest {

    private fun song(channelId: String? = null, album: String = "某专辑") = SongItem(
        id = 1L,
        name = "测试歌曲",
        artist = "歌手",
        album = album,
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        channelId = channelId,
    )

    @Test
    fun `channel id wins over album tag when both are present`() {
        assertEquals(
            MusicSource.BILIBILI,
            MusicSources.sourceOf(song(channelId = "bilibili", album = "QQMusic|xxx")),
        )
    }

    @Test
    fun `channel id is trimmed and lowercased`() {
        assertEquals(MusicSource.QQ_MUSIC, MusicSources.sourceOf(song(channelId = "  QQMUSIC  ")))
        assertEquals(MusicSource.CLOUD_MUSIC, MusicSources.sourceOf(song(channelId = " Netease ")))
    }

    @Test
    fun `youtube music channel id is matched after normalization`() {
        assertEquals(MusicSource.YOUTUBE_MUSIC, MusicSources.sourceOf(song(channelId = "youtubeMusic")))
    }

    @Test
    fun `legacy album tag works without channel id`() {
        assertEquals(MusicSource.QQ_MUSIC, MusicSources.sourceOf(song(album = "QQMusic|003bL7Wv1fXeVi")))
        assertEquals(MusicSource.BILIBILI, MusicSources.sourceOf(song(album = "Bilibili|BV1xx")))
    }

    @Test
    fun `legacy album tag is case insensitive`() {
        assertEquals(MusicSource.QQ_MUSIC, MusicSources.sourceOf(song(album = "qqmusic|003bL7Wv1fXeVi")))
        assertEquals(MusicSource.CLOUD_MUSIC, MusicSources.sourceOf(song(album = "netease|123")))
    }

    @Test
    fun `unknown channel id falls back to album tag`() {
        assertEquals(
            MusicSource.QQ_MUSIC,
            MusicSources.sourceOf(song(channelId = "some-future-platform", album = "QQMusic|xxx")),
        )
    }

    @Test
    fun `unknown source returns null`() {
        assertNull(MusicSources.sourceOf(song()))
        assertNull(MusicSources.sourceOf(song(channelId = "some-future-platform")))
    }

    @Test
    fun `qq predicate agrees with sourceOf`() {
        val legacy = song(album = "QQMusic|xxx")
        assertEquals(MusicSources.sourceOf(legacy) == MusicSource.QQ_MUSIC, MusicSources.isQqMusic(legacy))
    }
}
