package moe.ouom.neriplayer.platform.qqmusic.auth

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * File: moe.ouom.neriplayer.platform.qqmusic.auth/QQMusicAuthTest
 */

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 音乐登录协议的纯函数测试。
 *
 * 这些测试不联网，只锁住"协议里最容易写错、又最难在真机上调试"的部分：
 * hash33 的黄金值、ptuiCB 状态码表、Cookie 的拼装与解析。
 */
class QQMusicAuthTest {

    @Test
    fun hash33MatchesQqProtocol() {
        // 与 MeloX 项目里用真实账号验证过的黄金值一致
        assertEquals(1_968_809_247L, qqMusicHash33("example-qrsig", seed = 0))
        assertEquals(193_496_974L, qqMusicHash33("key", seed = 5381))
    }

    @Test
    fun parsesQrLoginStates() {
        assertEquals(
            QQMusicQrLoginEvent.Waiting,
            parsePtuiCallback("ptuiCB('66','0','','0','二维码未失效。','')").event,
        )
        assertEquals(
            QQMusicQrLoginEvent.Scanned,
            parsePtuiCallback("ptuiCB('67','0','','0','二维码认证中。','')").event,
        )
        assertEquals(
            QQMusicQrLoginEvent.Expired,
            parsePtuiCallback("ptuiCB('65','0','','0','二维码已失效。','')").event,
        )
        assertEquals(
            QQMusicQrLoginEvent.Rejected,
            parsePtuiCallback("ptuiCB('68','0','','0','用户拒绝。','')").event,
        )

        val connected = parsePtuiCallback(
            "ptuiCB('0','0','https://graph.qq.com/oauth2.0/login_jump?ptsigx=sig-value&uin=12345678','0','登录成功！','')",
        )
        assertEquals(QQMusicQrLoginEvent.Connected, connected.event)
        assertEquals("12345678", connected.uin)
        assertEquals("sig-value", connected.sigX)
    }

    @Test
    fun parsesCookieAliases() {
        // 完整登录态：qm_keyst + uin
        val full = QQMusicSessionStore.parse(
            "pgv_pvid=1; uin=12345678; qm_keyst=Q_H_L_abc; login_type=1",
        )
        assertEquals("12345678", full.uin)
        assertEquals("Q_H_L_abc", full.musicKey)
        assertTrue(full.isLoggedIn)

        // 只有别名的旧 Cookie：qqmusic_uin + qqmusic_key
        val alias = QQMusicSessionStore.parse("qqmusic_uin=o000123456; qqmusic_key=KEY2")
        assertEquals("123456", alias.uin)
        assertEquals("KEY2", alias.musicKey)
        assertTrue(alias.isLoggedIn)

        // 缺关键字段：不应被视为已登录
        val partial = QQMusicSessionStore.parse("uin=12345")
        assertFalse(partial.isLoggedIn)
    }

    @Test
    fun buildsCookieThatStoreCanParseBack() {
        val credential = parseCredential(
            JSONObject()
                .put("musicid", 12_345_678)
                .put("musickey", "play-key")
                .put("openid", "open-id")
                .put("loginType", 2),
        )
        val cookie = credential.toCookie()
        val session = QQMusicSessionStore.parse(cookie)

        assertEquals("12345678", session.uin)
        assertEquals("play-key", session.musicKey)
        assertTrue(session.isLoggedIn)
        assertTrue(cookie.contains("psrf_qqopenid=open-id"))
    }
}
