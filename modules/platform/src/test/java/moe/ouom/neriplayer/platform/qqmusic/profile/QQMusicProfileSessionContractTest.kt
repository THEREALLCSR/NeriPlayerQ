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
 * File: moe.ouom.neriplayer.platform.qqmusic.profile/QQMusicProfileSessionContractTest
 */

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
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
 * 第 018 次迭代红灯：profile API 从"只传 uin 的弱化版"补强到可用于 app 登录态的版本。
 *
 * 背景：第 011 次补的 loadProfile(uin) 是为打红灯简化的版本——只带 uin + format，
 * 无 g_tk/Cookie/loginUin。而 app 层现有一份手写实现带了完整登录参数
 * （SettingsAccountProfiles.kt:174），QM-Download v0.6 真实账号验证的实现也带全套。
 * 要用统一 repository 替换 app 手写实现（消除重复、提高复用），必须先把 repository
 * 补强到等价强度，否则委托会造成登录态资料获取退化。
 *
 * 本测试钉住 QM-Download 已验证的 load(session) 契约：
 * 全套登录参数 + Cookie + creator.nick 候选 + http→https 归一化 + 未登录不发请求。
 * 编译期红灯：load(session) 重载不存在。
 */
class QQMusicProfileSessionContractTest {

    private val requests = CopyOnWriteArrayList<Request>()
    private var reply: Pair<Int, String> = 200 to """{"code":0,"data":{"creator":{"nick":"歌神","headpic":"http://img/a.png"}}}"""
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

    /** 登录态请求须带完整身份参数（g_tk/loginUin/userid）与 Cookie，而非只带 uin。 */
    @Test
    fun `load session sends authenticated profile request`() = runTest {
        val repo = QQMusicProfileRepository(client)
        val profile = repo.load(loggedInSession())

        assertNotNull(profile)
        assertEquals(1, requests.size)
        val url = requests.first().url
        assertTrue("须带 g_tk（QQ 接口级校验）", url.queryParameter("g_tk")!!.isNotBlank())
        assertTrue("须带 loginUin", url.queryParameter("loginUin") == "123")
        assertTrue("须带 userid", url.queryParameter("userid") == "123")
        assertTrue("须带 format=json", url.queryParameter("format") == "json")
        assertEquals("须带完整 Cookie", loggedInSession().cookie, requests.first().header("Cookie"))
    }

    /** 昵称候选须含 creator.nick（QQ 真实响应字段），不能只认 nickname。 */
    @Test
    fun `load session parses creator nick candidate`() = runTest {
        reply = 200 to """{"code":0,"data":{"creator":{"nick":"真实昵称"}}}"""
        val profile = QQMusicProfileRepository(client).load(loggedInSession())

        assertNotNull(profile)
        assertEquals("真实昵称", profile!!.nickname)
    }

    /** 头像 http:// 须归一化为 https://（QM-Download 已验证的 normalize 行为）。 */
    @Test
    fun `load session normalizes http avatar to https`() = runTest {
        reply = 200 to """{"code":0,"data":{"creator":{"nick":"x","headpic":"http://img/a.png"}}}"""
        val profile = QQMusicProfileRepository(client).load(loggedInSession())

        assertEquals("https://img/a.png", profile!!.avatarUrl)
    }

    /** 未登录不发请求（与手写实现和 QM-Download 一致）。 */
    @Test
    fun `load session with guest session does not send request`() = runTest {
        val profile = QQMusicProfileRepository(client).load(guestSession())

        assertNull(profile)
        assertEquals("未登录不应发请求", 0, requests.size)
    }

    /** HTTP 失败降级 null，不抛异常（app 层调用方依赖此行为）。 */
    @Test
    fun `load session degrades to null on http failure`() = runTest {
        reply = 500 to "boom"
        val profile = QQMusicProfileRepository(client).load(loggedInSession())

        assertNull(profile)
    }

    private fun loggedInSession() = QQMusicSession(
        cookie = "uin=123; qm_keyst=KEY",
        uin = "123",
        musicKey = "KEY",
        authst = "AUTHST",
        musicId = "123",
        wid = "WID",
        sid = "SID",
    )

    private fun guestSession() = QQMusicSession(
        cookie = "",
        uin = "",
        musicKey = "",
    )
}
