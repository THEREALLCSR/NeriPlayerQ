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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicPlaylistRawProbeTest
 */

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 诊断探针红灯：userPlaylistsRaw() 返回歌单列表接口的原始 data（未解析），
 * 用于在真机上确认播放量字段的真实名称。
 *
 * 背景：第 015 次按 B 级证据改了 play_cnt/listennum 解析，离线 4/4 绿，
 * 但真机装机后媒体库歌单播放量仍全 0。证据不足（无真实账号抓包），
 * 需拿原始 JSON 一锤定音——现有探针只复制解析后模型，看不到原始字段。
 *
 * 本测试钉住：raw 探针返回 req_0.data 原样（含 v_playlist 全部字段），
 * 未登录不发请求。编译期红灯：userPlaylistsRaw 不存在。
 */
class QQMusicPlaylistRawProbeTest {

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

    /** 原始探针须原样返回 req_0.data，保留 v_playlist 里的所有字段（含播放量真值）。 */
    @Test
    fun `userPlaylistsRaw returns untouched req_0 data`() = runTest {
        val raw = """{"v_playlist":[{"dirId":10,"dirName":"测试","unknown_play_field":12345,"play_cnt":0}]}"""
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"req_0":{"code":0,"data":$raw}}""")
                .build()
        )

        val data = repository.userPlaylistsRaw()

        assertTrue("须返回原始 data", data.toString().contains("v_playlist"))
        assertTrue("须保留未解析的未知字段（真机诊断关键）", data.toString().contains("unknown_play_field"))
        assertEquals(12345L, data.optJSONArray("v_playlist")!!.optJSONObject(0)!!.optLong("unknown_play_field"))
    }

    /** 未登录不发请求（与其它 userPlaylists 系列一致）。 */
    @Test
    fun `userPlaylistsRaw does not send request when logged out`() = runTest {
        val guestRepo = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )
        val data = guestRepo.userPlaylistsRaw()

        assertEquals(0, data.length())
        assertEquals(0, server.requestCount)
    }

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
