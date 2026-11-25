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
 * File: moe.ouom.neriplayer.architecture/QqPlaylistDetailRawProbeTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 030 次迭代红灯：歌单详情原始字段探针接线（找播放量真值）。
 *
 * 背景：媒体库歌单列表 v_playlist 的 play_cnt 恒 0，搜索引擎有 listennum 真值
 * 但覆盖不到"我的歌单"。歌单详情 CgiGetDiss 的播放量字段名无一手证据，
 * 按 D6 不猜字段——先加 raw 探针拿原始 data，真机看字段名与真值。
 *
 * 静态钉住：VM 调 playlistDetailRaw + 调试屏有对应按钮。
 */
class QqPlaylistDetailRawProbeTest {

    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/debug/QqMusicApiProbeViewModel.kt"
    )
    private val screen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/debug/QqMusicApiProbeScreen.kt"
    )

    @Test
    fun `view model probes playlist detail raw`() {
        val source = vm.readText()
        assertTrue(
            "VM 须新增叫 playlistDetailRaw 的探针函数，拿歌单详情原始 data（含播放量候选字段）",
            source.contains("playlistDetailRaw(")
        )
        assertTrue(
            "探针函数名须可辨识（callPlaylistDetailRawAndCopy）",
            source.contains("callPlaylistDetailRawAndCopy")
        )
    }

    @Test
    fun `debug screen wires playlist detail raw button`() {
        val source = screen.readText()
        assertTrue(
            "调试屏须有「复制歌单详情原始字段（诊断）」按钮并接到 VM",
            Regex("歌单详情原始字段").containsMatchIn(source) &&
                source.contains("callPlaylistDetailRawAndCopy")
        )
    }
}
