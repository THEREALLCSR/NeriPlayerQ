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
 * File: moe.ouom.neriplayer.architecture/QqSingerDetailEnrichmentParadigmTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 017 次迭代红灯：QQ 歌手详情页接入上一轮补齐的 singerDetail / singerAlbums API。
 *
 * 前置 API（已就绪）：QQMusicLibraryRepository.singerDetail(singerMid) 返回简介
 * QQMusicSingerDetail.description；singerAlbums(singerMid, offset, limit) 返回专辑列表。
 * 但 app 层零调用——用户看到"歌手详情页简陋：无百科简介、无法查看专辑"。
 *
 * 断言分两层（都是可机械判定的接线事实，非凭空造符号）：
 * 1) VM 真实调用了两个 API 并把结果暴露进 UiState；
 * 2) Screen 渲染简介文本与专辑 Tab。
 * 沿用项目既有的 QqUiStateCollectionParadigmTest / QqDetailViewModelFactoryParadigmTest
 * 静态扫描范式。视觉形态仍需装机真机目视，静态测试只钉接线。
 */
class QqSingerDetailEnrichmentParadigmTest {

    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/QqSingerDetailViewModel.kt"
    )
    private val screen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/artist/QqMusicSingerDetailScreen.kt"
    )

    @Test
    fun `view model loads singer description from repository`() {
        val source = vm.readText()
        assertTrue(
            "VM 必须调用 repository.singerDetail 拉取歌手简介（API 已就绪，此前零调用）",
            source.contains("repository.singerDetail(")
        )
        assertTrue(
            "UiState 必须承载简介字段供页面渲染",
            Regex("val\\s+description\\s*:").containsMatchIn(source)
        )
    }

    @Test
    fun `view model loads singer albums from repository`() {
        val source = vm.readText()
        assertTrue(
            "VM 必须调用 repository.singerAlbums 拉取歌手专辑",
            source.contains("repository.singerAlbums(")
        )
        assertTrue(
            "UiState 必须承载专辑列表供页面展示",
            Regex("val\\s+albums\\s*:").containsMatchIn(source)
        )
    }

    @Test
    fun `screen renders singer description and albums tab`() {
        val source = screen.readText()
        assertTrue(
            "歌手页必须渲染简介文本（原 QqSingerProfileCard 只有头像/名字/作品数）",
            source.contains("description") || source.contains("ArtistBriefDescription")
        )
        assertTrue(
            "歌手页必须有歌曲/专辑 Tab（网易云已有 ArtistWorkTabs，QQ 缺）",
            source.contains("ArtistWorkTabs") || source.contains("TabRow")
        )
    }
}
