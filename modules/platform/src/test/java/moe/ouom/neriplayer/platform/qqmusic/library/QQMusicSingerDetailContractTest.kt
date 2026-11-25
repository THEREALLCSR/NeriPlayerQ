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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicSingerDetailContractTest
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
 * 四音源能力对齐 · QQ 歌手简介 + 歌手专辑列表探针红灯。
 *
 * 接口证据（A 级，simple-music docs/qq-music-api.md 2026-08-24 真实请求成功）：
 * - 歌手详情：music.web_singer_info_svr / get_singer_detail_info，简介在 data.singer_brief
 * - 歌手专辑：music.musichallAlbum.AlbumListServer / GetAlbumList
 *
 * 证据边界（诚实）：module/method 与响应字段是 A 级；但 param 内字段名（singerMid）
 * 探查只到 C 级（惯例推测、未实测），故本测试**只钉 A 级部分**——module/method 与解析，
 * 不把 param 字段名固化为断言。实现按项目现有 singerSongs 的 singerMid 惯例，
 * 真机可用性（含 param 字段名）待验证，不得据本测试宣称"真机可用"。
 *
 * 编译期红灯：被测方法与模型尚不存在。
 */
class QQMusicSingerDetailContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: QQMusicLibraryRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "uin=123; qm_keyst=KEY", uin = "123", musicKey = "KEY") },
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** 歌手详情须走已验证的 web_singer_info_svr / get_singer_detail_info。 */
    @Test
    fun `singerDetail uses verified module and method`() = runTest {
        server.enqueue(envelope(detailData()))
        repository.singerDetail("singer-mid-1")
        val body = server.takeRequest().body!!.utf8()
        assertTrue("须走 web_singer_info_svr", body.contains("music.web_singer_info_svr"))
        assertTrue("须调 get_singer_detail_info", body.contains("get_singer_detail_info"))
    }

    /** 简介从 data.singer_brief 提取（A 级字段），并保留歌手名。 */
    @Test
    fun `singerDetail parses brief description`() = runTest {
        server.enqueue(envelope(detailData()))
        val detail = repository.singerDetail("singer-mid-1")
        assertEquals("singer-mid-1", detail.mid)
        assertEquals("测试歌手", detail.name)
        assertEquals("这是一段歌手简介", detail.description)
    }

    /** 歌手专辑须走 musichallAlbum.AlbumListServer / GetAlbumList。 */
    @Test
    fun `singerAlbums uses verified module and method`() = runTest {
        server.enqueue(envelope(albumsData()))
        repository.singerAlbums("singer-mid-1", offset = 0, limit = 30)
        val body = server.takeRequest().body!!.utf8()
        assertTrue("须走 AlbumListServer", body.contains("music.musichallAlbum.AlbumListServer"))
        assertTrue("须调 GetAlbumList", body.contains("GetAlbumList"))
    }

    /** 专辑列表解析 mid/title/songCount。 */
    @Test
    fun `singerAlbums parses album list`() = runTest {
        server.enqueue(envelope(albumsData()))
        val albums = repository.singerAlbums("singer-mid-1", offset = 0, limit = 30)
        assertEquals(2, albums.size)
        assertEquals("alb-mid-1", albums[0].mid)
        assertEquals("专辑一", albums[0].title)
    }

    /** 空数据返回空，不崩溃。 */
    @Test
    fun `singerDetail returns blank description on empty data`() = runTest {
        server.enqueue(envelope("""{}"""))
        val detail = repository.singerDetail("singer-mid-1")
        assertEquals("", detail.description)
    }

    private fun envelope(data: String) = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":$data}}""")
        .build()

    private fun detailData() = """
        {"singer_mid":"singer-mid-1","singer_name":"测试歌手","singer_brief":"这是一段歌手简介"}
    """.trimIndent()

    private fun albumsData() = """
        {"albums":[
          {"albumMID":"alb-mid-1","albumName":"专辑一","song_count":10,"singerName":"测试歌手"},
          {"albumMID":"alb-mid-2","albumName":"专辑二","song_count":8,"singerName":"测试歌手"}
        ]}
    """.trimIndent()
}
