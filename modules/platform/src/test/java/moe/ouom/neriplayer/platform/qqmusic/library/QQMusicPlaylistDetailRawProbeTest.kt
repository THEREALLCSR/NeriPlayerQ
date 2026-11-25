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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicPlaylistDetailRawProbeTest
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
 * 第 030 次迭代红灯：歌单详情原始字段诊断探针（找播放量真值）。
 *
 * 背景（真机铁证，2026-11-25）：
 * - 媒体库"我的歌单"走 `userPlaylists`（v_playlist），`play_cnt` 字段存在但恒 0；
 * - 搜索歌单接口原始项有 `listennum` 真值（783.9万），但搜索结果≠我的歌单；
 * - 公开资料（YAQMC/qqmusic-provider、dylan121322/qqmusic-decrypt 等）确认
 *   `music.srfDissInfo.DissInfo / CgiGetDiss` 是歌单详情标准接口，但**播放量字段名
 *   （visitnum / listennum / listen_num）未有一手证据**——按 D6 不猜字段。
 *
 * 本探针与 `playlistTracksPage` 同 payload（disstid/dirid/tag/userinfo/orderlist），
 * 但 song_num 只取 1（不为拿头部字段拉整页歌曲），拿到 req_0.data 后**原样返回**，
 * 保留未知字段，真机点调试屏按钮即可一锤定音播放量字段名与真值。
 *
 * 编译期红灯：playlistDetailRaw 不存在。
 */
class QQMusicPlaylistDetailRawProbeTest {

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

    /** 须原样返回 CgiGetDiss 的 data：保留 visitnum/listennum 等未知字段（真机诊断关键）。 */
    @Test
    fun `playlistDetailRaw returns untouched data with all fields`() = runTest {
        val raw = """{"disstid":7011264340,"dissname":"热门歌单","visitnum":1234567,
            "listennum":7654321,"unknown_field":"KEEP_ME","songlist":[]}"""
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"req_0":{"code":0,"data":$raw}}""")
                .build()
        )

        val data = repository.playlistDetailRaw("7011264340")

        assertTrue("须返回原始 data", data.toString().contains("热门歌单"))
        assertTrue("须保留 visitnum（候选播放量字段）", data.toString().contains("1234567"))
        assertTrue("须保留 listennum（候选播放量字段）", data.toString().contains("7654321"))
        assertTrue("须保留完全未知字段", data.toString().contains("KEEP_ME"))
    }

    /** 只取 1 首（song_num=1），不为拿头部字段拉整页歌曲。 */
    @Test
    fun `playlistDetailRaw requests only one song`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"req_0":{"code":0,"data":{"disstid":7011264340,"songlist":[]}}}""")
                .build()
        )

        repository.playlistDetailRaw("7011264340")

        val body = server.takeRequest().body!!.utf8()
        assertTrue("须带数字 disstid", body.contains("\"disstid\":7011264340"))
        assertTrue("须只取 1 首（song_num=1）", body.contains("\"song_num\":1"))
    }

    /** 非数字 dissid 直接返回空 JSONObject（与 playlistTracksPage 一致，不发请求）。 */
    @Test
    fun `playlistDetailRaw returns empty on non numeric dissid`() = runTest {
        val data = repository.playlistDetailRaw("not-a-number")

        assertEquals(0, data.length())
        assertEquals(0, server.requestCount)
    }

    /** 未登录不发请求。 */
    @Test
    fun `playlistDetailRaw does not send request when logged out`() = runTest {
        val guestRepo = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )

        assertEquals(0, guestRepo.playlistDetailRaw("7011264340").length())
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
