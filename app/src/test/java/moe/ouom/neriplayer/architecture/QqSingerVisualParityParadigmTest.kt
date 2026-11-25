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
 * File: moe.ouom.neriplayer.architecture/QqSingerVisualParityParadigmTest
 */

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 021 次迭代红灯：QQ 歌手页视觉对齐网易云（用户反馈两项纯视觉问题）。
 *
 * ① 头像：QQ 页 `Modifier.size(112.dp).clip(neriRoundedShape(56.dp))`——
 *    112dp 边长配 56dp 圆角恰好等于半径，视觉等效正圆。用户要"倒圆角方形"，
 *    应改明显小于半径的圆角（24dp，与页面卡片 neriRoundedShape(28.dp) 同族）。
 * ② Tab 图标：网易云 ArtistWorkTabs 两个 Tab 带 MusicNote/LibraryMusic 图标
 *    （NeteaseArtistDetailScreen.kt:414-425），QQ 页 TabRow 只有 text。
 *
 * 沿用项目静态扫描范式（QqSingerDetailEnrichmentParadigmTest 等）。
 * 静态测试钉"可机械判定的事实"，最终形态由真机目视确认。
 */
class QqSingerVisualParityParadigmTest {

    private val screen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/artist/QqMusicSingerDetailScreen.kt"
    )

    @Test
    fun `singer avatar uses rounded square not full circle`() {
        val source = screen.readText()
        assertFalse(
            "112dp 头像配 56dp 圆角 = 半径，视觉是正圆；应改成明显小于半径的圆角（如 24dp）",
            source.contains("neriRoundedShape(56.dp)")
        )
        assertTrue(
            "头像须用倒圆角方形圆角（24dp 或 28dp）",
            source.contains("neriRoundedShape(24.dp)") || source.contains("neriRoundedShape(28.dp)")
        )
    }

    @Test
    fun `singer work tabs carry icons like netease`() {
        val source = screen.readText()
        assertTrue(
            "歌曲 Tab 须带图标 MusicNote（对齐网易云 ArtistWorkTabs）",
            source.contains("Icons.Outlined.MusicNote")
        )
        assertTrue(
            "专辑 Tab 须带图标 LibraryMusic（对齐网易云 ArtistWorkTabs）",
            source.contains("Icons.Outlined.LibraryMusic")
        )
        assertTrue(
            "两个 Tab 都要传 icon 参数，不能只有 text",
            Regex("icon\\s*=\\s*\\{").findAll(source).count() >= 2
        )
    }
}
