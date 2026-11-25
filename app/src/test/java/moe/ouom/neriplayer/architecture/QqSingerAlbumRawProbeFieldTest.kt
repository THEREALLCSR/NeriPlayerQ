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
 * File: moe.ouom.neriplayer.architecture/QqSingerAlbumRawProbeFieldTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 026 次迭代红灯：QQ 歌手专辑 raw 诊断探针须读真机证实的 albumList 字段。
 *
 * 背景（真机铁证，第 022 次迭代）：
 * - 歌手专辑接口 `music.musichallAlbum.AlbumListServer / GetAlbumList` 响应 data 的
 *   顶层 keys = ["singerMid", "albumList", "total"]，专辑数组字段是 **albumList**；
 * - 生产解析 `QQMusicLibraryRepository.singerAlbums()` 第 023 次已改为 albumList 优先。
 *
 * 缺陷：
 * - 诊断探针屏 `QqMusicApiProbeViewModel.callSingerAlbumsRawAndCopy()` 仍只读
 *   `data.optJSONArray("albums")`——真机数据里根本没有 albums 这个 key，
 *   导致它输出的 albumsCount 恒为 0、firstAlbumAllKeys 恒为空数组；
 * - 后果：这个探针自诞生起就从未拿到过专辑项级别字段，歌曲数到底是
 *   songNum / song_count / 其它，始终无从真机验证——这正是"专辑项显示 0 首"
 *   遗留问题久拖未决的根因。诊断工具自身失效，属真实缺陷，非人造红灯。
 *
 * 修复目标：探针须 albumList 优先、albums 兜底（与生产解析口径一致），
 * 从而真机点一次按钮即可拿到专辑项所有字段，一锤定音确定歌曲数字段名。
 *
 * 静态扫描钉接线事实；专辑项真实字段值由真机点探针按钮后确认，不猜字段。
 */
class QqSingerAlbumRawProbeFieldTest {

    private val probeVm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/debug/QqMusicApiProbeViewModel.kt"
    )

    /** 探针函数须读 albumList（真机铁证字段），否则 albumsCount 恒 0、拿不到专辑项。 */
    @Test
    fun `singer albums raw probe reads albumList field`() {
        val source = probeVm.readText()
        val body = source.substringAfter("fun callSingerAlbumsRawAndCopy")
            .substringBefore("\n    fun ")
        assertTrue(
            "探针 callSingerAlbumsRawAndCopy 须读 albumList 字段（真机铁证顶层 key），" +
                "不能只读 albums——否则真机 albumsCount 恒 0、专辑项字段永远拿不到",
            Regex("optJSONArray\\(\\s*\"albumList\"\\s*\\)").containsMatchIn(body)
        )
    }

    /** 专辑项级别字段提取须基于 albumList 的首项，而非空数组的 albums。 */
    @Test
    fun `first album fields are extracted from albumList`() {
        val source = probeVm.readText()
        val body = source.substringAfter("fun callSingerAlbumsRawAndCopy")
            .substringBefore("\n    fun ")
        assertTrue(
            "探针须从 albumList 取首项做字段提取（firstAlbumAllKeys），" +
                "真机才能看到专辑项级别字段（含歌曲数）",
            Regex("albumList[^\\n]*optJSONObject\\(0\\)|optJSONArray\\(\\s*\"albumList\"[^\\n]*\\)")
                .containsMatchIn(body)
        )
    }

    /** 保留 albums 兜底，避免其它响应形态下探针直接崩。 */
    @Test
    fun `probe keeps albums as fallback`() {
        val source = probeVm.readText()
        val body = source.substringAfter("fun callSingerAlbumsRawAndCopy")
            .substringBefore("\n    fun ")
        assertTrue(
            "探针须保留 albums 兜底（albumList ?: albums），与其它响应形态兼容",
            Regex("optJSONArray\\(\\s*\"albumList\"\\s*\\)\\s*\\?:\\s*data\\.optJSONArray\\(\\s*\"albums\"\\s*\\)")
                .containsMatchIn(body)
        )
    }
}
