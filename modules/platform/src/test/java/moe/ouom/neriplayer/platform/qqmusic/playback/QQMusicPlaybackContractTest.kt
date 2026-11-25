package moe.ouom.neriplayer.platform.qqmusic.playback

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
 * File: moe.ouom.neriplayer.platform.qqmusic.playback/QQMusicPlaybackContractTest
 */

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * QQ 取流链路的 HTTP 契约。
 *
 * 改造前这个文件只有 4 条纯函数测试（候选链 / 文件名），**请求组装与响应解析零覆盖**：
 *  sip 选择、http→https、`result != 0` 的降级、`code=1000` 的语义，
 * 全部只能靠真机抓包确认。结果是"代码改了、看起来也没坏"，直到用户点开某首歌。
 *
 * 这里用假服务器把这些分支逐条钉死。
 */
class QQMusicPlaybackContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: QQMusicPlaybackRepository
    private var expiredNotified = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        expiredNotified = 0
        repository = newRepository(session = loggedInSession())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `relative purl is joined with sip and upgraded to https`() = runBlocking {
        server.enqueue(
            vkeyResponse(
                purl = "M800/media-1.mp3",
                sip = listOf("http://isure.stream.qqmusic.qq.com/"),
            )
        )

        val result = resolve(quality = "exhigh")

        assertTrue(result is QQMusicPlaybackResult.Playable)
        result as QQMusicPlaybackResult.Playable
        assertEquals(
            "https://isure.stream.qqmusic.qq.com/M800/media-1.mp3",
            result.url,
        )
        assertEquals("exhigh", result.qualityKey)
        assertEquals(320, result.bitrateKbps)
        assertEquals("mp3", result.format)
    }

    @Test
    fun `absolute purl keeps its host and drops plain http`() = runBlocking {
        server.enqueue(
            vkeyResponse(
                purl = "http://dl.stream.qqmusic.qq.com/song.flac",
                sip = emptyList(),
            )
        )

        val result = resolve(quality = "lossless") as QQMusicPlaybackResult.Playable

        assertEquals("https://dl.stream.qqmusic.qq.com/song.flac", result.url)
        assertEquals("flac", result.format)
    }

    @Test
    fun `ws sip domain is skipped when a normal one exists`() = runBlocking {
        server.enqueue(
            vkeyResponse(
                purl = "M500/media-1.mp3",
                sip = listOf(
                    "http://ws.stream.qqmusic.qq.com/",
                    "https://isure.stream.qqmusic.qq.com/",
                ),
            )
        )

        val result = resolve(quality = "standard") as QQMusicPlaybackResult.Playable

        assertEquals("https://isure.stream.qqmusic.qq.com/M500/media-1.mp3", result.url)
    }

    @Test
    fun `blank purl falls back to the next quality instead of failing`() = runBlocking {
        server.enqueue(vkeyResponse(purl = "", sip = emptyList()))
        server.enqueue(vkeyResponse(purl = "M800/media-1.mp3", sip = emptyList()))

        val result = resolve(quality = "lossless") as QQMusicPlaybackResult.Playable

        assertEquals("exhigh", result.qualityKey)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non zero result is treated as unavailable for that quality`() = runBlocking {
        server.enqueue(vkeyResponse(purl = "F000/media-1.flac", result = 1))
        server.enqueue(vkeyResponse(purl = "M800/media-1.mp3", sip = emptyList()))

        val result = resolve(quality = "lossless") as QQMusicPlaybackResult.Playable

        assertEquals("exhigh", result.qualityKey)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `expired cookie stops immediately and is not retried per quality`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"req_0":{"code":1000,"msg":"cookie expired"}}""")
                .build()
        )

        val result = resolve(quality = "lossless")

        assertEquals(QQMusicPlaybackResult.LoginExpired, result)
        assertEquals("登录失效只需通知一次", 1, expiredNotified)
        assertEquals("不能继续试下一档", 1, server.requestCount)
    }

    @Test
    fun `http 403 is classified as an expired session`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())

        assertEquals(QQMusicPlaybackResult.LoginExpired, resolve(quality = "standard"))
        assertEquals(1, expiredNotified)
    }

    @Test
    fun `all qualities empty reports unavailable with reason`() = runBlocking {
        repeat(3) {
            server.enqueue(vkeyResponse(purl = "", sip = emptyList()))
        }

        val result = resolve(quality = "lossless")

        assertTrue(result is QQMusicPlaybackResult.Unavailable)
        assertTrue((result as QQMusicPlaybackResult.Unavailable).reason.isNotBlank())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `missing media mid triggers exactly one detail lookup first`() = runBlocking {
        server.enqueue(mediaMidResponse("media-9"))
        server.enqueue(vkeyResponse(purl = "F000media-9.flac", sip = emptyList()))

        val result = resolve(quality = "lossless", mediaMid = null) as QQMusicPlaybackResult.Playable

        assertEquals("lossless", result.qualityKey)
        assertEquals("详情一次 + 取流一次", 2, server.requestCount)
        val detail = server.takeRequest()
        assertTrue(detail.body!!.utf8().contains("get_song_detail_yqq"))
        val vkey = server.takeRequest()
        // 文件名必须用 media_mid，否则高音质拿不到地址
        assertTrue(vkey.body!!.utf8().contains("F000media-9.flac"))
    }

    @Test
    fun `logged out session with no resource reports login required`() = runBlocking {
        repository = newRepository(session = QQMusicSession(cookie = "", uin = "", musicKey = ""))
        server.enqueue(vkeyResponse(purl = "", sip = emptyList()))

        assertEquals(QQMusicPlaybackResult.LoginRequired, resolve(quality = "standard"))
        assertEquals(0, expiredNotified)
    }

    @Test
    fun `second resolve reuses cached media mid and quality decision`() = runBlocking {
        // 首次：详情 → 无损空 → 320k 成功（3 次）
        server.enqueue(mediaMidResponse("media-9"))
        server.enqueue(vkeyResponse(purl = ""))
        server.enqueue(vkeyResponse(purl = "M800/media-9.mp3"))
        val first = resolve(quality = "lossless", mediaMid = null) as QQMusicPlaybackResult.Playable
        assertEquals("exhigh", first.qualityKey)
        assertEquals(3, server.requestCount)

        // 第二次：media_mid 与"上次档位"都命中缓存，只剩 1 次取流
        server.enqueue(vkeyResponse(purl = "M800/media-9.mp3"))
        val second = resolve(quality = "lossless", mediaMid = null) as QQMusicPlaybackResult.Playable
        assertEquals("exhigh", second.qualityKey)
        assertEquals("重播不应再查详情、也不应重试无损", 4, server.requestCount)

        // 先丢掉首次的三条记录（详情 / 无损 / 320k），只看重播这一条
        repeat(3) { server.takeRequest() }
        val replay = server.takeRequest()
        val body = replay.body!!.utf8()
        assertTrue("应直接用 media_mid 拼文件名", body.contains("M800media-9.mp3"))
        assertFalse("已知无损不可用就不该再试", body.contains("F000"))
    }

    @Test
    fun `quality decision expires so a newly added lossless track is picked up`() = runBlocking {
        server.enqueue(vkeyResponse(purl = ""))
        server.enqueue(vkeyResponse(purl = "M800/media-1.mp3"))
        assertEquals("exhigh", (resolve(quality = "lossless") as QQMusicPlaybackResult.Playable).qualityKey)

        // 31 分钟后缓存失效：应重新从无损开始试
        nowMs += 31L * 60L * 1000L
        server.enqueue(vkeyResponse(purl = "F000/media-1.flac"))
        val later = resolve(quality = "lossless") as QQMusicPlaybackResult.Playable

        assertEquals("lossless", later.qualityKey)
        // 丢掉首次的两条（无损空 / 320k 成功），第三条应是过期后重新尝试的无损
        repeat(2) { server.takeRequest() }
        assertTrue(server.takeRequest().body!!.utf8().contains("F000media-1.flac"))
    }

    @Test
    fun `expiry comes from the response when present`() = runBlocking {
        server.enqueue(vkeyResponse(purl = "M500/media-1.mp3", expiration = 1_200L))

        val result = resolve(quality = "standard") as QQMusicPlaybackResult.Playable

        assertEquals(nowMs + 1_200_000L, result.expiresAtEpochMs)
    }

    @Test
    fun `expiry falls back to the conservative default`() = runBlocking {
        server.enqueue(vkeyResponse(purl = "M500/media-1.mp3"))

        val result = resolve(quality = "standard") as QQMusicPlaybackResult.Playable

        assertEquals(nowMs + DEFAULT_VKEY_TTL_MS, result.expiresAtEpochMs)
    }

    @Test
    fun `connection refused is reported as transport not unavailable`() = runBlocking {
        // 指向必然拒绝连接的端口，模拟断网 / DNS 失败这类传输层错误。
        // 这类失败必须和"没版权 / 没权益"分开：混成 Unavailable 时，播放页会把
        // OkHttp 的原文（Unable to resolve host "u.y.qq.com"）直接弹给用户。
        val offline = QQMusicPlaybackRepository(
            httpClient = OkHttpClient(),
            sessionProvider = { loggedInSession() },
            baseUrl = "http://127.0.0.1:1/",
            clock = { nowMs },
        )

        val result = offline.resolve(
            songMid = "003bL7Wv1fXeVi",
            mediaMid = "media-1",
            qualityKey = "standard",
        )

        assertTrue(
            "传输层失败应归类为 Transport，而不是 Unavailable",
            result is QQMusicPlaybackResult.Transport
        )
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private var nowMs = 1_700_000_000_000L

    private fun newRepository(session: QQMusicSession) = QQMusicPlaybackRepository(
        httpClient = OkHttpClient(),
        sessionProvider = { session },
        baseUrl = server.url("/").toString(),
        onSessionExpired = { expiredNotified++ },
        clock = { nowMs },
    )

    private suspend fun resolve(
        quality: String,
        mediaMid: String? = "media-1",
    ): QQMusicPlaybackResult = repository.resolve(
        songMid = "0039MnYb0qxYhV",
        mediaMid = mediaMid,
        qualityKey = quality,
    )

    private fun loggedInSession() = QQMusicSession(
        cookie = "uin=123456; qm_keyst=KEY",
        uin = "123456",
        musicKey = "KEY",
        wid = "123456",
    )

    private fun vkeyResponse(
        purl: String,
        sip: List<String> = emptyList(),
        result: Int = 0,
        expiration: Long? = null,
    ): MockResponse {
        val sipJson = sip.joinToString(",") { "\"$it\"" }
        val expirationJson = expiration?.let { ",\"expiration\":$it" }.orEmpty()
        return MockResponse.Builder()
            .code(200)
            .body(
                """
                {"req_0":{"code":0,"data":{
                  "midurlinfo":[{"purl":"$purl","result":$result$expirationJson}],
                  "sip":[$sipJson]
                }}}
                """.trimIndent()
            )
            .build()
    }

    private fun mediaMidResponse(mediaMid: String): MockResponse = MockResponse.Builder()
        .code(200)
        .body("""{"req_0":{"code":0,"data":{"track_info":{"file":{"media_mid":"$mediaMid"}}}}}""")
        .build()
}
