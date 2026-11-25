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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicPlaylistPlayCountContractTest
 */

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 第 032 次迭代红灯：歌单播放量补数契约（解析 `dirinfo.listennum`）。
 *
 * 真机铁证（第 031 次探针，2026-11-25，dissid=7011264340）：
 *   dirinfo.listennum = 146608（14.6万播放）
 *   dirinfo.songnum   = 76
 *   dirinfoPlayRelated = {"listennum":146608,"ordernum":0,"songnum":76,"song_update_num":0}
 * —— 播放量不在 data 顶层，而在 `dirinfo` 对象里，字段名是 **listennum**。
 * 而媒体库歌单列表 v_playlist 的 play_cnt 恒 0（字段存在但不补数）。
 *
 * 设计要求（本次红灯钉住）：
 * - 走 `CgiGetDiss`，只取 `song_num = 1`（拿头部 dirinfo，不拉整页歌曲）；
 * - 解析 `data.dirinfo.listennum`，> 0 才返回，0 / 缺失 / 无 dirinfo 返回 null
 *   （不把"未核实"伪造成 0，与第 029/030 次同一原则）；
 * - 未登录 / 非数字 dissid 返回 null 且不发请求。
 *
 * 编译期红灯：playlistPlayCount 不存在。
 */
class QQMusicPlaylistPlayCountContractTest {

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

    /** dirinfo.listennum 是真值：须返回 146608（真机同款）。 */
    @Test
    fun `playlistPlayCount reads listennum from dirinfo`() = runTest {
        server.enqueue(
            envelope("""{"total_song_num":76,"dirinfo":{"id":7011264340,"title":"电子","listennum":146608,"songnum":76},"songlist":[]}""")
        )

        val count = repository.playlistPlayCount("7011264340")

        assertEquals(146608L, count)
    }

    /** 只请求 1 首（song_num=1），不为拿播放量拉整页歌曲。 */
    @Test
    fun `playlistPlayCount requests only one song`() = runTest {
        server.enqueue(envelope("""{"dirinfo":{"listennum":146608},"songlist":[]}"""))

        repository.playlistPlayCount("7011264340")

        val body = server.takeRequest().body!!.utf8()
        assertTrue("须带数字 disstid", body.contains("\"disstid\":7011264340"))
        assertTrue("须只取 1 首（song_num=1）", body.contains("\"song_num\":1"))
    }

    /** listennum=0 是"未核实"：返回 null 而不是伪造 0。 */
    @Test
    fun `playlistPlayCount returns null on zero`() = runTest {
        server.enqueue(envelope("""{"dirinfo":{"listennum":0},"songlist":[]}"""))

        assertNull(repository.playlistPlayCount("7011264340"))
    }

    /** 缺 dirinfo：返回 null，不崩溃。 */
    @Test
    fun `playlistPlayCount returns null when dirinfo absent`() = runTest {
        server.enqueue(envelope("""{"total_song_num":76,"songlist":[]}"""))

        assertNull(repository.playlistPlayCount("7011264340"))
    }

    /** 非数字 dissid：返回 null 且不发请求。 */
    @Test
    fun `playlistPlayCount returns null on non numeric dissid`() = runTest {
        assertNull(repository.playlistPlayCount("not-a-number"))
        assertEquals(0, server.requestCount)
    }

    /** 未登录：返回 null 且不发请求。 */
    @Test
    fun `playlistPlayCount does not send request when logged out`() = runTest {
        val guestRepo = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )

        assertNull(guestRepo.playlistPlayCount("7011264340"))
        assertEquals(0, server.requestCount)
    }

    private fun envelope(data: String) = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":$data}}""")
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
