package moe.ouom.neriplayer.platform.qqmusic.library

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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicPlaylistPlayCountParsingTest
 */

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 第 015 次迭代红灯：QQ 歌单播放量全 0 的数据层根因。
 *
 * 现象：QQ 歌单页播放量全部显示 0，UI 层 render 正常（formatPlayCount(playlist.playCount)），
 * 导航 JSON 也原样传参——问题在 platform 层解析。
 *
 * 根因证据（QM-Download v0.6 真实账号验证实现，QQMusicLibraryRepository.kt:77-80）：
 * 播放量优先取 `play_cnt`，`listennum` 仅作兜底。本项目 userPlaylists(:138) 与
 * searchPlaylists(:478) 均只认 `listennum`——服务端只回 `play_cnt` 时解析为 0。
 *
 * 本测试用 MockWebServer 喂入只含 play_cnt 的歌单，断言解析出正确播放量；
 * 同时钉住 listennum 兜底与 play_cnt 优先，防止字段顺序回归。
 */
class QQMusicPlaylistPlayCountParsingTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: QQMusicLibraryRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { session() },
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** userPlaylists：服务端只回 play_cnt 时必须解析出播放量（当前实现红：读 listennum 得 0）。 */
    @Test
    fun `userPlaylists reads play_cnt when listennum absent`() = runTest {
        server.enqueue(playlistEnvelope("""{"v_playlist":[{"dirId":10,"dirName":"我的歌单","songNum":3,"play_cnt":123456}]}"""))

        val playlists = repository.userPlaylists()

        assertEquals(1, playlists.size)
        assertEquals("只回 play_cnt 时应解析出播放量，不能是 0", 123456L, playlists[0].playCount)
    }

    /** userPlaylists：play_cnt 优先于 listennum（两者都在时用 play_cnt）。 */
    @Test
    fun `userPlaylists prefers play_cnt over listennum`() = runTest {
        server.enqueue(playlistEnvelope("""{"v_playlist":[{"dirId":11,"dirName":"混合字段","play_cnt":999,"listennum":888}]}"""))

        val playlists = repository.userPlaylists()

        assertEquals(999L, playlists[0].playCount)
    }

    /** userPlaylists：无 play_cnt 时 listennum 兜底（防止修复时丢掉旧字段兼容）。 */
    @Test
    fun `userPlaylists falls back to listennum`() = runTest {
        server.enqueue(playlistEnvelope("""{"v_playlist":[{"dirId":12,"dirName":"旧字段","listennum":777}]}"""))

        val playlists = repository.userPlaylists()

        assertEquals(777L, playlists[0].playCount)
    }

    /** searchPlaylists：同样要优先读 play_cnt（搜索结果卡片也显示播放量）。 */
    @Test
    fun `searchPlaylists reads play_cnt`() = runTest {
        server.enqueue(searchEnvelope("""{"dissid":"abc","dissname":"搜索到的歌单","song_count":5,"play_cnt":54321}"""))

        val playlists = repository.searchPlaylists("关键词", 0)

        assertEquals(1, playlists.size)
        assertEquals("搜索结果也要解析 play_cnt", 54321L, playlists[0].playCount)
    }

    private fun playlistEnvelope(data: String) = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":$data}}""")
        .build()

    private fun searchEnvelope(item: String) = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":{"body":{"songlist":{"list":[$item]}}}}}""")
        .build()

    private fun session() = QQMusicSession(
        cookie = "uin=123; qm_keyst=KEY",
        uin = "123",
        musicKey = "KEY",
        authst = "AUTHST",
        musicId = "123",
        wid = "WID",
        sid = "SID",
    )
}
