package moe.ouom.neriplayer.platform.qqmusic.track

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
 * File: moe.ouom.neriplayer.platform.qqmusic.track/QQMusicTrackMappingTest
 */

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.qqmusic.qqMusicAlbumCoverUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 歌曲模型的"六路径一致性"契约。
 *
 * 改造前同一个 songMid 从不同入口进来会得到不同的 [SongItem]：
 *  - 搜索路径不传 `subAudioId`（播放时多一次 media_mid 补查，高音质拿不到）；
 *  - 封面尺寸 300 与 800 混用；
 *  - 歌手分隔符 `"A/B"` 与 `"A / B"` 混用；
 *  - ID 规则有两份实现。
 *
 * 这些差异不会让编译失败，只会在"某个入口"上表现成"音质不对/换歌重下封面"。
 * 这里用**同一首歌**走六条入口，断言产出的 SongItem 逐字段相等。
 */
class QQMusicTrackMappingTest {

    private val songMid = "0039MnYb0qxYhV"
    private val mediaMid = "0039MnYb0qxYhV_media"
    private val albumMid = "000MkMni19ClKG"
    private val albumName = "叶惠美"
    private val title = "晴天"
    private val artist = "周杰伦"
    private val intervalSeconds = 269L

    @Test
    fun `same song from six entry points produces identical SongItem`() {
        val viaSearch = searchEntry()
        val viaPlaylist = playlistEntry()
        val viaAlbum = albumEntry()
        val viaSinger = singerEntry()
        val viaRadar = radarEntry()
        // "最近播放"存的是完整 SongItem，读回来只是复制，不应丢字段
        val viaRecent = playlistEntry().copy()

        val entries = mapOf(
            "search" to viaSearch,
            "playlist" to viaPlaylist,
            "album" to viaAlbum,
            "singer" to viaSinger,
            "radar" to viaRadar,
            "recent" to viaRecent,
        )
        entries.forEach { (name, song) ->
            assertEquals("entry=$name name", title, song.name)
            assertEquals("entry=$name artist", artist, song.artist)
            assertEquals("entry=$name album", albumName, song.album)
            assertEquals("entry=$name songMid", songMid, song.audioId)
            assertEquals("entry=$name mediaMid", mediaMid, song.subAudioId)
            assertEquals("entry=$name channel", QQ_MUSIC_CHANNEL_ID, song.channelId)
            assertEquals("entry=$name duration", intervalSeconds * 1_000L, song.durationMs)
            assertEquals("entry=$name cover", qqMusicAlbumCoverUrl(albumMid), song.coverUrl)
            assertEquals("entry=$name id", stableQqMusicSongId(songMid), song.id)
        }

        // 全字段相等（SongItem 是 data class，任何字段漂移都会在这里暴露）
        entries.values.forEach { assertEquals(viaSearch, it) }
    }

    @Test
    fun `wrapper unwrapping handles songInfo trackInfo and bare objects`() {
        val bare = parseQqMusicTrack(songJson())
        val wrappedSongInfo = parseQqMusicTrackWrapper(JSONObject().put("songInfo", songJson()))
        val wrappedTrackInfo = parseQqMusicTrackWrapper(JSONObject().put("trackInfo", songJson()))

        assertEquals(bare, wrappedSongInfo)
        assertEquals(bare, wrappedTrackInfo)
    }

    @Test
    fun `track without id is dropped instead of entering the queue`() {
        val noId = JSONObject().put("name", title)
        assertNull(parseQqMusicTrack(noId))
        assertNull(parseQqMusicTrackWrapper(JSONObject().put("songInfo", noId)))
    }

    @Test
    fun `missing media mid stays null instead of faking songMid`() {
        val json = songJson().apply { remove("file") }
        val track = parseQqMusicTrack(json)!!

        assertNull("mediaMid must not be fabricated", track.mediaMid)
        assertNull("subAudioId must stay null", track.toSongItem().subAudioId)
    }

    @Test
    fun `blank media mid is normalized to null`() {
        val json = songJson().apply { put("file", JSONObject().put("media_mid", "   ")) }
        assertNull(parseQqMusicTrack(json)!!.mediaMid)
    }

    @Test
    fun `media mid falls back to the top level field`() {
        val json = songJson().apply {
            remove("file")
            put("media_mid", mediaMid)
        }
        assertEquals(mediaMid, parseQqMusicTrack(json)!!.mediaMid)
    }

    @Test
    fun `duration text parses into milliseconds`() {
        assertEquals(269_000L, parseQqMusicDurationMs("4:29"))
        assertEquals(269_000L, parseQqMusicDurationMs("04:29"))
        assertEquals(3_723_000L, parseQqMusicDurationMs("1:02:03"))
        assertEquals(0L, parseQqMusicDurationMs(""))
        assertEquals(0L, parseQqMusicDurationMs("unknown"))
        assertEquals(0L, parseQqMusicDurationMs("4:xx"))
    }

    @Test
    fun `stable id is deterministic and never zero`() {
        assertEquals(stableQqMusicSongId(songMid), stableQqMusicSongId(songMid))
        assertNotEquals(stableQqMusicSongId(songMid), stableQqMusicSongId("$songMid-other"))
        // 0 会被 SongItem 当成"无 ID"
        assertTrue(stableQqMusicSongId(songMid) != 0L)
    }

    // ── 六条入口的构造 ──────────────────────────────────────────────────────

    private fun searchEntry(): SongItem = SongSearchInfo(
        id = songMid,
        songName = title,
        singer = artist,
        duration = "4:29",
        source = MusicPlatform.QQ_MUSIC,
        albumName = albumName,
        coverUrl = qqMusicAlbumCoverUrl(albumMid),
        mediaId = mediaMid,
    ).toQqMusicSongItem()

    private fun playlistEntry(): SongItem = parseQqMusicTrack(songJson())!!.toSongItem()

    private fun albumEntry(): SongItem =
        parseQqMusicTrackWrapper(JSONObject().put("songInfo", songJson()))!!.toSongItem()

    private fun singerEntry(): SongItem =
        parseQqMusicTrackWrapper(JSONObject().put("trackInfo", songJson()))!!.toSongItem()

    private fun radarEntry(): SongItem = parseQqMusicTrack(songJson())!!.toSongItem()

    /** 各接口共有的字段形态；差异只在包装层，因此复用同一份 JSON。 */
    private fun songJson(): JSONObject = JSONObject(
        """
        {
          "mid": "$songMid",
          "name": "$title",
          "singer": [{"name": "$artist"}],
          "album": {"name": "$albumName", "mid": "$albumMid"},
          "interval": $intervalSeconds,
          "file": {"media_mid": "$mediaMid"}
        }
        """.trimIndent()
    )
}
