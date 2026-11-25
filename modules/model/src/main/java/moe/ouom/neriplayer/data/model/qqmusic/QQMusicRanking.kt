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
 * File: moe.ouom.neriplayer.data.model.qqmusic/QQMusicRanking
 */

/**
 * QQ 音乐排行榜条目（GetAll 下发的 toplist 项），四音源能力对齐的统一数据模型。
 *
 * 放在 `:model` 叶子模块：platform 层的 ranking repository 与后续 UI 层都要用它，
 * 且符合门禁的单向依赖（platform → model）。字段全部带默认值，不破坏既有调用方。
 */
data class QQMusicRanking(
    val id: Long = 0L,
    val title: String = "",
    val coverUrl: String = "",
)
