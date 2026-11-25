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
 * File: moe.ouom.neriplayer.platform.qqmusic.auth/QQMusicSessionRefresherTest
 */

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 9 次迭代红灯：QQ 音乐登录态续签器。
 *
 * 依据 QM-Download v0.6 的 QQMusicSessionRefresher（真实账号验证过）——续签与首次登录
 * 用同一个 QQConnectLogin.LoginServer/QQLogin 方法，区别只在入参用 refresh_token 换凭据；
 * 必须在旧 keyst 仍有效时（约 20h）完成，过期只能重新扫码。
 *
 * 三个纯函数（isNearExpiry / buildRefreshBody / mergeRefreshedCookie）便于单测，
 * refresh 负责发包、失败抛 IOException 由上层降级。本测试全部离线可跑。
 *
 * 这是编译期红灯：被测对象 QQMusicSessionRefresher 尚未实现，测试先落地。
 */
class QQMusicSessionRefresherTest {

    private companion object {
        const val NOW: Long = 2_000_000_000_000L
        const val HOUR_MS: Long = 3_600_000L
        const val THRESHOLD_HOURS: Long = 20L
    }

    private fun cookieOf(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("; ") { (key, value) -> "$key=$value" }

    private fun cookieWithCreatetime(deltaMs: Long, millis: Boolean = false): String = cookieOf(
        "uin" to "3396921622",
        "qm_keyst" to "Q_H_L_test",
        "psrf_musickey_createtime" to (if (millis) (NOW - deltaMs).toString() else ((NOW - deltaMs) / 1_000L).toString()),
    )

    private fun fullOldCookie(): String = cookieOf(
        "uin" to "3396921622",
        "qqmusic_uin" to "3396921622",
        "qm_keyst" to "OLD_KEY",
        "qqmusic_key" to "OLD_KEY",
        "music_key" to "OLD_KEY",
        "login_type" to "2",
        "psrf_qqopenid" to "OLD_OPENID",
        "psrf_qqaccess_token" to "OLD_ACC",
        "psrf_qqrefresh_token" to "OLD_REF",
        "psrf_qqunionid" to "OLD_UNION",
        "euin" to "OLD_EUIN",
        "authst" to "OLD_AUTHST",
        "musicid" to "3396921622",
        "sid" to "OLD_SID",
        "wid" to "OLD_WID",
        "psrf_musickey_createtime" to "OLD_CREATE",
        "psrf_access_token_expiresAt" to "OLD_EXPIRES",
    )

    // ── isNearExpiry ─────────────────────────────────────────────────────────

    @Test
    fun `isNearExpiry fresh keyst one hour old does not need refresh`() {
        assertFalse(QQMusicSessionRefresher.isNearExpiry(cookieWithCreatetime(HOUR_MS), NOW, THRESHOLD_HOURS))
    }

    @Test
    fun `isNearExpiry at exactly twenty hour boundary needs refresh`() {
        // 闭区间语义：age >= threshold 即需续签，恰好 20h 返回 true
        assertTrue(
            QQMusicSessionRefresher.isNearExpiry(
                cookieWithCreatetime(THRESHOLD_HOURS * HOUR_MS), NOW, THRESHOLD_HOURS,
            ),
        )
    }

    @Test
    fun `isNearExpiry accepts both seconds and milliseconds createtime`() {
        val delta = 25L * HOUR_MS
        assertTrue(QQMusicSessionRefresher.isNearExpiry(cookieWithCreatetime(delta, millis = false), NOW, THRESHOLD_HOURS))
        assertTrue(QQMusicSessionRefresher.isNearExpiry(cookieWithCreatetime(delta, millis = true), NOW, THRESHOLD_HOURS))
    }

    @Test
    fun `isNearExpiry missing or illegal createtime degrades to needing refresh`() {
        assertTrue(QQMusicSessionRefresher.isNearExpiry("uin=3396921622; qm_keyst=K", NOW, THRESHOLD_HOURS))
        assertTrue(
            QQMusicSessionRefresher.isNearExpiry(
                "uin=3396921622; psrf_musickey_createtime=not-a-number", NOW, THRESHOLD_HOURS,
            ),
        )
    }

    @Test
    fun `isNearExpiry honors custom threshold and defaults to twenty hours`() {
        assertEquals(20L, QQMusicSessionRefresher.DEFAULT_NEAR_EXPIRY_HOURS)
        val twoHoursAgo = cookieWithCreatetime(2L * HOUR_MS)
        assertTrue(QQMusicSessionRefresher.isNearExpiry(twoHoursAgo, NOW, thresholdHours = 1L))
        assertFalse(QQMusicSessionRefresher.isNearExpiry(twoHoursAgo, NOW, thresholdHours = 20L))
    }

    // ── buildRefreshBody ─────────────────────────────────────────────────────

    @Test
    fun `buildRefreshBody uses QQLogin envelope and maps credentials from old cookie`() {
        val body = JSONObject(QQMusicSessionRefresher.buildRefreshBody(fullOldCookie()))
        assertEquals(0, body.getInt("code"))
        val req1 = body.getJSONObject("req1")
        assertEquals("QQConnectLogin.LoginServer", req1.getString("module"))
        assertEquals("QQLogin", req1.getString("method"))
        val param = req1.getJSONObject("param")
        assertEquals("OLD_REF", param.getString("refresh_token"))
        assertEquals("OLD_ACC", param.getString("access_token"))
        assertEquals("OLD_EXPIRES", param.getString("expired_at"))
        assertEquals("OLD_OPENID", param.getString("psrf_qqopenid"))
        assertEquals("OLD_UNION", param.getString("unionid"))
        assertEquals("OLD_EUIN", param.getString("encryptUin"))
        assertEquals("OLD_KEY", param.getString("musickey"))
        assertEquals("OLD_CREATE", param.getString("musickeyCreateTime"))
        assertEquals("3396921622", param.getString("musicid"))
    }

    @Test
    fun `buildRefreshBody normalizes uin to digits only`() {
        val cookie = fullOldCookie().replace("uin=3396921622", "uin=o003396921622")
        val param = JSONObject(QQMusicSessionRefresher.buildRefreshBody(cookie))
            .getJSONObject("req1").getJSONObject("param")
        assertEquals("3396921622", param.getString("musicid"))
        assertEquals("3396921622", param.getString("str_musicid"))
    }

    // ── mergeRefreshedCookie ─────────────────────────────────────────────────

    @Test
    fun `mergeRefreshedCookie updates all keyst aliases and token fields`() {
        val refreshed = JSONObject()
            .put("musickey", "NEW_KEY")
            .put("refresh_token", "NEW_REF")
            .put("musickeyCreateTime", "NEW_CREATE")
        val values = QQMusicSessionStore.cookieValues(
            QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), refreshed),
        )
        assertEquals("NEW_KEY", values["qm_keyst"])
        assertEquals("NEW_KEY", values["qqmusic_key"])
        assertEquals("NEW_KEY", values["music_key"])
        assertEquals("NEW_REF", values["psrf_qqrefresh_token"])
        assertEquals("NEW_CREATE", values["psrf_musickey_createtime"])
    }

    @Test
    fun `mergeRefreshedCookie ignores blank zero null and preserves unrelated fields`() {
        val refreshed = JSONObject()
            .put("musickey", "0")
            .put("refresh_token", "")
            .put("unionid", JSONObject.NULL)
        val values = QQMusicSessionStore.cookieValues(
            QQMusicSessionRefresher.mergeRefreshedCookie(fullOldCookie(), refreshed),
        )
        assertEquals("OLD_KEY", values["qm_keyst"])
        assertEquals("OLD_REF", values["psrf_qqrefresh_token"])
        assertEquals("OLD_UNION", values["psrf_qqunionid"])
        assertEquals("OLD_AUTHST", values["authst"])
        assertEquals("OLD_SID", values["sid"])
        assertEquals("3396921622", values["musicid"])
    }

    // ── refresh（发包）──────────────────────────────────────────────────────────

    @Test
    fun `refresh posts to endpoint with old cookie and returns merged cookie`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """{"code":0,"req1":{"code":0,"data":{"musickey":"NEW_KEY","refresh_token":"NEW_REF"}}}"""
                    )
                    .build()
            )
            val client = OkHttpClient()
            val newCookie = QQMusicSessionRefresher.refresh(
                fullOldCookie(), client, url = server.url("/cgi-bin/musicu.fcg").toString(),
            )
            val values = QQMusicSessionStore.cookieValues(newCookie)
            assertEquals("NEW_KEY", values["qm_keyst"])
            assertEquals("NEW_REF", values["psrf_qqrefresh_token"])

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue("请求须带旧 Cookie", request.headers["Cookie"]!!.contains("psrf_qqrefresh_token=OLD_REF"))
            assertTrue("请求体须指向 QQLogin 续签", request.body!!.utf8().contains("QQConnectLogin.LoginServer"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `refresh throws on rejected business code`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"code":0,"req1":{"code":1000,"msg":"expired"}}""")
                    .build()
            )
            val error = try {
                QQMusicSessionRefresher.refresh(fullOldCookie(), OkHttpClient(), url = server.url("/").toString())
                null
            } catch (e: Exception) {
                e
            }
            assertTrue("续签被业务码拒绝须抛异常让上层降级", error != null)
        } finally {
            server.close()
        }
    }
}
