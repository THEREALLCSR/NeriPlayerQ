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
 * File: moe.ouom.neriplayer.architecture/QqPlaylistPlayCountEnrichmentTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 032 次迭代红灯：媒体库 QQ 歌单播放量补数接线。
 *
 * 真机铁证（2026-11-25）：
 * - 媒体库歌单列表 `userPlaylists`（v_playlist）的 `play_cnt` 恒 0；
 * - 歌单详情 `CgiGetDiss` 的 `dirinfo.listennum` 是真值（真机 146608 = 14.6万）。
 *
 * UI 侧 `LibraryScreenQqMusicContent` 已用 `formatPlayCount(playlist.playCount)` 显示，
 * 故只需 VM 回填 `QQMusicPlaylist.playCount`（UI 零改动）。
 *
 * 静态钉住：
 * 1. LibraryViewModel 调 repository.playlistPlayCount；
 * 2. 回填 copy(playCount = ...)；
 * 3. chunked 分批 + runCatching 降级（不拖垮列表）；
 * 4. 独立 Job 并在刷新时取消；
 * 5. 进程内缓存（避免反复进出媒体库重复请求）。
 */
class QqPlaylistPlayCountEnrichmentTest {

    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/LibraryViewModel.kt"
    )

    @Test
    fun `library view model loads playlist play count`() {
        val source = vm.readText()
        assertTrue(
            "LibraryViewModel 须调用 repository.playlistPlayCount 拿真值（v_playlist 的 play_cnt 恒 0）",
            source.contains("playlistPlayCount(")
        )
    }

    @Test
    fun `library view model backfills play count into playlist state`() {
        val source = vm.readText()
        assertTrue(
            "补数须回填 QQMusicPlaylist.copy(playCount = ...)，UI 用 formatPlayCount 显示",
            Regex("copy\\(\\s*playCount\\s*=").containsMatchIn(source)
        )
    }

    @Test
    fun `play count enrichment is batched and failure tolerant`() {
        val source = vm.readText()
        assertTrue(
            "补数须分批并发（chunked），避免歌单多时一次性打满请求",
            source.contains("chunked(")
        )
        assertTrue(
            "单条歌单补数失败须降级（runCatching），不能拖垮媒体库列表",
            Regex("runCatching[^\\n]*playlistPlayCount").containsMatchIn(source) ||
                Regex("runCatching\\s*\\{\\s*[^}]*playlistPlayCount").containsMatchIn(source)
        )
    }

    @Test
    fun `play count enrichment has cache and cancellable job`() {
        val source = vm.readText()
        assertTrue(
            "须有进程内缓存（Map）避免反复进出媒体库重复补数",
            Regex("(mutableMapOf|HashMap|ConcurrentHashMap)\\s*<\\s*String\\s*,\\s*Long\\s*>")
                .containsMatchIn(source)
        )
        assertTrue(
            "须有独立补数 Job 并在刷新时取消，避免旧结果覆盖新列表",
            Regex("qqPlayCountJob\\s*\\?\\s*\\.\\s*cancel\\(\\)").containsMatchIn(source) ||
                Regex("qqPlayCountJob\\s*=\\s*viewModelScope\\.launch").containsMatchIn(source)
        )
    }
}
