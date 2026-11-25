package moe.ouom.neriplayer.platform.qqmusic.network

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
 * File: moe.ouom.neriplayer.platform.qqmusic.network/QQMusicFailureTest
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码 → 语义 → 用户文案 的映射契约。
 *
 * 这些码值来自 README 的真机复核记录；在改造前它们只存在于文档与注释里，
 * 代码对任何非 0 返回都是同一句"可能需要该音质权益"。
 * 本测试的作用是：**让文档里的知识一旦被改动就立刻变红**。
 */
class QQMusicFailureTest {

    @Test
    fun `expired cookie asks the user to relogin`() {
        val failure = QQMusicApiException.fromCode(1000, "cookie expired")

        assertEquals(QQMusicFailureKind.SessionExpired, failure.kind)
        assertTrue(failure.requiresRelogin)
        assertTrue(failure.message!!.contains("重新扫码"))
        assertEquals(1000, failure.code)
    }

    @Test
    fun `anonymous gateway rejection is also a relogin case`() {
        val failure = QQMusicApiException.fromCode(2001, "")

        assertEquals(QQMusicFailureKind.SessionExpired, failure.kind)
        assertTrue(failure.requiresRelogin)
    }

    @Test
    fun `comment write without app credential is a permission issue not a relogin`() {
        val failure = QQMusicApiException.fromCode(10000, "web session")

        assertEquals(QQMusicFailureKind.PermissionDenied, failure.kind)
        assertFalse(
            "重新登录解决不了缺少 App 凭据的问题，不应引导用户去扫码",
            failure.requiresRelogin
        )
    }

    @Test
    fun `risk control codes are retryable but not relogin`() {
        listOf(500026, 104009).forEach { code ->
            val failure = QQMusicApiException.fromCode(code, "")
            assertEquals("code=$code", QQMusicFailureKind.RiskControlled, failure.kind)
            assertFalse(failure.requiresRelogin)
        }
    }

    @Test
    fun `unknown code keeps the server message`() {
        val failure = QQMusicApiException.fromCode(42, "歌曲已下架")

        assertEquals(QQMusicFailureKind.Rejected, failure.kind)
        assertEquals("歌曲已下架", failure.message)
    }

    @Test
    fun `unknown code without message still tells the code`() {
        val failure = QQMusicApiException.fromCode(42, "")

        assertTrue(failure.message!!.contains("42"))
    }

    @Test
    fun `transport failure carries the cause`() {
        val cause = java.net.SocketTimeoutException("timeout")
        val failure = QQMusicApiException.transport(cause)

        assertEquals(QQMusicFailureKind.Transport, failure.kind)
        assertEquals(-1, failure.code)
        assertEquals(cause, failure.cause)
        assertTrue(failure.message!!.contains("网络"))
    }

    @Test
    fun `invalid guid is detected from text because server puts it in msg`() {
        assertTrue(QQMusicApiException.isInvalidGuid("invalidq"))
        assertTrue(QQMusicApiException.isInvalidGuid("guid INVALIDQ"))
        assertFalse(QQMusicApiException.isInvalidGuid("no permission"))
    }
}
