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
 * File: moe.ouom.neriplayer.architecture/QqAlbumSongCountEnrichmentTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 029 次迭代红灯：QQ 歌手页专辑歌曲数补数接线。
 *
 * 真机结论（2026-11-25）：
 * - 歌手页 GetAlbumList 专辑项无歌曲数字段，totalNum 恒 0 → 列表显示"0 首"；
 * - 专辑详情接口 totalNum 是真值（真机 total=49），用它逐专辑补数；
 * - UI 侧 `ArtistAlbumRow` 已用 `AlbumSummary.size` 显示"N 首"，
 *   VM 只要回填 `qqAlbums[].songCount` 并同步 `albums` 即可生效（无需改 UI）。
 *
 * 设计要求（静态钉住）：
 * 1. 调 repository.albumSongCount 取真值；
 * 2. 回填 QQMusicAlbum.copy(songCount=...)，并同步 albums（toAlbumSummary）；
 * 3. 分批并发（chunked），避免 30 张专辑一次性打 30 个请求；
 * 4. runCatching 降级：单张失败不影响其它专辑，也不拖垮首屏；
 * 5. 切换歌手/重载时取消补数 Job，避免旧歌手结果串台。
 */
class QqAlbumSongCountEnrichmentTest {

    private val vm = File(
        "src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/QqSingerDetailViewModel.kt"
    )

    @Test
    fun `view model loads album song count from detail api`() {
        val source = vm.readText()
        assertTrue(
            "VM 须调用 repository.albumSongCount 拿专辑真实歌曲数（GetAlbumList 无此字段）",
            source.contains("albumSongCount(")
        )
    }

    @Test
    fun `view model backfills song count into album state`() {
        val source = vm.readText()
        assertTrue(
            "补数结果须回填到 QQMusicAlbum.copy(songCount=...)，UI 用 AlbumSummary.size 显示",
            Regex("copy\\(\\s*songCount\\s*=").containsMatchIn(source)
        )
        assertTrue(
            "回填后须同步 albums（重新 map toAlbumSummary），否则专辑行仍显示旧值",
            Regex("albums\\s*=\\s*[a-zA-Z]*[Aa]lbums\\.map").containsMatchIn(source)
        )
    }

    @Test
    fun `album enrichment is batched and failure-tolerant`() {
        val source = vm.readText()
        assertTrue(
            "补数须分批并发（chunked），避免 30 张专辑一次性发 30 个请求",
            source.contains("chunked(")
        )
        assertTrue(
            "单张专辑补数失败须降级（runCatching），不能拖垮整页",
            Regex("runCatching\\s*\\{\\s*repository\\.albumSongCount").containsMatchIn(source) ||
                Regex("runCatching[^\\n]*albumSongCount").containsMatchIn(source)
        )
    }

    @Test
    fun `album enrichment job is cancelled on reload`() {
        val source = vm.readText()
        assertTrue(
            "须有独立补数 Job 并在重载/切换歌手时取消，避免旧歌手结果串台",
            Regex("albumCountJob\\s*\\?\\.\\s*cancel\\(\\)").containsMatchIn(source) ||
                Regex("albumCountJob\\s*=\\s*viewModelScope\\.launch").containsMatchIn(source)
        )
    }
}
