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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicAlbumSongCountContractTest
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
 * 第 029 次迭代红灯：专辑歌曲数补数契约（用专辑详情接口的 totalNum）。
 *
 * 真机铁证（2026-11-25）：
 * - 歌手页 GetAlbumList 专辑项完整 13 字段无 songNum/song_count，totalNum 恒 0
 *   → 列表接口拿不到歌曲数（第 027 次字段修正正确但无数据）；
 * - 专辑详情接口 `music.musichallAlbum.AlbumSongList / GetAlbumSongList` 返回
 *   `{"total":49,...}` 是真值（"少女与战车"原声带 49 首），故用它逐专辑补数。
 *
 * 设计要求（本次红灯钉住）：
 * - 只取 `num = 1`，用 totalNum 拿总数，避免为拿计数拉 20 首曲目；
 * - 只有 totalNum > 0 才返回，否则返回 null —— 不把"未核实"伪造成 0
 *   （公开实现原则："未能核实的播放数不会填成 0"；此处同理）；
 * - 未登录返回 null 且不发请求。
 *
 * 编译期红灯：albumSongCount 不存在。
 */
class QQMusicAlbumSongCountContractTest {

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

    /** 详情接口 totalNum 是真值：须返回 49（真机同款）。 */
    @Test
    fun `albumSongCount returns totalNum from album detail`() = runTest {
        server.enqueue(envelope("""{"totalNum":49,"songList":[{"songInfo":{"songMid":"a"}}]}"""))

        val count = repository.albumSongCount("0024BGk34VDono")

        assertEquals(49, count)
    }

    /** 只请求 1 首（num=1 / begin=0），不为拿计数拉整张专辑。 */
    @Test
    fun `albumSongCount requests only one track`() = runTest {
        server.enqueue(envelope("""{"totalNum":49,"songList":[]}"""))

        repository.albumSongCount("0024BGk34VDono")

        val body = server.takeRequest().body!!.utf8()
        assertTrue("须带 albumMid", body.contains("\"albumMid\":\"0024BGk34VDono\""))
        assertTrue("须只取 1 首（num=1）", body.contains("\"num\":1"))
        assertTrue("须从第 0 首开始", body.contains("\"begin\":0"))
    }

    /** totalNum=0 是"未核实"：须返回 null 而不是伪造 0。 */
    @Test
    fun `albumSongCount returns null on zero total`() = runTest {
        server.enqueue(envelope("""{"totalNum":0,"songList":[]}"""))

        assertNull(repository.albumSongCount("mid-zero"))
    }

    /** 响应缺 totalNum：须返回 null（不得退化成 songList.size 之类的猜测值）。 */
    @Test
    fun `albumSongCount returns null when totalNum absent`() = runTest {
        server.enqueue(envelope("""{"songList":[{"songInfo":{"songMid":"a"}}]}"""))

        assertNull(repository.albumSongCount("mid-absent"))
    }

    /** 未登录：返回 null 且不发请求。 */
    @Test
    fun `albumSongCount does not send request when logged out`() = runTest {
        val guestRepo = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )

        assertNull(guestRepo.albumSongCount("mid-guest"))
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
