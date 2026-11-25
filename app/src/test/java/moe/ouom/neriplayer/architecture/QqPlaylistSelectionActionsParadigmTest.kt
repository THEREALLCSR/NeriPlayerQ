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
 * File: moe.ouom.neriplayer.architecture/QqPlaylistSelectionActionsParadigmTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 016 次迭代红灯：QQ 歌单页多选操作条漏接"全选"能力。
 *
 * 现成组件 [PlaylistSelectionActions]（ui/component/playlist/PlaylistSelectionActions.kt:28）
 * 已内含全选（CheckBox / CheckBoxOutlineBlank + onToggleSelectAll），网易云/B站/YouTube
 * 歌单页均已接入；唯 QQ 页选择条自己手写 4 个 IconButton（下载/加歌单/队列/移除），
 * 没有全选入口——用户长按能多选但无法一键全选。
 *
 * 本测试沿用项目既有的"范式统一静态扫描"先例（QqUiStateCollectionParadigmTest /
 * QqDetailViewModelFactoryParadigmTest）：钉住 QQ 页必须复用该现成组件并接上全选回调。
 * 这是接线检查，不验证视觉；视觉形态由装机真机目视确认。
 */
class QqPlaylistSelectionActionsParadigmTest {

    private val qqScreen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/playlist/QqMusicPlaylistDetailScreen.kt"
    )

    @Test
    fun `QQ playlist screen reuses shared PlaylistSelectionActions component`() {
        val source = qqScreen.readText()
        assertTrue(
            "QQ 歌单页应复用现成的 PlaylistSelectionActions 组件（含全选/导出/插入等操作），" +
                "而不是自己手写残缺的多选按钮条",
            source.contains("PlaylistSelectionActions(")
        )
    }

    @Test
    fun `QQ playlist screen wires toggle-select-all callback`() {
        val source = qqScreen.readText()
        assertTrue(
            "全选回调必须接上：PlaylistSelectionActions 的 onToggleSelectAll 需要传入实际切换逻辑",
            source.contains("onToggleSelectAll")
        )
    }

    @Test
    fun `QQ playlist screen has no stale hand-rolled select-all icon without shared component`() {
        val source = qqScreen.readText()
        // 防止"只是 import 了组件但没用"或"加了全选图标却没接现成组件"的假绿：
        // 组件接入与全选回调必须同时存在。
        val hasComponent = source.contains("PlaylistSelectionActions(")
        val hasCallback = source.contains("onToggleSelectAll")
        assertTrue("组件与全选回调必须同时接入（$hasComponent / $hasCallback）", hasComponent && hasCallback)
    }
}
