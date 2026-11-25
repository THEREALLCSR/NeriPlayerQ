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
 * File: moe.ouom.neriplayer.architecture/QqRawFieldProbeParadigmTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 028 次迭代红灯：诊断探针必须暴露完整字段，不能再自己过滤/截断。
 *
 * 上一轮盲点（真机复盘）：
 * - `callSingerAlbumsRawAndCopy` 的 relatedFields 用 hints
 *   (play/album/mid/name/cnt/total) 过滤——`songNum` 等歌曲数字段不含这些词被丢弃；
 * - 完整字段列表 `firstAlbumAllKeys` 排在 JSON 末尾，被 lastJsonPreview 的
 *   4000 字符截断（dataRelatedFields 展开了 30 个专辑占满预算），从未真机读到。
 * - 用户线索"搜索界面能显示专辑数量" + 真机 searchAlbums 的 songCount=1 证明
 *   搜索接口有真值，但歌手页 GetAlbumList 字段不同，必须先拿到完整字段列表才能定修法。
 *
 * 本测试静态钉住：
 * 1. 专辑 raw 探针输出首项完整字段（allKeys 不过滤）且放在 JSON 最前面；
 * 2. 新增两个搜索 raw 探针按钮（专辑/歌单），调用 platform 的 searchGroupRaw；
 * 3. 调试屏有对应按钮接线。
 */
class QqRawFieldProbeParadigmTest {

    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/debug/QqMusicApiProbeViewModel.kt"
    )
    private val screen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/debug/QqMusicApiProbeScreen.kt"
    )

    /** 专辑 raw 探针：firstAlbumAllKeys 须用 allKeys（不过滤）且排在输出最前面。 */
    @Test
    fun `singer albums probe exposes full field keys first unfiltered`() {
        val body = vm.readText().substringAfter("fun callSingerAlbumsRawAndCopy")
            .substringBefore("\n    fun ")
        assertTrue(
            "专辑探针须输出完整首项字段名 allKeys(first)，不能用 relatedFields 过滤丢字段",
            Regex("firstAlbumAllKeys[^\\n]*allKeys\\(\\s*first\\s*\\)").containsMatchIn(body)
        )
        val idxFull = body.indexOf("firstAlbumAllKeys")
        val idxRelated = body.indexOf("dataRelatedFields")
        assertTrue(
            "完整字段列表 firstAlbumAllKeys 须排在 dataRelatedFields 之前，" +
                "否则展开 30 个专辑会把关键字段挤出 4000 字符预览被截断",
            idxFull >= 0 && (idxRelated < 0 || idxFull < idxRelated)
        )
    }

    /** 专辑 raw 探针：须输出首项完整原始 JSON（含所有值，尤其歌曲数字段真值）。 */
    @Test
    fun `singer albums probe outputs first item raw json`() {
        val body = vm.readText().substringAfter("fun callSingerAlbumsRawAndCopy")
            .substringBefore("\n    fun ")
        assertTrue(
            "专辑探针须把首项完整原始 JSON 放输出里（firstAlbumRaw/first 直接 put），" +
                "真机才能读到 songNum 等字段的真值",
            Regex("firstAlbumRaw[^\\n]*\\bfirst\\b|\\.put\\(\\s*\"firstAlbumRaw\"").containsMatchIn(body)
        )
    }

    /** VM 须新增调用 platform searchGroupRaw 的搜索原始字段探针函数。 */
    @Test
    fun `view model adds search raw field probe`() {
        val source = vm.readText()
        assertTrue(
            "VM 须调用 searchGroupRaw 拿搜索接口原始字段（专辑/歌单），" +
                "绕开 searchAlbums/searchPlaylists 的固定字段解析",
            source.contains("searchGroupRaw(")
        )
    }

    /** 调试屏须有两个搜索 raw 按钮（复制专辑原始字段 / 复制歌单原始字段）。 */
    @Test
    fun `debug screen wires search raw buttons`() {
        val source = screen.readText()
        assertTrue(
            "调试屏须接线搜索专辑原始字段按钮",
            Regex("搜索专辑原始字段|callSearchAlbumRaw").containsMatchIn(source)
        )
        assertTrue(
            "调试屏须接线搜索歌单原始字段按钮（拿播放量真值）",
            Regex("搜索歌单原始字段|callSearchPlaylistRaw").containsMatchIn(source)
        )
    }
}
