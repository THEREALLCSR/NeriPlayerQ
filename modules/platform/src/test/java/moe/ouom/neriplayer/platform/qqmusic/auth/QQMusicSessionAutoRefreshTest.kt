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
 * File: moe.ouom.neriplayer.platform.qqmusic.auth/QQMusicSessionAutoRefreshTest
 */

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 10 次迭代红灯：登录态自动续签编排器。
 *
 * 把"读 store → isNearExpiry 判断 → refresh → 回写 store"的编排抽成可注入的纯 Kotlin 类，
 * 让启动自检能离线单测。契约对齐 QM-Download v0.6 的 QmApplication.ensureSessionFresh：
 * - 未临近过期：不发请求、不回写，返回 false；
 * - 临近过期：续签成功后回写新 cookie，返回 true；
 * - 失败降级：refresh 抛错 / 返回未登录 cookie 时，保留原登录态、不回写、不抛异常，返回 false；
 * - force：跳过时间判断强制续签一次；
 * - 并发串行：同一 refresh_token 不被并发重复消费。
 *
 * 编译期红灯：被测对象 QQMusicSessionAutoRefresh 尚未实现。
 */
class QQMusicSessionAutoRefreshTest {

    private companion object {
        const val NOW: Long = 2_000_000_000_000L
        const val HOUR_MS: Long = 3_600_000L
    }

    /** 可变的假 store，记录回写次数。 */
    private class FakeStore(initial: String) {
        var cookie: String = initial
        var saveCount: Int = 0
        fun read(): String = cookie
        fun save(value: String) {
            cookie = value
            saveCount++
        }
    }

    private fun cookieAge(hours: Long): String =
        "uin=3396921622; qm_keyst=OLD_KEY; qqmusic_uin=3396921622; " +
            "psrf_musickey_createtime=${((NOW - hours * HOUR_MS) / 1_000L)}; " +
            "psrf_qqrefresh_token=OLD_REF"

    private fun enqueueRefresh(server: MockWebServer, code: Int = 0, reqCode: Int = 0, musickey: String = "NEW_KEY") {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"code":$code,"req1":{"code":$reqCode,"data":{"musickey":"$musickey","refresh_token":"NEW_REF"}}}""")
                .build()
        )
    }

    private fun newRefresher(
        store: FakeStore,
        server: MockWebServer,
        forceClock: () -> Long = { NOW },
    ) = QQMusicSessionAutoRefresh(
        readCookie = store::read,
        saveCookie = store::save,
        httpClient = OkHttpClient(),
        refreshUrl = server.url("/cgi-bin/musicu.fcg").toString(),
        clock = forceClock,
    )

    @Test
    fun `ensureFresh does nothing when keyst is not near expiry`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val store = FakeStore(cookieAge(1))
            val result = newRefresher(store, server).ensureFresh()
            assertFalse("刚登录 1h 不该续签", result)
            assertEquals("不该回写", 0, store.saveCount)
            assertEquals("不该发包", 0, server.requestCount)
        } finally { server.close() }
    }

    @Test
    fun `ensureFresh refreshes and writes back new cookie when near expiry`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            enqueueRefresh(server)
            val store = FakeStore(cookieAge(25))
            val result = newRefresher(store, server).ensureFresh()
            assertTrue("临近 20h 应续签成功", result)
            assertEquals("应回写一次", 1, store.saveCount)
            assertTrue("回写的新 cookie 应含新 keyst", store.cookie.contains("qm_keyst=NEW_KEY"))
            assertEquals("应发包一次", 1, server.requestCount)
        } finally { server.close() }
    }

    @Test
    fun `ensureFresh degrades and keeps original cookie when refresh fails`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            enqueueRefresh(server, reqCode = 1000) // 业务码拒绝
            val original = cookieAge(25)
            val store = FakeStore(original)
            val result = newRefresher(store, server).ensureFresh()
            assertFalse("续签失败应返回 false", result)
            assertEquals("续签失败不得回写", 0, store.saveCount)
            assertEquals("续签失败须保留原 cookie", original, store.cookie)
        } finally { server.close() }
    }

    @Test
    fun `ensureFresh ignores refreshed cookie that is not logged in`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            // 响应 data 既无 musickey 也无 uin，合并后仍是旧 cookie（login 有效），
            // 但旧 cookie 若本身缺 keyst 则不应被当成成功。这里用一个缺 keyst 的旧 cookie。
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"code":0,"req1":{"code":0,"data":{}}}""")
                    .build()
            )
            val notLoggedIn = "uin=3396921622; psrf_musickey_createtime=${(NOW / 1000L)}"
            val store = FakeStore(notLoggedIn)
            val result = newRefresher(store, server).ensureFresh(force = true)
            assertFalse("续签后仍无 keyst 不应视为成功", result)
            assertEquals("不应回写无效登录态", 0, store.saveCount)
        } finally { server.close() }
    }

    @Test
    fun `ensureFresh with force refreshes even when keyst is fresh`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            enqueueRefresh(server)
            val store = FakeStore(cookieAge(1))
            val result = newRefresher(store, server).ensureFresh(force = true)
            assertTrue("force 应强制续签", result)
            assertEquals("force 应回写", 1, store.saveCount)
            assertEquals("force 应发包一次", 1, server.requestCount)
        } finally { server.close() }
    }

    @Test
    fun `ensureFresh serializes concurrent calls to one refresh`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            repeat(4) { enqueueRefresh(server) }
            val store = FakeStore(cookieAge(25))
            val refresher = newRefresher(store, server)
            val results: List<Boolean> = (1..4).map { async<Boolean> { refresher.ensureFresh() } }.awaitAll()
            // Mutex 串行：第一个成功后 cookie 变新（不再临近过期），后续应跳过；
            // 即便都进入，saveCount 也应等于真正续签的次数且远小于并发数。
            assertEquals("并发只回写一次", 1, store.saveCount)
            assertTrue(results.count { it } <= 1)
            assertTrue("发包次数应受串行约束", server.requestCount <= 2)
        } finally { server.close() }
    }
}
