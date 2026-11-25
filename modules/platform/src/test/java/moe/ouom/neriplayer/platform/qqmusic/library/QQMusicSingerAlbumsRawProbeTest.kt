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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicSingerAlbumsRawProbeTest
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
 * 第 022 次迭代红灯：歌手专辑原始响应诊断探针。
 *
 * 背景：用户真机反馈"QQ 歌手页专辑 tab 无法正常显示专辑"。第 017 次已把
 * singerAlbums API 接进 VM→UiState→Screen，静态全绿；但 API 的 param 字段名
 * （singerMid/offset/limit）与响应字段（albums/albumMID/albumName）全是 C 级推断，
 * 第 014 次红灯的 mock 是自喂假 JSON——从未在真机证实。
 *
 * 按播放量问题的教训（不猜字段、拿原始 JSON 一锤定音），加 raw 探针原样返回
 * req_0.data，真机点调试屏按钮即可看到专辑字段真值，再决定是否修解析。
 *
 * 编译期红灯：singerAlbumsRaw 不存在。
 */
class QQMusicSingerAlbumsRawProbeTest {

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

    /** raw 探针须原样返回 req_0.data，保留未知字段（真机诊断关键）。 */
    @Test
    fun `singerAlbumsRaw returns untouched req_0 data`() = runTest {
        val raw = """{"albums":[{"albumMID":"x","unknown_field":42}],"total":99}"""
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"req_0":{"code":0,"data":$raw}}""")
                .build()
        )

        val data = repository.singerAlbumsRaw("singer-mid-1")

        assertTrue("须返回原始 data", data.toString().contains("albums"))
        assertTrue("须保留未知字段（真机诊断关键）", data.toString().contains("unknown_field"))
        assertEquals(99L, data.optLong("total"))
    }

    /** 请求须带 singerMid 参数（与 singerAlbums 同 payload，便于对照真机字段名）。 */
    @Test
    fun `singerAlbumsRaw sends singerMid param`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"req_0":{"code":0,"data":{"albums":[]}}}""")
                .build()
        )

        repository.singerAlbumsRaw("singer-mid-1")

        val body = server.takeRequest().body!!.utf8()
        assertTrue("须带 singerMid", body.contains("\"singerMid\":\"singer-mid-1\""))
    }

    /** 未登录不发请求（与其它 raw 探针一致）。 */
    @Test
    fun `singerAlbumsRaw does not send request when logged out`() = runTest {
        val guestRepo = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )

        val data = guestRepo.singerAlbumsRaw("singer-mid-1")

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
