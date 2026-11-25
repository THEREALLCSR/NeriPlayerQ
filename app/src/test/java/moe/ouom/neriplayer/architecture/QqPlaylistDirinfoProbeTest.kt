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
 * File: moe.ouom.neriplayer.architecture/QqPlaylistDirinfoProbeTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 031 次迭代红灯：歌单详情探针须展开 `dirinfo`（播放量所在对象）。
 *
 * 真机取证（第 030 次，dissid=7011264340）：
 *   dataAllKeys = [code, subcode, msg, ..., **dirinfo**, songlist, songlist_size,
 *                  total_song_num, ...]
 *   playRelatedFields = {filter_song_num:0, total_song_num:76, quickListenVid:[]}
 *   —— 播放量不在 data 顶层，而在 `dirinfo` 对象里（歌单标题/创建者/播放量的载体）。
 *   上一版探针只输出顶层字段名，看不到 dirinfo 内部，取证卡住。
 *
 * 静态钉住：探针须输出 dirinfo 的完整字段名 + dirinfo 完整原始 JSON，且排在前面，
 * 确保 4000 字符预览内能读到播放量字段真值。
 */
class QqPlaylistDirinfoProbeTest {

    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/debug/QqMusicApiProbeViewModel.kt"
    )

    @Test
    fun `playlist detail probe exposes dirinfo keys and raw json`() {
        val body = vm.readText().substringAfter("fun callPlaylistDetailRawAndCopy")
            .substringBefore("\n    fun ")
        assertTrue(
            "探针须输出 dirinfo 的完整字段名（dirinfoAllKeys，用 allKeys 不过滤）",
            Regex("dirinfoAllKeys[^\\n]*allKeys\\(\\s*dirinfo\\s*\\)").containsMatchIn(body)
        )
        assertTrue(
            "探针须输出 dirinfo 完整原始 JSON（dirinfoRaw），真机才能读到播放量字段真值",
            Regex("dirinfoRaw").containsMatchIn(body)
        )
    }

    @Test
    fun `dirinfo output is placed before bulky fields`() {
        val body = vm.readText().substringAfter("fun callPlaylistDetailRawAndCopy")
            .substringBefore("\n    fun ")
        val idxDirinfo = body.indexOf("dirinfoAllKeys")
        val idxDataKeys = body.indexOf("dataAllKeys")
        assertTrue(
            "dirinfo 字段须排在 dataAllKeys 之前，避免被 4000 字符预览截断",
            idxDirinfo >= 0 && idxDataKeys >= 0 && idxDirinfo < idxDataKeys
        )
    }
}
