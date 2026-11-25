package moe.ouom.neriplayer.platform.search.api.client

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
 * File: moe.ouom.neriplayer.platform.search.api.client/QQMusicSearchErrorClassificationTest
 */

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicApiException
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicFailureKind
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q2（QQMusicSearchApi 接入 QQMusicClient）的红灯契约测试。
 *
 * 取流链路早已把 QQ 的返回码分类成类型化异常 `QQMusicApiException`
 * （`QQMusicFailureKind.SessionExpired` / `PermissionDenied` / …），
 * 让上层能区分"重新扫码"与"没版权"。但搜索链路没有接入这套分类：
 * `QQMusicSearchApi.search()` 遇到 `code=2001` 时只 `throw IOException("...2001...")`，
 * 上层拿不到 `requiresRelogin` 语义，只能显示一句字符串。
 *
 * 本测试锁定的缺口：搜索失败必须是类型化异常，且码值映射到与取流链路一致的语义。
 * 这是"搜索接入统一 client/错误码分类"的可观察行为契约——不依赖真实网络。
 */
class QQMusicSearchErrorClassificationTest {

    private val requests = CopyOnWriteArrayList<Request>()
    private var nextBody: String = "{}"

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(nextBody.toResponseBody()).build()
    }.build()

    private val api = QQMusicSearchApi(http)

    @After
    fun closeClient() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /** 登录态失效（2001）必须映射成 SessionExpired，而不是裸 IOException。 */
    @Test
    fun `search 2001 throws typed SessionExpired exception`() = runTest {
        nextBody = """{"req_0":{"code":2001,"msg":"login expired"}}"""

        val error = runCatching { api.search("anything", page = 1) }
            .exceptionOrNull()
        assertTrue("search 2001 应抛异常，实际=${error}", error != null)
        assertTrue(
            "应抛类型化 QQMusicApiException，实际=${error!!::class.simpleName}: ${error.message}",
            error is QQMusicApiException
        )
        error as QQMusicApiException
        assertEquals(QQMusicFailureKind.SessionExpired, error.kind)
        assertEquals(2001, error.code)
        assertTrue("上层须能据此引导重新登录", error.requiresRelogin)
    }

    /** 音乐版权/权益类错误（code=1004）应映射成有语义的失败，而非裸 IOException。 */
    @Test
    fun `search non-zero code throws typed QQMusicApiException`() = runTest {
        nextBody = """{"req_0":{"code":1004,"msg":"no copyright"}}"""

        val error = runCatching { api.search("anything", page = 1) }
            .exceptionOrNull()
        assertTrue("search 非0码应抛异常，实际=${error}", error != null)
        assertTrue(
            "应抛类型化 QQMusicApiException，实际=${error!!::class.simpleName}: ${error.message}",
            error is QQMusicApiException
        )
        error as QQMusicApiException
        assertEquals(1004, error.code)
        assertEquals(QQMusicFailureKind.Rejected, error.kind)
    }
}
