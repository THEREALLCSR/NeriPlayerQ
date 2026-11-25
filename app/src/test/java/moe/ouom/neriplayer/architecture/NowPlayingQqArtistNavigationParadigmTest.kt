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
 * File: moe.ouom.neriplayer.architecture/NowPlayingQqArtistNavigationParadigmTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 025 次迭代红灯：播放页点歌手名按音源打开对应歌手页（补 QQ 分支）。
 *
 * 用户反馈：播放页点歌手名无法打开歌手页。探查根因：
 * NowPlayingScreen 的 openCurrentArtist when 块只有 B站/YouTube/网易云三个分支，
 * 没有 QQ；QQ 歌曲落 else 走 openCurrentNeteaseArtist，被其音源守卫拒绝→静默无响应。
 *
 * 参照网易云范式（点击→音源判断→解析歌手→导航），补 QQ 分支：
 * - SongItem 没有 singer mid，需按歌手名 searchSingers 解析出 QQMusicSinger；
 * - QQ 导航函数 navigateToQqMusicSinger 与路由已就绪，无需改导航层。
 *
 * 静态扫描钉接线事实（音源分支、解析函数、导航注入）；真机点击由人工验证。
 */
class NowPlayingQqArtistNavigationParadigmTest {

    private val screen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/NowPlayingScreen.kt"
    )
    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/NowPlayingViewModel.kt"
    )
    private val actions = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/nowplaying/NowPlayingSecondaryActions.kt"
    )
    private val neriApp = File(
        "src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt"
    )

    @Test
    fun `openCurrentArtist has a QQ source branch`() {
        val source = screen.readText()
        assertTrue(
            "openCurrentArtist 须有 QQ 分支（当前只有 B站/YouTube/网易云，QQ 落 else 被守卫拒绝）",
            source.contains("openCurrentQqSinger") ||
                source.contains("isQqSingerNavigationSource")
        )
        assertTrue(
            "播放页须接收 onEnterQqSinger 导航回调",
            Regex("onEnterQqSinger\\s*:").containsMatchIn(source)
        )
    }

    @Test
    fun `view model resolves QQ singer via searchSingers`() {
        val source = vm.readText()
        assertTrue(
            "VM 须有解析 QQ 歌手的方法（SongItem 无 mid，需按歌手名搜索）",
            Regex("fun\\s+resolveQqSinger\\s*\\(").containsMatchIn(source)
        )
        assertTrue(
            "解析 QQ 歌手须复用现成 searchSingers API",
            source.contains("searchSingers(")
        )
    }

    @Test
    fun `QQ singer navigation source uses unified music source resolver`() {
        val source = actions.readText()
        assertTrue(
            "QQ 歌手跳转判定须复用统一音源工具 MusicSources.isQqMusic（门禁要求音源规则只在 resolver）",
            source.contains("MusicSources.isQqMusic")
        )
    }

    @Test
    fun `NeriApp wires QQ singer navigation callback`() {
        val source = neriApp.readText()
        assertTrue(
            "NeriApp 须把 onEnterQqSinger 接到 navigateToQqMusicSinger（函数已就绪）",
            source.contains("navigateToQqMusicSinger")
        )
    }
}
