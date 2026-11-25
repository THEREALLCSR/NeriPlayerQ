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
 * File: moe.ouom.neriplayer.platform.playback.mapping/BiliPlaybackResultMapperTest
 */

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliPlaybackResultMapperTest {

    @Test
    fun `stream preserves url candidates and mime type`() {
        val stream = BiliAudioStreamInfo(
            id = 30280,
            mimeType = "audio/mp4",
            bitrateKbps = 192,
            qualityTag = null,
            url = "https://cdn.example/primary.m4s",
            candidateUrls = listOf("https://cdn.example/primary.m4s", "https://cdn.example/backup.m4s"),
        )
        val result = BiliPlaybackResultMapper.map(BiliPlaybackResult.Stream(stream))

        assertTrue(result is PlatformPlaybackResult.Success)
        val success = result as PlatformPlaybackResult.Success
        assertEquals(stream.url, success.url)
        assertEquals(stream.candidateUrls, success.candidateUrls)
        assertEquals("audio/mp4", success.mimeType)
    }

    @Test
    fun `no stream maps to unavailable not transport`() {
        val result = BiliPlaybackResultMapper.map(BiliPlaybackResult.NoStream)
        assertTrue(result is PlatformPlaybackResult.Unavailable)
    }

    @Test
    fun `login required keeps its own semantics`() {
        val result = BiliPlaybackResultMapper.map(BiliPlaybackResult.LoginRequired)
        assertTrue(result is PlatformPlaybackResult.LoginRequired)
    }

    @Test
    fun `transport failure keeps reason`() {
        val result = BiliPlaybackResultMapper.map(BiliPlaybackResult.Transport("连接超时"))
        assertTrue(result is PlatformPlaybackResult.Transport)
        assertEquals("连接超时", (result as PlatformPlaybackResult.Transport).reason)
    }
}
