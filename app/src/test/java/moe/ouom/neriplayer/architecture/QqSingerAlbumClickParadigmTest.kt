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
 * File: moe.ouom.neriplayer.architecture/QqSingerAlbumClickParadigmTest
 */

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 024 次迭代红灯：QQ 歌手页专辑点击打开专辑详情。
 *
 * 真机验证：专辑 tab 已能显示专辑（第 023 次修了 albumList 字段），但点击无反应。
 * 根因（探查证据）：
 * - Screen 专辑行 `ArtistAlbumRow(album = album, onClick = {})` 是空回调（:303）；
 * - Screen 签名无 onAlbumClick 参数；
 * - VM 的 albums 是 AlbumSummary，toAlbumSummary() 丢了 mid，而 QQ 专辑详情路由
 *   参数需要 QQMusicAlbum.mid——必须额外保存原始 qqAlbums；
 * - 导航层 registerQqMusicDetailRoutes 未接 onAlbumClick 回调。
 *
 * QQ 专辑导航设施（navigateToQqMusicAlbum / QqAlbumDetail 路由 / Screen）均已就绪，
 * 只缺这四环。网易云的 onAlbumClick 透传是参照范式。
 *
 * 静态扫描钉接线事实；点击是否真能打开由真机验证。
 */
class QqSingerAlbumClickParadigmTest {

    private val screen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/artist/QqMusicSingerDetailScreen.kt"
    )
    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/QqSingerDetailViewModel.kt"
    )
    private val nav = File(
        "src/main/java/moe/ouom/neriplayer/ui/navigation/AppNavigationGraph.kt"
    )

    @Test
    fun `view model keeps original qq albums with mid for navigation`() {
        val source = vm.readText()
        assertTrue(
            "UiState 须保留原始 QQMusicAlbum（含 mid），AlbumSummary 丢了 mid 无法构造专辑详情路由",
            Regex("val\\s+qqAlbums\\s*:").containsMatchIn(source)
        )
    }

    @Test
    fun `singer screen exposes album click callback`() {
        val source = screen.readText()
        assertTrue(
            "Screen 须接收 onAlbumClick 回调（QQMusicAlbum 参数）",
            Regex("onAlbumClick\\s*:\\s*\\(QQMusicAlbum\\)").containsMatchIn(source)
        )
        assertFalse(
            "专辑行 onClick 不能再是空实现",
            source.contains("ArtistAlbumRow(album = album, onClick = {})")
        )
    }

    @Test
    fun `navigation wires qq album click to detail route`() {
        val source = nav.readText()
        assertTrue(
            "导航层须把 onQqAlbumClick/navigateToQqMusicAlbum 接到 QQ 歌手页",
            source.contains("navigateToQqMusicAlbum") ||
                Regex("onQqAlbumClick\\s*=").containsMatchIn(source)
        )
    }
}
