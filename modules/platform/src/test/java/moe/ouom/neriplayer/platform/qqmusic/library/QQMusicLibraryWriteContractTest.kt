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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicLibraryWriteContractTest
 */

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicApiException
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicFailureKind
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Q4（QQ 歌单写接口契约测试）的红灯测试。
 *
 * 写接口（create/add/remove/update/delete）此前零契约覆盖——请求组装、响应解析、
 * 写句柄 dirId 的传递只能靠真机抓包。本测试用假服务器钉住最关键的一条业务链：
 *
 * 新建歌单时，服务端返回的 `data.result.dirId` 是后续加歌/删歌/改名/删除**必需的写句柄**
 * （QQMusicPlaylist.dirId；各写函数在 dirId<=0 时抛"请刷新歌单列表"）。
 * 如果 createPlaylist 没有把 dirId 带回来，用户刚建完歌单想立刻加歌就会失败。
 *
 * 本测试先锁定"createPlaylist 必须回传 dirId"这一行为契约（当前实现会红），
 * 同时钉住请求信封的 module/method/dirName，防止请求组装回归。
 */
class QQMusicLibraryWriteContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: QQMusicLibraryRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { writableSession() },
            baseUrl = server.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** 新建歌单必须把服务端的 dirId（写句柄）回传到模型，否则后续写操作全部失败。 */
    @Test
    fun `createPlaylist carries server dirId into the returned playlist`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {"req_0":{"code":0,"data":{"result":{
                      "tid":12345,"dirId":678,"dirName":"测试歌单"
                    }}}}
                    """.trimIndent()
                )
                .build()
        )

        val playlist = repository.createPlaylist("测试歌单")

        assertEquals("12345", playlist.id)
        assertEquals("测试歌单", playlist.title)
        assertEquals(
            "createPlaylist 必须回传 data.result.dirId 作为写句柄；否则紧接着的加歌会抛“请刷新歌单列表”",
            678L,
            playlist.dirId,
        )
    }

    /** 请求信封必须指向 AddPlaylist，且把歌单名放进 dirName。 */
    @Test
    fun `createPlaylist sends AddPlaylist envelope with dirName`() = runTest {
        server.enqueue(successEnvelope())
        repository.createPlaylist("我的新歌单")

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertTrue("请求须带 AddPlaylist 方法", body.contains("AddPlaylist"))
        assertTrue("请求须带 PlaylistBaseWrite 模块", body.contains("music.musicasset.PlaylistBaseWrite"))
        assertTrue("请求须把歌单名放进 dirName", body.contains("我的新歌单"))
    }

    /** 写操作“已完成”码（80081/80083）属于成功，不应被当成错误，且要正确解析出 playlist。 */
    @Test
    fun `createPlaylist accepts write completed codes as success`() = runTest {
        for (code in listOf(80081, 80083)) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """
                        {"req_0":{"code":$code,"data":{"result":{
                          "tid":12345,"dirId":678,"dirName":"测试歌单"
                        }}}}
                        """.trimIndent()
                    )
                    .build()
            )

            val playlist = repository.createPlaylist("测试歌单")

            assertEquals("12345", playlist.id)
            assertEquals(678L, playlist.dirId)
        }
    }

    /** 写接口遇到旧 Cookie（code=1000）必须归类为 SessionExpired，而不是笼统的 IOException。 */
    @Test
    fun `createPlaylist maps code 1000 to SessionExpired`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"req_0":{"code":1000,"msg":"cookie expired"}}""")
                .build()
        )

        val error = try {
            repository.createPlaylist("测试歌单")
            null
        } catch (e: QQMusicApiException) {
            e
        }

        assertNotNull("code=1000 应抛 QQMusicApiException", error)
        assertEquals(QQMusicFailureKind.SessionExpired, error!!.kind)
        assertEquals(1000, error.code)
    }

    /** 未登录时应在发请求前直接抛 IOException，不浪费一次网络调用。 */
    @Test
    fun `createPlaylist without login throws before sending request`() = runTest {
        repository = QQMusicLibraryRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { QQMusicSession(cookie = "", uin = "", musicKey = "") },
            baseUrl = server.url("/").toString(),
        )

        val error = try {
            repository.createPlaylist("测试歌单")
            null
        } catch (e: IOException) {
            e
        }

        assertNotNull("未登录应抛 IOException", error)
        assertEquals("未登录不应发请求", 0, server.requestCount)
    }

    /** 加歌先用详情接口拿数字 songId/songType，再把它们与 dirId 一起塞进 AddSonglist。 */
    @Test
    fun `addSongsToPlaylist sends numeric song identity and dirId`() = runTest {
        server.enqueue(songDetailEnvelope(id = 999L, type = 5))
        server.enqueue(writeOkEnvelope())

        val playlist = QQMusicPlaylist(
            id = "12345",
            title = "测试歌单",
            coverUrl = null,
            trackCount = 0,
            dirId = 678L,
        )

        val added = repository.addSongsToPlaylist(playlist, listOf("songmid-1"))

        assertEquals(1, added)
        val detail = server.takeRequest()
        assertTrue("先查歌曲详情拿数字 id/type", detail.body!!.utf8().contains("get_song_detail_yqq"))
        val write = server.takeRequest()
        val body = write.body!!.utf8()
        assertTrue("写请求须指向 AddSonglist", body.contains("AddSonglist"))
        assertTrue("写请求须带 dirId=678", body.contains("\"dirId\":678"))
        assertTrue("v_songInfo 须带数字 songId", body.contains("\"songId\":999"))
        assertTrue("v_songInfo 须带数字 songType", body.contains("\"songType\":5"))
    }

    /**
     * 迭代 7 红灯：批量加歌超过单批上限时不得静默丢弃。
     *
     * 调用方（收藏歌单 QqMusicPlaylistDetailScreen）会把整页/全选歌曲（可达 100 首）
     * 一次性传入，而 addSongsToPlaylist 内部 `take(20)` 静默截断——第 21 首起凭空消失，
     * 返回值与 toast 却按 20 报。这里钉住"传入 25 首必须处理 25 首"。
     */
    @Test
    fun `addSongsToPlaylist does not silently drop songs past the batch limit`() = runTest {
        val mids = (1..25).map { "songmid-$it" }
        mids.forEachIndexed { index, _ ->
            server.enqueue(songDetailEnvelope(id = 100L + index, type = 5))
        }
        repeat(2) { server.enqueue(writeOkEnvelope()) } // 为分批写请求预留

        val playlist = QQMusicPlaylist(
            id = "12345", title = "测试歌单", coverUrl = null, trackCount = 0, dirId = 678L,
        )
        val added = repository.addSongsToPlaylist(playlist, mids)

        assertEquals("25 首不得静默截断为 20 首（收藏歌单会丢歌）", 25, added)
        // 25 次详情查询 + 至少 1 次写请求；当前实现只有 20 次详情 + 1 次写 = 21。
        assertTrue(
            "须为 25 首都查详情（当前 take(20) 只查 20 首），实际请求数=${server.requestCount}",
            server.requestCount >= 26,
        )
    }

    /** 删除歌单同样必须用 dirId（写句柄），且指向 DelPlaylist。 */
    @Test
    fun `deletePlaylist sends DelPlaylist with correct dirId`() = runTest {
        server.enqueue(writeOkEnvelope())

        repository.deletePlaylist(
            QQMusicPlaylist(
                id = "12345",
                title = "测试歌单",
                coverUrl = null,
                trackCount = 0,
                dirId = 678L,
            )
        )

        val body = server.takeRequest().body!!.utf8()
        assertTrue("删除须指向 DelPlaylist", body.contains("DelPlaylist"))
        assertTrue("删除须带 dirId=678", body.contains("\"dirId\":678"))
    }

    private fun songDetailEnvelope(id: Long, type: Int) = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":{"track_info":{"id":$id,"type":$type}}}}""")
        .build()

    private fun writeOkEnvelope() = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":{}}}""")
        .build()

    private fun successEnvelope() = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":{"result":{"tid":12345,"dirId":678,"dirName":"x"}}}}""")
        .build()

    private fun writableSession() = QQMusicSession(
        cookie = "uin=123; qm_keyst=KEY",
        uin = "123",
        musicKey = "KEY",
        authst = "AUTHST",
        musicId = "123",
        wid = "WID",
        sid = "SID",
    )
}
