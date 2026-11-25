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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicLikedSongsContractTest
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
 * 四音源能力对齐 · QQ「我喜欢」列表读取探针红灯。
 *
 * 接口证据等级 B+（多开源项目一致）：music.srfDissInfo.DissInfo / CgiGetDiss，
 * 关键参数 `disstid=0, dirid=201`——201 是 QQ「我喜欢」的系统目录 ID
 * （ylw1997/qqmusic-api docs/apis/get-my-favorite.md + MeloX "directory 201 for favorites"）。
 * 与本项目既有 CgiGetDiss（歌单详情，dirid=0）同构，可复用解析。
 *
 * 本测试用 MockWebServer 钉住请求参数与响应解析（离线、CI 可跑），
 * 真机可用性（登录态下 dirid=201 今天是否仍返回喜欢列表）待验证。
 * 编译期红灯：被测方法 likedSongs() 尚不存在。
 */
class QQMusicLikedSongsContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: QQMusicLibraryRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { loggedInSession() },
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** 喜欢列表必须用 dirid=201 / disstid=0，而不是普通歌单的 dirid=0。 */
    @Test
    fun `likedSongs uses the favorites directory id 201`() = runTest {
        server.enqueue(likedFixture())
        repository.likedSongs()
        val body = server.takeRequest().body!!.utf8()
        assertTrue("须走 CgiGetDiss", body.contains("CgiGetDiss"))
        assertTrue(
            "喜欢列表须用系统目录 dirid=201（普通歌单是 dirid=0）",
            body.contains("\"dirid\":201"),
        )
        assertTrue("disstid 须为 0（系统目录不走真实歌单 id）", body.contains("\"disstid\":0"))
    }

    /** 解析 data.songlist 为歌曲列表，复用既有 parseQqMusicTrack。 */
    @Test
    fun `likedSongs parses songlist into tracks`() = runTest {
        server.enqueue(likedFixture())
        val songs = repository.likedSongs()
        assertEquals(2, songs.size)
        assertEquals("fav-mid-1", songs[0].songMid)
        assertEquals("喜欢的歌一", songs[0].title)
    }

    /** 未登录时不发请求，返回空列表（与其它 QQ 只读能力一致）。 */
    @Test
    fun `likedSongs without login returns empty and sends no request`() = runTest {
        repository = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )
        val songs = repository.likedSongs()
        assertTrue(songs.isEmpty())
        assertEquals("未登录不应发请求", 0, server.requestCount)
    }

    /** 空 songlist 返回空列表，不崩溃。 */
    @Test
    fun `likedSongs returns empty on missing songlist`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"req_0":{"code":0,"data":{}}}""")
                .build()
        )
        assertTrue(repository.likedSongs().isEmpty())
    }

    private fun likedFixture() = MockResponse.Builder()
        .code(200)
        .body(
            """
            {"req_0":{"code":0,"data":{"songlist":[
              {"mid":"fav-mid-1","name":"喜欢的歌一","singer":[{"name":"歌手A"}],"album":{"name":"专辑","mid":"alb1"},"interval":180},
              {"mid":"fav-mid-2","name":"喜欢的歌二","singer":[{"name":"歌手B"}],"album":{"name":"专辑","mid":"alb2"},"interval":200}
            ],"total_song_num":2}}}
            """.trimIndent()
        )
        .build()

    private fun loggedInSession() = QQMusicSession(
        cookie = "uin=123; qm_keyst=KEY",
        uin = "123",
        musicKey = "KEY",
    )
}
