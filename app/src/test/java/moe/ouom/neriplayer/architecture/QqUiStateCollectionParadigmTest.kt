package moe.ouom.neriplayer.architecture

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
 * File: moe.ouom.neriplayer.architecture/QqUiStateCollectionParadigmTest
 */

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 相关 UI 的 State 收集范式统一（迭代 4）的红灯契约测试。
 *
 * 项目已确立的范式：Composable 收集 Flow 必须用 `collectAsStateWithLifecycle()`，
 * 裸 `collectAsState()` 会在页面进入后台后继续收集、浪费资源并可能引发崩溃。
 * QQ 相关页面里 3 处仍是裸用：
 *  - QqMusicSingerDetailScreen.kt:101
 *  - QqMusicApiProbeScreen.kt:46
 *  - HomeQqFeed.kt:225
 * 而同目录的 `QqMusicPlaylistDetailScreen.kt` 已经是正确写法（lifecycle）——
 * 说明这是可机械收敛的尾部债务，不是架构障碍。
 *
 * 本测试用静态源码扫描钉住范式：这 3 个文件不许再出现裸 `collectAsState()`，
 * 且必须出现 `collectAsStateWithLifecycle`。改动前红、改完绿。
 */
class QqUiStateCollectionParadigmTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private val targets = listOf(
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/artist/QqMusicSingerDetailScreen.kt",
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/debug/QqMusicApiProbeScreen.kt",
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/home/HomeQqFeed.kt",
    )

    @Test
    fun `QQ screens have no raw collectAsState`() {
        val offenders = targets.filter { path ->
            val source = File(projectRoot, path).readText()
            // 排除 withLifecycle 变体后，仍出现 collectAsState( 即为裸用。
            Regex("""(?<!WithLifecycle)collectAsState\s*\(""").containsMatchIn(source)
        }
        assertTrue(
            "以下 QQ 页面仍用裸 collectAsState()，应改为 collectAsStateWithLifecycle()：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `QQ screens import lifecycle collection API`() {
        val missing = targets.filter { path ->
            val source = File(projectRoot, path).readText()
            !source.contains("collectAsStateWithLifecycle")
        }
        assertTrue(
            "以下 QQ 页面缺少 collectAsStateWithLifecycle 的 import/使用：$missing",
            missing.isEmpty(),
        )
    }
}
