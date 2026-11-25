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
 * File: moe.ouom.neriplayer.architecture/QqSingerSongManagementParadigmTest
 */

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 019 次迭代红灯：歌手页歌曲行支持通用歌曲管理操作。
 *
 * 用户反馈：QQ 歌手页长按歌曲无法做更多管理（收藏/加歌单/下一首），网易云艺术家页
 * 同样没有——这是两家共同的空白，且底层操作（收藏/加队列/下一首）都是平台无关的
 * SongItem 逻辑，歌单页已实现。本轮把"更多操作"入口下沉到共享 ArtistSongRow，
 * 让 QQ 歌手页先接上，网易云页可随后低成本复用。
 *
 * 静态扫描钉接线（项目既有范式先例），视觉/交互需真机目视。
 */
class QqSingerSongManagementParadigmTest {

    private val sharedRow = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/artist/NeteaseArtistRows.kt"
    )
    private val qqSingerScreen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/artist/QqMusicSingerDetailScreen.kt"
    )

    @Test
    fun `shared ArtistSongRow exposes optional more action`() {
        val source = sharedRow.readText()
        assertTrue(
            "共享 ArtistSongRow 需支持可选的更多操作回调（默认 null，不影响网易云/QQ首页调用方）",
            Regex("onMore\\s*:").containsMatchIn(source)
        )
    }

    @Test
    fun `QQ singer screen wires song more action`() {
        val source = qqSingerScreen.readText()
        assertTrue(
            "QQ 歌手页须给 ArtistSongRow 传 onMore，接入歌曲管理菜单",
            Regex("onMore\\s*=").containsMatchIn(source)
        )
    }

    @Test
    fun `QQ singer song operations use platform-agnostic SongItem APIs`() {
        val source = qqSingerScreen.readText()
        assertTrue("下一首须用通用 PlayerManager.addToQueueNext", source.contains("addToQueueNext"))
        assertTrue("加入队列须用通用 PlayerManager.addToQueueEnd", source.contains("addToQueueEnd"))
        assertTrue("添加到歌单须复用现成 AddSongsToPlaylistDialog", source.contains("songsToAdd"))
    }
}
