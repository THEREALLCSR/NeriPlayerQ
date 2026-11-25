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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicSearchRawProbeTest
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
 * 第 028 次迭代红灯：搜索接口原始字段诊断探针。
 *
 * 背景（用户线索 + 真机铁证）：
 * - 用户反馈"搜索界面直接搜索专辑能显示专辑数量"——真机点探针「搜索专辑」按钮，
 *   searchAlbums 输出的 JSON 里 songCount=1（非0真值），证明搜索接口
 *   `DoSearchForQQMusicDesktop` 的专辑项带歌曲数真值；
 * - 而歌手页专辑 tab 的接口 `GetAlbumList` 专辑项只返回 totalNum=0；
 * - 播放量同理：歌单列表 `v_playlist` 的 play_cnt 真值 0，但搜索歌单接口
 *   `DoSearchForQQMusicDesktop`（search_type=3）可能带播放量真值，从未验证。
 *
 * 上一轮探针的盲点：`callSingerAlbumsRawAndCopy` 的 relatedFields 用 hints
 * (play/album/mid/name/cnt/total) 过滤字段，songNum 等字段被丢弃；完整字段列表
 * 排在 JSON 末尾被 lastJsonPreview 的 4000 字符截断，从未读到。
 *
 * 本测试要求新增 raw 探针：原样返回搜索组首项的完整 JSON（不解析、不过滤），
 * 覆盖搜索专辑与搜索歌单两类，真机点按钮即可一锤定音字段真值。
 *
 * 编译期红灯：searchGroupRaw 不存在。
 */
class QQMusicSearchRawProbeTest {

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

    /** 搜索专辑 raw：须原样返回 body.album.list 首项全部字段（含未知字段与 songNum/song_count 真值）。 */
    @Test
    fun `searchGroupRaw returns untouched album item with all fields`() = runTest {
        server.enqueue(envelope("""{
            "body":{"album":{"list":[
              {"albumMID":"m1","albumName":"测试专辑","song_count":12,"songNum":99,"unknown_field":"KEEP_ME"}
            ]}}
        }"""))

        val raw = repository.searchGroupRaw("keyword", searchType = 2, groupKey = "album")

        assertTrue("须返回首项完整 JSON", raw.toString().contains("albumMID"))
        assertTrue("须保留 song_count 真值", raw.toString().contains("12"))
        assertTrue("须保留 songNum 等未知字段（真机诊断关键）", raw.toString().contains("songNum"))
        assertTrue("须保留完全未知字段", raw.toString().contains("KEEP_ME"))
    }

    /** 搜索歌单 raw：须原样返回 body.songlist.list 首项全部字段（含播放量真值）。 */
    @Test
    fun `searchGroupRaw returns untouched playlist item with play count`() = runTest {
        server.enqueue(envelope("""{
            "body":{"songlist":{"list":[
              {"dissid":"701","dissname":"热门歌单","play_cnt":88888,"listennum":777,"new_unknown":42}
            ]}}
        }"""))

        val raw = repository.searchGroupRaw("keyword", searchType = 3, groupKey = "songlist")

        assertTrue("须保留 play_cnt 真值", raw.toString().contains("88888"))
        assertTrue("须保留 listennum 兜底字段", raw.toString().contains("777"))
        assertTrue("须保留完全未知字段", raw.toString().contains("new_unknown"))
    }

    /** 请求须带 search_type 与 query（与 searchGroup 同 payload，便于对照真机）。 */
    @Test
    fun `searchGroupRaw sends search type and query`() = runTest {
        server.enqueue(envelope("""{"body":{"album":{"list":[]}}}"""))

        repository.searchGroupRaw("Miku", searchType = 2, groupKey = "album")

        val body = server.takeRequest().body!!.utf8()
        assertTrue("须带 query", body.contains("\"query\":\"Miku\""))
        assertTrue("须带 search_type", body.contains("\"search_type\":2"))
    }

    /** 空列表返回空 JSONObject（不崩溃、不返回其它组数据）。 */
    @Test
    fun `searchGroupRaw returns empty object on empty list`() = runTest {
        server.enqueue(envelope("""{"body":{"album":{"list":[]}}}"""))

        val raw = repository.searchGroupRaw("nothing", searchType = 2, groupKey = "album")

        assertEquals(0, raw.length())
    }

    /** 未登录不发请求。 */
    @Test
    fun `searchGroupRaw does not send request when logged out`() = runTest {
        val guestRepo = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )

        val raw = guestRepo.searchGroupRaw("x", searchType = 2, groupKey = "album")

        assertEquals(0, raw.length())
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
