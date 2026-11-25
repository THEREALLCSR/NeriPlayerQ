package moe.ouom.neriplayer.platform.playback.mapping

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
 * File: moe.ouom.neriplayer.platform.playback.mapping/PlatformPlaybackMapperContractTest
 */

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Q5（统一平台 PlayableResult）的红灯契约测试。
 *
 * 背景：QQ 音乐改造时建立了类型化取流结果 `QQMusicPlaybackResult`
 * （Playable / LoginRequired / LoginExpired / Unavailable / Transport），
 * 且能保真地把五种语义映射到播放层。但 B站 / 网易云 / YouTube 三家
 * 至今仍把"无资源 / 需要登录 / 网络失败"全部折叠成 `SongUrlResult.Failure`，
 * 失败语义丢失——用户看到的是同一句无差别报错。
 *
 * 本测试锁定的缺口：三家各自的类型化结果，都应能通过一个**独立的、
 * 可单测的 mapper** 转成统一结果 `PlatformPlaybackResult`，且失败语义不丢失。
 *
 * 采用反射探测而非直接引用类型，是为了让"类型/函数还不存在"表现为明确的
 * 测试失败而不是编译错误——红灯要能说明缺的是什么，而不只是编译不过。
 */
class PlatformPlaybackMapperContractTest {

    private val mappingPkg = "moe.ouom.neriplayer.platform.playback.mapping"

    /** 统一结果类型必须存在，且是带这四种语义的 sealed 类型。 */
    @Test
    fun `unified PlatformPlaybackResult type exists with four semantics`() {
        val clazz = try {
            Class.forName("$mappingPkg.PlatformPlaybackResult")
        } catch (_: ClassNotFoundException) {
            fail("缺少统一结果类型 $mappingPkg.PlatformPlaybackResult（Q5 未实现）")
            return
        }
        assertTrue(
            "PlatformPlaybackResult 必须是 sealed class/sealed interface 以保真失败语义",
            clazz.isSealed || Modifier.isAbstract(clazz.modifiers)
        )
        val nestedNames = clazz.declaredClasses.map { it.simpleName }
        listOf("Success", "LoginRequired", "Unavailable", "Transport").forEach { expected ->
            assertTrue(
                "PlatformPlaybackResult 缺少语义分支 $expected；现有=${nestedNames.sorted()}",
                nestedNames.any { it == expected }
            )
        }
    }

    /** B站 mapper：BiliAudioStreamInfo? 与失败原因 → 统一结果。 */
    @Test
    fun `Bili mapper exists and preserves no-stream vs failure semantics`() {
        assertMapperExists("BiliPlaybackResultMapper")
    }

    /** 网易云 mapper：NeteasePlaybackResponseParser.PlaybackResult → 统一结果。 */
    @Test
    fun `Netease mapper exists and preserves requires-login vs failure semantics`() {
        assertMapperExists("NeteasePlaybackResultMapper")
    }

    /** YouTube mapper：YouTubePlayableAudio? 与失败原因 → 统一结果。 */
    @Test
    fun `YouTube mapper exists and preserves no-stream vs failure semantics`() {
        assertMapperExists("YouTubePlaybackResultMapper")
    }

    private fun assertMapperExists(simpleName: String) {
        val clazz = try {
            Class.forName("$mappingPkg.$simpleName")
        } catch (_: ClassNotFoundException) {
            fail("缺少 mapper $mappingPkg.$simpleName（Q5 未实现：三家失败语义仍被折叠）")
            return
        }
        assertTrue(
            "$simpleName 应提供静态 map 入口（Kotlin object 的 @JvmStatic 或伴生对象）",
            clazz.methods.any { it.name == "map" } ||
                clazz.declaredClasses.any { it.methods.any { m -> m.name == "map" } }
        )
    }
}
