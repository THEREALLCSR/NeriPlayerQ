package moe.ouom.neriplayer.platform.qqmusic.playback

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * File: moe.ouom.neriplayer.platform.qqmusic.playback/QQMusicPlaybackRepositoryTest
 */

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QQ 音质候选链与文件名的纯函数测试。
 *
 * 这两块是"能不能拿到地址"的关键：
 *  - 候选链顺序错 → 明明有无损却只拿到 128k；
 *  - 文件名拼错 → 服务端直接返回空 purl。
 */
class QQMusicPlaybackRepositoryTest {

    @Test
    fun losslessFallsBackDownwards() {
        val chain = qqQualityCandidates("lossless")
        assertEquals(listOf("lossless", "exhigh", "standard"), chain.map { it.qualityKey })
        assertEquals(listOf("F000", "M800", "M500"), chain.map { it.prefix })
    }

    @Test
    fun exhighOnlyTries320ThenStandard() {
        val chain = qqQualityCandidates("exhigh")
        assertEquals(listOf("exhigh", "standard"), chain.map { it.qualityKey })
    }

    @Test
    fun unknownQualityFallsBackToStandard() {
        // 未知/空档位不能让播放失败，最差也要能出声
        assertEquals(listOf("standard"), qqQualityCandidates("something-new").map { it.qualityKey })
        assertEquals(listOf("standard"), qqQualityCandidates("").map { it.qualityKey })
    }

    @Test
    fun fileNameUsesMediaMidWhenPresent() {
        val candidate = qqQualityCandidates("lossless").first()
        // 有 media_mid：前缀 + media_mid + 扩展名
        assertEquals("F0000039MnYb0qxYhV.flac", candidate.fileName("0039MnYb0qxYhV", "0039MnYb0qxYhV"))
        // 没有 media_mid：前缀 + songmid + songmid + 扩展名（旧规则）
        assertEquals("F0000039MnYb0qxYhV0039MnYb0qxYhV.flac", candidate.fileName("0039MnYb0qxYhV", null))
        assertEquals(
            "F0000039MnYb0qxYhV0039MnYb0qxYhV.flac",
            candidate.fileName("0039MnYb0qxYhV", "   "),
        )
    }
}
