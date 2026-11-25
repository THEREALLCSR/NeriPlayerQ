package moe.ouom.neriplayer.data.model.music

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
 * File: moe.ouom.neriplayer.data.model.music/MusicSourceResolver
 */

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import java.util.Locale

/**
 * 一首歌的**逻辑音源**。
 *
 * 与 `PlaybackAudioSource` 的区别：后者描述"现在实际从哪家拿到音频"，
 * 可能因跨源回退而改变；这里描述"这首歌本身属于谁"，是稳定的身份信息。
 * 跨源回退需要先知道原始来源，才能标注"现在播的是另一家"。
 */
enum class MusicSource {
    QQ_MUSIC,
    CLOUD_MUSIC,
    BILIBILI,
    YOUTUBE_MUSIC,
    LOCAL,
}

/**
 * 唯一的音源判定入口。
 *
 * 改造前同一件事被写在四处，而且规则不一样：
 *
 * | 调用方 | 旧规则 | 旧数据（无 channelId、`album = "QQMusic|..."`） |
 * |---|---|---|
 * | `PlaybackMediaItemFactory` | `channelId == "qqmusic"` 或 `album.startsWith("QQMusic")` | ✅ |
 * | `AudioDownloadSourceResolver` | 只认 `channelId` | ❌ 走网易云解析器 |
 * | `CommentSourceResolver` | trim+lowercase，album 前缀忽略大小写 | ✅ |
 * | `AddSongsToPlaylistDialog` | 裸 `channelId == "qqmusic"` | ❌ 歌单目标选错 |
 *
 * 现在统一为两级规则：
 *  1. `channelId` 归一化（`trim + lowercase`）后精确匹配；
 *  2. 缺失或未知时，用 `album` 的来源前缀兜底（忽略大小写）。
 *
 * 规则只允许存在于本文件。门禁禁止在别处写 `channelId == "qqmusic"` 这类判定；
 * 协议字段（`tmeAppID = "qqmusic"`）与设置项取值不在禁止范围内。
 */
object MusicSources {

    private val CHANNEL_TO_SOURCE = mapOf(
        ListenTogetherChannels.QQ_MUSIC to MusicSource.QQ_MUSIC,
        ListenTogetherChannels.NETEASE to MusicSource.CLOUD_MUSIC,
        ListenTogetherChannels.BILIBILI to MusicSource.BILIBILI,
        // 原始值是 "youtubeMusic"；归一化后必须同样命中
        ListenTogetherChannels.YOUTUBE_MUSIC.lowercase(Locale.ROOT) to MusicSource.YOUTUBE_MUSIC,
        ListenTogetherChannels.LOCAL to MusicSource.LOCAL,
    )

    private val ALBUM_TAG_TO_SOURCE = listOf(
        SongSourceTags.QQ_MUSIC to MusicSource.QQ_MUSIC,
        SongSourceTags.BILIBILI to MusicSource.BILIBILI,
        SongSourceTags.NETEASE to MusicSource.CLOUD_MUSIC,
    )

    /**
     * @return 识别出的音源；本地歌曲与未知来源返回 null，由调用方按"不支持"处理。
     */
    fun sourceOf(song: SongItem): MusicSource? {
        val channel = song.channelId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (channel.isNotBlank()) {
            CHANNEL_TO_SOURCE[channel]?.let { return it }
        }
        return ALBUM_TAG_TO_SOURCE.firstOrNull { (tag, _) ->
            song.album.startsWith(tag, ignoreCase = true)
        }?.second
    }

    fun isQqMusic(song: SongItem): Boolean = sourceOf(song) == MusicSource.QQ_MUSIC

    fun isBili(song: SongItem): Boolean = sourceOf(song) == MusicSource.BILIBILI
}

/** 便捷入口，让调用点写成 `song.sourceOf()`。 */
fun SongItem.sourceOf(): MusicSource? = MusicSources.sourceOf(this)
