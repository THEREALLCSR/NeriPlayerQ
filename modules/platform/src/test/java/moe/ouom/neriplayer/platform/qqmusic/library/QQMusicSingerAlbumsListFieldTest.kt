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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicSingerAlbumsListFieldTest
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
 * 第 023 次迭代红灯：按真机原始 JSON 修正歌手专辑列表字段名。
 *
 * 真机取证（第 022 次 raw 探针，2026-11-24）：
 *   dataAllKeys = ["singerMid", "albumList", "total"]
 *   dataRelatedFields = {"singerMid":"003TIqpG2DLywF", "albumList":[], "total":0}
 * 即服务端返回的列表字段是 `albumList`，而 singerAlbums() 读的是 `albums`（C 级推断错误）
 * ——永远解析为空，这就是"专辑 tab 不显示专辑"的直接根因。
 *
 * 本测试用真机同款 `albumList` 结构喂 MockWebServer，断言解析出专辑；
 * 同时保留 `albums` 兜底（兼容第 014 次 mock 与可能的服务端差异）。
 */
class QQMusicSingerAlbumsListFieldTest {

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

    /** 真机字段名 albumList：必须解析出专辑（当前读 albums，会红 expected:2 was:0）。 */
    @Test
    fun `singerAlbums parses albumList field from real server response`() = runTest {
        server.enqueue(envelope("""{
            "singerMid":"003TIqpG2DLywF",
            "albumList":[
              {"albumMID":"real-mid-1","albumName":"真机专辑一","song_count":10,"singerName":"测试歌手"},
              {"albumMID":"real-mid-2","albumName":"真机专辑二","song_count":8,"singerName":"测试歌手"}
            ],
            "total":2
        }"""))

        val albums = repository.singerAlbums("003TIqpG2DLywF")

        assertEquals(2, albums.size)
        assertEquals("real-mid-1", albums[0].mid)
        assertEquals("真机专辑一", albums[0].title)
        assertEquals(10, albums[0].songCount)
    }

    /** 旧字段名 albums 仍要兼容（第 014 次 mock 与潜在服务端差异）。 */
    @Test
    fun `singerAlbums still parses legacy albums field`() = runTest {
        server.enqueue(envelope("""{"albums":[{"albumMID":"legacy-1","albumName":"旧字段专辑"}]}"""))

        val albums = repository.singerAlbums("singer-mid")

        assertEquals(1, albums.size)
        assertEquals("legacy-1", albums[0].mid)
    }

    /** 真机空 albumList + total=0 时返回空列表（不崩溃）。 */
    @Test
    fun `singerAlbums returns empty on real empty albumList`() = runTest {
        server.enqueue(envelope("""{"singerMid":"x","albumList":[],"total":0}"""))

        val albums = repository.singerAlbums("x")

        assertEquals(0, albums.size)
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
