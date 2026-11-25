package moe.ouom.neriplayer.platform.qqmusic.profile

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
 * File: moe.ouom.neriplayer.platform.qqmusic.profile/QQMusicProfileRepositoryTest
 */

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicAccountProfile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四音源能力对齐 · QQ 用户资料（头像/昵称）探针红灯。
 *
 * 依据：QM-Download v0.6 的 QQMusicProfileRepository 用真实账号验证过
 * `c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg` 可返回昵称与头像；
 * 本项目 QQ 侧完全没有该能力（四音源矩阵「其它/用户资料」标 —），网易云已有 getCurrentUserAccount。
 *
 * 本测试用 OkHttp Interceptor 钉住请求端点与响应解析（离线、CI 可跑，
 * 复用 QQMusicSearchApiTest 的现成范式），不验证真机可用性——
 * 端点"今天是否仍有效"仍由 debug 探针页人工确认。
 *
 * 编译期红灯：被测对象 QQMusicProfileRepository 与 QQMusicAccountProfile 尚不存在。
 */
class QQMusicProfileRepositoryTest {

    private val requests = CopyOnWriteArrayList<Request>()
    private var reply: Pair<Int, String> = 200 to "{}"
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (code, body) = reply
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()

    @After
    fun closeClient() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** 必须请求已验证的 profile 端点，而不是杜撰的 URL。 */
    @Test
    fun `loadProfile hits the verified profile endpoint`() = runTest {
        reply = 200 to profileFixture(nickname = "测试昵称", headpic = "https://img/p.png")
        val repo = QQMusicProfileRepository(client)
        repo.loadProfile("2894378037")
        assertTrue("须请求 fcg_get_profile_homepage.fcg", requests.size == 1)
        val url = requests.first().url.toString()
        assertTrue("端点须是 c6.y.qq.com profile，实际=$url", url.contains("fcg_get_profile_homepage.fcg"))
        assertTrue("须带用户 uin", url.contains("2894378037"))
    }

    /** 解析昵称：接受 creator.nickname / data.nickname 多候选字段。 */
    @Test
    fun `loadProfile parses nickname from creator`() = runTest {
        reply = 200 to profileFixture(nickname = "歌神", headpic = "https://img/a.png")
        val profile = QQMusicProfileRepository(client).loadProfile("2894378037")
        assertNotNull(profile)
        assertEquals("歌神", profile!!.nickname)
    }

    /** 解析头像：接受 creator.headpic / data.headpic 多候选字段。 */
    @Test
    fun `loadProfile parses avatar url`() = runTest {
        reply = 200 to profileFixture(nickname = "歌神", headpic = "https://img/avatar.png")
        val profile = QQMusicProfileRepository(client).loadProfile("2894378037")
        assertNotNull(profile)
        assertEquals("https://img/avatar.png", profile!!.avatarUrl)
    }

    /** 空数据不得被当成"有资料"，避免 UI 显示空白 account。 */
    @Test
    fun `loadProfile returns null on empty response`() = runTest {
        reply = 200 to """{"code":0,"data":{}}"""
        val profile = QQMusicProfileRepository(client).loadProfile("2894378037")
        assertNull("无资料字段时须返回 null", profile)
    }

    /** HTTP 失败须降级为 null，不抛异常打断调用方（对齐网易云 loadProfile 失败返回 null）。 */
    @Test
    fun `loadProfile degrades to null on http failure`() = runTest {
        reply = 500 to "boom"
        val profile = QQMusicProfileRepository(client).loadProfile("2894378037")
        assertNull("HTTP 失败须降级 null", profile)
    }

    /** uin 为空时不应发请求（与项目其它 QQ 能力"未登录不发请求"一致）。 */
    @Test
    fun `loadProfile with blank uin does not send request`() = runTest {
        val profile = QQMusicProfileRepository(client).loadProfile("")
        assertNull(profile)
        assertEquals("空 uin 不应发请求", 0, requests.size)
    }

    private fun profileFixture(nickname: String, headpic: String) = """
        {"code":0,"data":{
          "creator":{"nickname":"$nickname","headpic":"$headpic"},
          "uin":2894378037
        }}
    """.trimIndent()
}
