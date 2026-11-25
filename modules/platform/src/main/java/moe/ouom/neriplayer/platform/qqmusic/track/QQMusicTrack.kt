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
 * File: moe.ouom.neriplayer.platform.qqmusic.track/QQMusicTrack
 */

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.qqmusic.qqMusicAlbumCoverUrl
import org.json.JSONObject

/**
 * QQ 音乐歌曲的唯一模型（本地 fork 新增）。
 *
 * 此前同一个概念有两套：`library` 包的 `QQMusicTrack`（歌单/专辑/歌手/雷达）
 * 与搜索接口的 `SongSearchInfo`。字段名、封面尺寸、字段兜底顺序都不一样，
 * 于是同一首歌从不同页面进入播放器时，`SongItem` 的 `subAudioId`（media_mid）
 * 时有时无——表现是"某些入口的高音质永远拿不到"。
 *
 * 现在所有入口都先收敛到本类，再由 [toSongItem] 唯一映射。
 */
data class QQMusicTrack(
    val songMid: String,
    /** 高音质文件名要用的 media_mid；搜索/歌单接口都不保证下发。 */
    val mediaMid: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val albumMid: String?,
    val coverUrl: String?,
    val durationMs: Long,
)

/** QQ 音源的频道标识；只在这里定义，其它地方一律引用。 */
const val QQ_MUSIC_CHANNEL_ID: String = ListenTogetherChannels.QQ_MUSIC

/**
 * QQ 歌曲在 [SongItem] 里的稳定 ID。
 *
 * 规则**必须与改造前逐字节一致**：历史收藏/最近播放/下载目录都用它做键，
 * 换算法等于让老数据指向另一首歌。0 会被 `SongItem` 当成"无 ID"，映射为 -1。
 */
fun stableQqMusicSongId(songMid: String): Long =
    songMid.hashCode().toLong().let { if (it == 0L) -1L else it }

/** 唯一映射：QQ 歌曲 → 播放内核认识的 [SongItem]。 */
fun QQMusicTrack.toSongItem(): SongItem = SongItem(
    id = stableQqMusicSongId(songMid),
    name = title,
    artist = artist,
    album = album.orEmpty(),
    albumId = 0L,
    durationMs = durationMs,
    coverUrl = coverUrl,
    channelId = QQ_MUSIC_CHANNEL_ID,
    audioId = songMid,
    // 高音质文件名必须用它；空值就如实留空，让解析层退回 songmid 规则。
    subAudioId = mediaMid?.trim()?.takeIf(String::isNotBlank),
)

/**
 * 搜索结果 → [SongItem]。
 *
 * 走同一个 [stableQqMusicSongId] 与同一个频道标识，保证"搜到的"和"歌单里的"
 * 是同一首歌。`duration` 是 `m:ss` 文本，这里解析成毫秒。
 */
fun SongSearchInfo.toQqMusicSongItem(): SongItem = SongItem(
    id = stableQqMusicSongId(id),
    name = songName,
    artist = singer,
    album = albumName.orEmpty(),
    albumId = 0L,
    durationMs = parseQqMusicDurationMs(duration),
    coverUrl = coverUrl,
    channelId = QQ_MUSIC_CHANNEL_ID,
    audioId = id,
    subAudioId = mediaId?.trim()?.takeIf(String::isNotBlank),
)

/** `m:ss` / `mm:ss` → 毫秒；解析不了返回 0（不抛，搜索页不能因此崩）。 */
internal fun parseQqMusicDurationMs(duration: String): Long {
    val parts = duration.trim().split(':')
    if (parts.size != 2 && parts.size != 3) return 0L
    val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
    return when (numbers.size) {
        2 -> numbers[0] * 60_000L + numbers[1] * 1_000L
        else -> numbers[0] * 3_600_000L + numbers[1] * 60_000L + numbers[2] * 1_000L
    }
}

/**
 * 解析一首 QQ 歌曲对象（唯一实现）。
 *
 * 兼容接口差异（都是实测形态，不是猜测）：
 *  - ID 字段：`mid` / `songmid` / `songMid`；
 *  - 标题：`name` / `title` / `songname`；
 *  - media_mid：`file.media_mid`，少数接口提到顶层 `media_mid`；
 *  - 时长：`interval`（秒）；
 *  - 专辑：`album.name` + `album.mid`，或顶层 `albumMid/albumName`。
 *
 * @return 缺少 ID 时返回 null（没有 ID 的歌无法播放，也不该进列表）。
 */
fun parseQqMusicTrack(song: JSONObject): QQMusicTrack? {
    val songMid = sequenceOf("mid", "songmid", "songMid")
        .map { song.optString(it) }
        .firstOrNull(String::isNotBlank)
        ?: return null
    val mediaMid = sequenceOf(
        song.optJSONObject("file")?.optString("media_mid"),
        song.optString("media_mid"),
        song.optString("mediaMid"),
    )
        .map { it.orEmpty().trim() }
        .firstOrNull(String::isNotBlank)
    val singers = song.optJSONArray("singer")?.let { array ->
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.optString("name")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.joinToString(" / ")
    }.orEmpty()
    val album = song.optJSONObject("album")
    val albumMid = sequenceOf(
        album?.optString("mid"),
        song.optString("albumMid"),
        song.optString("albummid"),
    )
        .map { it.orEmpty().trim() }
        .firstOrNull(String::isNotBlank)
    val albumName = sequenceOf(
        album?.optString("name"),
        song.optString("albumName"),
        song.optString("albumname"),
    )
        .map { it.orEmpty().trim() }
        .firstOrNull(String::isNotBlank)
    return QQMusicTrack(
        songMid = songMid,
        mediaMid = mediaMid,
        title = sequenceOf("name", "title", "songname")
            .map { song.optString(it) }
            .firstOrNull(String::isNotBlank) ?: "未知歌曲",
        artist = singers.ifBlank { "未知艺术家" },
        album = albumName,
        albumMid = albumMid,
        coverUrl = qqMusicAlbumCoverUrl(albumMid),
        durationMs = song.optLong("interval", 0L).coerceAtLeast(0L) * 1_000L,
    )
}

/**
 * 解析列表项：兼容 `{ songInfo: {...} }`、`{ trackInfo: {...} }` 与裸对象三种包装。
 *
 * 专辑曲目、歌手作品、雷达接口的包装层各不相同，这里一次收口。
 */
fun parseQqMusicTrackWrapper(item: JSONObject): QQMusicTrack? {
    val inner = item.optJSONObject("songInfo")
        ?: item.optJSONObject("trackInfo")
        ?: item.optJSONObject("song")
    return parseQqMusicTrack(inner ?: item)
}
