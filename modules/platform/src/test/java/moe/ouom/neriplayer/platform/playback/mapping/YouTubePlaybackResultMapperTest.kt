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
 * File: moe.ouom.neriplayer.platform.playback.mapping/YouTubePlaybackResultMapperTest
 */

import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackResultMapperTest {

    @Test
    fun `stream preserves url mime and duration`() {
        val audio = YouTubePlayableAudio(
            url = "https://cdn.example/youtube.m4a",
            durationMs = 210_000L,
            mimeType = "audio/mp4",
        )
        val result = YouTubePlaybackResultMapper.map(YouTubePlaybackResult.Stream(audio))

        assertTrue(result is PlatformPlaybackResult.Success)
        val success = result as PlatformPlaybackResult.Success
        assertEquals(audio.url, success.url)
        assertEquals("audio/mp4", success.mimeType)
        assertEquals(210_000L, success.durationMs)
        assertNull(success.expiresAtEpochMs)
    }

    @Test
    fun `no stream maps to unavailable not transport`() {
        val result = YouTubePlaybackResultMapper.map(YouTubePlaybackResult.NoStream)
        assertTrue(result is PlatformPlaybackResult.Unavailable)
    }

    @Test
    fun `login required keeps its own semantics`() {
        val result = YouTubePlaybackResultMapper.map(YouTubePlaybackResult.LoginRequired)
        assertTrue(result is PlatformPlaybackResult.LoginRequired)
    }

    @Test
    fun `transport failure keeps reason`() {
        val result = YouTubePlaybackResultMapper.map(YouTubePlaybackResult.Transport("DNS 解析失败"))
        assertTrue(result is PlatformPlaybackResult.Transport)
        assertEquals("DNS 解析失败", (result as PlatformPlaybackResult.Transport).reason)
    }
}
