package moe.ouom.neriplayer.platform.qqmusic.network

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
 * File: moe.ouom.neriplayer.platform.qqmusic.network/QQMusicRequestHeadersTest
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ CDN 请求头的取值与域名判定。
 *
 * 这两件事此前分散在下载侧（硬编码 Referer）与播放侧（完全没有），
 * 一旦 CDN 政策收紧就会"下载正常、播放 403"，且找不到集中修改点。
 */
class QQMusicRequestHeadersTest {

    @Test
    fun `known qq music cdn hosts are recognized`() {
        listOf(
            "isure.stream.qqmusic.qq.com",
            "dl.stream.qqmusic.qq.com",
            "ws6.stream.qqmusic.qq.com",
            "QQMUSIC.QQ.COM",
        ).forEach { host ->
            assertTrue("host=$host", isQqMusicCdnHost(host))
        }
    }

    @Test
    fun `lookalike and unrelated hosts are rejected`() {
        listOf(
            "y.qq.com",
            "music.163.com",
            "notqqmusic.qq.com.evil.test",
            "qqmusic.qq.com.evil.test",
            "",
            null,
        ).forEach { host ->
            assertFalse("host=$host", isQqMusicCdnHost(host))
        }
    }

    @Test
    fun `referer is forced while an existing user agent is preserved`() {
        val headers = mutableMapOf("User-Agent" to "CustomDownloader/1.0")

        QQMusicRequestHeaders.applyTo(headers)

        assertEquals(QQMusicRequestHeaders.REFERER, headers["Referer"])
        assertEquals("CustomDownloader/1.0", headers["User-Agent"])
    }

    @Test
    fun `user agent is filled in when absent`() {
        val headers = mutableMapOf<String, String>()

        QQMusicRequestHeaders.applyTo(headers)

        assertEquals(QQMusicRequestHeaders.USER_AGENT, headers["User-Agent"])
        assertEquals("https://y.qq.com/", headers["Referer"])
    }
}
