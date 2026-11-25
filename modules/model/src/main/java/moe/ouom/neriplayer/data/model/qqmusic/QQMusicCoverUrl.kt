package moe.ouom.neriplayer.data.model.qqmusic

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
 * File: moe.ouom.neriplayer.data.model.qqmusic/QQMusicCoverUrl
 */

/**
 * QQ 音乐封面地址（唯一实现）。
 *
 * 放在 `:model` 而不是 `:platform`：搜索接口（`platform.search.api`）与
 * QQ 仓库（`platform.qqmusic`）都要用它，而门禁不允许前者引用后者的包。
 *
 * 尺寸默认 500：改造前歌单用 300、搜索用 800，同一首歌经不同入口拿到不同 URL，
 * 图片缓存各存一份，播放页清晰度也跟着入口变。统一后六条入口才能"全字段相等"。
 */
fun qqMusicAlbumCoverUrl(albumMid: String?, size: Int = QQ_MUSIC_COVER_DEFAULT_SIZE): String? =
    albumMid?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { "https://y.qq.com/music/photo_new/T002R${size}x${size}M000$it.jpg" }

const val QQ_MUSIC_COVER_DEFAULT_SIZE: Int = 500
