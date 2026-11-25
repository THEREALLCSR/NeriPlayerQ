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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicSingerAlbumsSongCountFieldTest
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
 * 第 027 次迭代红灯：按真机铁证修正歌手专辑项的"歌曲数"字段。
 *
 * 真机取证（第 026 次 raw 探针，2026-11-24，关键词 Miku）：
 *   albumsCount = 30
 *   dataAllKeys = ["singerMid","albumList","total"]
 *   专辑项真实字段 = albumMid / albumName / albumTranName / publishDate /
 *                    **totalNum** / albumType / albumPmid / albumID / singerName / tags / specIcon / pubText / trace
 *   ——专辑项里根本没有 `song_count` 字段，歌曲数是 **totalNum**。
 *
 * 缺陷：生产 `singerAlbums()` 第 701 行读 `item.optInt("song_count", 0)`——
 * 字段不存在，恒取默认 0，这就是"专辑项显示 0 首"的真正根因。
 * 第 023 次的测试 mock 沿用了第 014 次的 C 级推断字段 song_count，故未能暴露。
 *
 * 本测试用真机同款结构（专辑项只有 totalNum、无 song_count），且 totalNum 取非零值
 * 避免与默认值 0 混淆，断言 songCount 必须等于 totalNum 真值。
 * 保留 song_count 兜底，兼容第 014/023 次旧 mock 与潜在服务端差异。
 */
class QQMusicSingerAlbumsSongCountFieldTest {

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

    /** 真机字段 totalNum：专辑项无 song_count，songCount 须取 totalNum（当前读 song_count→0，红）。 */
    @Test
    fun `singerAlbums reads songCount from totalNum on real response`() = runTest {
        server.enqueue(envelope("""{
            "singerMid":"0007x1RE3c4jEk",
            "albumList":[
              {"albumMid":"real-mid-a","albumName":"真机专辑A","totalNum":12,"singerName":"初音未来"},
              {"albumMid":"real-mid-b","albumName":"真机专辑B","totalNum":8,"singerName":"初音未来"}
            ],
            "total":2
        }"""))

        val albums = repository.singerAlbums("0007x1RE3c4jEk")

        assertEquals(2, albums.size)
        assertEquals("real-mid-a", albums[0].mid)
        assertEquals("真机专辑A", albums[0].title)
        assertEquals("歌曲数须取真机字段 totalNum=12（当前读不存在的 song_count 会是 0）",
            12, albums[0].songCount)
        assertEquals(8, albums[1].songCount)
    }

    /** 旧字段 song_count 仍要兜底（第 014/023 次 mock 与潜在服务端差异），零回归。 */
    @Test
    fun `singerAlbums still falls back to legacy song_count`() = runTest {
        server.enqueue(envelope("""{
            "albumList":[{"albumMid":"legacy-1","albumName":"旧字段专辑","song_count":10}]
        }"""))

        val albums = repository.singerAlbums("singer-mid")

        assertEquals(1, albums.size)
        assertEquals(10, albums[0].songCount)
    }

    /** totalNum 优先于 song_count（两者并存时以真机 totalNum 为准）。 */
    @Test
    fun `singerAlbums prefers totalNum over song_count when both present`() = runTest {
        server.enqueue(envelope("""{
            "albumList":[{"albumMid":"both","albumName":"并存","totalNum":7,"song_count":99}]
        }"""))

        val albums = repository.singerAlbums("singer-mid")

        assertEquals(7, albums[0].songCount)
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
