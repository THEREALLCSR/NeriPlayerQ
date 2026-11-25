package moe.ouom.neriplayer.platform.qqmusic.ranking

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
 * File: moe.ouom.neriplayer.platform.qqmusic.ranking/QQMusicRankingRepositoryTest
 */

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicRanking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四音源能力对齐 · QQ 排行榜探针红灯。
 *
 * 接口证据等级 A：music.musicToplist.Toplist 的 GetAll / GetDetail 经 ylw1997/qqmusic-api、
 * W_PlatformMusicApi wiki（抓包记录）、Go github.com/guohuiyuan/music-lib/qq（可运行实现）三源交叉，
 * 公开只读接口、无需登录。详见 接手/19 号矩阵。
 *
 * 本测试用 Interceptor 钉住请求信封与响应解析（离线、CI 可跑，复用 QQMusicSearchApiTest 范式），
 * 不验证真机可用性。编译期红灯：被测对象尚不存在。
 */
class QQMusicRankingRepositoryTest {

    private val requests = CopyOnWriteArrayList<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body) = replies.removeFirst()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** GetAll：解析榜单列表，topId/title 一一对应。 */
    @Test
    fun `rankings parses toplist groups into flat rankings`() = runTest {
        replies += 200 to getAllFixture()
        val rankings = QQMusicRankingRepository(client).rankings()
        assertTrue("应解析出榜单", rankings.isNotEmpty())
        assertEquals(2, rankings.size)
        assertEquals(26L, rankings[0].id)
        assertEquals("热歌榜", rankings[0].title)
        assertEquals(62L, rankings[1].id)
    }

    /** GetAll 必须走 music.musicToplist.Toplist / GetAll，而不是杜撰的方法名。 */
    @Test
    fun `rankings sends correct GetAll envelope`() = runTest {
        replies += 200 to getAllFixture()
        QQMusicRankingRepository(client).rankings()
        assertEquals(1, requests.size)
        assertTrue("须请求 musicu.fcg", requests.first().url.toString().contains("musicu.fcg"))
        val body = run {
            val buffer = okio.Buffer()
            requests.first().body!!.writeTo(buffer)
            buffer.readUtf8()
        }
        val req = JSONObject(body).optJSONObject("req_0")
        assertEquals("music.musicToplist.Toplist", req?.optString("module"))
        assertEquals("GetAll", req?.optString("method"))
    }

    /** GetDetail：按 topId/offset/num 拉某榜歌曲，解析 mid/name。 */
    @Test
    fun `rankingSongs parses song list from GetDetail`() = runTest {
        replies += 200 to getDetailFixture()
        val songs = QQMusicRankingRepository(client).rankingSongs(topId = 26, offset = 0, num = 30)
        assertTrue("应解析出歌曲", songs.isNotEmpty())
        assertEquals("mid-1", songs[0].songMid)
        assertEquals("歌曲一", songs[0].title)
    }

    /** GetDetail 的参数须带 topId/offset/num（测试钉住请求组装）。 */
    @Test
    fun `rankingSongs sends topId offset and num`() = runTest {
        replies += 200 to getDetailFixture()
        QQMusicRankingRepository(client).rankingSongs(topId = 26, offset = 10, num = 20)
        val buffer = okio.Buffer()
        requests.first().body!!.writeTo(buffer)
        val param = JSONObject(buffer.readUtf8())
            .optJSONObject("req_0")?.optJSONObject("param")
        assertEquals(26, param?.optInt("topId"))
        assertEquals(10, param?.optInt("offset"))
        assertEquals(20, param?.optInt("num"))
    }

    /** 业务码非 0 不得被当成空榜单成功返回，须抛类型化异常供上层处理。 */
    @Test
    fun `rankings throws on rejected business code`() = runTest {
        replies += 200 to """{"req_0":{"code":1000,"msg":"expired"}}"""
        val error = try {
            QQMusicRankingRepository(client).rankings()
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("业务码非 0 须抛异常，实际=$error", error != null)
    }

    /** 空数据应返回空列表，不崩溃。 */
    @Test
    fun `rankings returns empty on missing data`() = runTest {
        replies += 200 to """{"req_0":{"code":0,"data":{}}}"""
        assertTrue(QQMusicRankingRepository(client).rankings().isEmpty())
    }

    private fun getAllFixture() = """
        {"req_0":{"code":0,"data":{"group":[
          {"groupName":"巅峰榜","toplist":[
            {"topId":26,"title":"热歌榜","headPicUrl":"https://img/26.png"},
            {"topId":62,"title":"飙升榜","headPicUrl":"https://img/62.png"}
          ]}
        ]}}}
    """.trimIndent()

    private fun getDetailFixture() = """
        {"req_0":{"code":0,"data":{"topId":26,"title":"热歌榜","songInfoList":[
          {"mid":"mid-1","name":"歌曲一","singer":[{"name":"歌手A"}],"album":{"name":"专辑"},"interval":200}
        ]}}}
    """.trimIndent()
}
