package moe.ouom.neriplayer.platform.playback.mapping

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
 * File: moe.ouom.neriplayer.platform.playback.mapping/NeteasePlaybackResultMapperTest
 */

import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.FailureReason
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.Notice
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.PlaybackResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaybackResultMapperTest {

    @Test
    fun `requires login maps to login required`() {
        val result = NeteasePlaybackResultMapper.map(PlaybackResult.RequiresLogin)
        assertTrue(result is PlatformPlaybackResult.LoginRequired)
    }

    @Test
    fun `no permission failure maps to unavailable with reason`() {
        val result = NeteasePlaybackResultMapper.map(PlaybackResult.Failure(FailureReason.NO_PERMISSION))
        assertTrue(result is PlatformPlaybackResult.Unavailable)
        assertTrue((result as PlatformPlaybackResult.Unavailable).reason.contains("NO_PERMISSION"))
    }

    @Test
    fun `success preserves url mime and preview notice`() {
        val result = NeteasePlaybackResultMapper.map(
            PlaybackResult.Success(
                url = "https://cdn.example/netease.mp3",
                type = "mp3",
                notice = Notice.PREVIEW_CLIP,
                durationMs = 30_000L,
            )
        )
        assertTrue(result is PlatformPlaybackResult.Success)
        val success = result as PlatformPlaybackResult.Success
        assertEquals("https://cdn.example/netease.mp3", success.url)
        assertEquals("mp3", success.mimeType)
        assertEquals(30_000L, success.durationMs)
        assertEquals("试听片段", success.noticeMessage)
    }
}
