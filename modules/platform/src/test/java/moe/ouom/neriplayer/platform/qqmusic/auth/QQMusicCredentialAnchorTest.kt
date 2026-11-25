package moe.ouom.neriplayer.platform.qqmusic.auth

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
 * File: moe.ouom.neriplayer.platform.qqmusic.auth/QQMusicCredentialAnchorTest
 */

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 8 次迭代红灯：扫码登录凭据的"时间锚点"补齐。
 *
 * QQ 音乐登录持久化要能判断 keyst 是否临近过期（从而在强制扫码前自动续签），
 * 前提是扫码登录成功时把服务端返回的两个时间字段一起存进 cookie：
 *  - musickeyCreateTime -> psrf_musickey_createtime（keyst 年龄锚点）
 *  - expired_at         -> psrf_access_token_expiresAt（access token 过期锚点）
 *
 * 依据：QM-Download v0.6 的 QQMusicSessionRefresher（已用真实账号验证）配套改动
 * "任务 A：凭据字段补齐"——缺这两个字段时无法计算 keyst 年龄，续签判断无从谈起。
 * 本项目的 parseCredential/toCookie 目前完全不处理这两个字段。
 *
 * 本测试刻意只通过 toCookie() 的输出断言，不直接引用新字段，
 * 使红灯是精确的运行时失败（expected value but was null）而非编译断裂。
 */
class QQMusicCredentialAnchorTest {

    private fun credentialWith(vararg extra: Pair<String, Any>) =
        parseCredential(
            JSONObject()
                .put("str_musicid", "3396921622")
                .put("musickey", "play-key")
                .put("openid", "open-id")
                .apply { extra.forEach { (key, value) -> put(key, value) } }
        )

    /** 扫码登录须把 musickeyCreateTime 存进 cookie，作为 keyst 续签的时间锚点。 */
    @Test
    fun `toCookie persists keyst creation time anchor`() {
        val cookie = credentialWith("musickeyCreateTime" to "1700000000").toCookie()
        val values = QQMusicSessionStore.cookieValues(cookie)
        assertEquals(
            "扫码登录须把服务端 musickeyCreateTime 存入 psrf_musickey_createtime，否则续签无法判断 keyst 年龄",
            "1700000000",
            values["psrf_musickey_createtime"],
        )
    }

    /** 扫码登录须把 expired_at 存进 cookie，作为 access token 过期锚点。 */
    @Test
    fun `toCookie persists access token expiry anchor`() {
        val cookie = credentialWith("expired_at" to "1700864000").toCookie()
        val values = QQMusicSessionStore.cookieValues(cookie)
        assertEquals(
            "扫码登录须把服务端 expired_at 存入 psrf_access_token_expiresAt",
            "1700864000",
            values["psrf_access_token_expiresAt"],
        )
    }

    /** 非法/空时间值不得写进 cookie（与 toCookie 既有的 Cookie 注入过滤一致）。 */
    @Test
    fun `toCookie rejects invalid time anchors so cookie stays well formed`() {
        val cookie = credentialWith(
            "musickeyCreateTime" to "bad;value\r\n",
            "expired_at" to "",
        ).toCookie()
        val values = QQMusicSessionStore.cookieValues(cookie)
        assertTrue(
            "含分隔符/换行的 musickeyCreateTime 不得写进 cookie",
            values["psrf_musickey_createtime"] == null,
        )
        assertTrue(
            "空的 expired_at 不得写进 cookie",
            values["psrf_access_token_expiresAt"] == null,
        )
    }
}
