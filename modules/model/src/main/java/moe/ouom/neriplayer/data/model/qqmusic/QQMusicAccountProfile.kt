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
 * File: moe.ouom.neriplayer.data.model.qqmusic/QQMusicAccountProfile
 */

/**
 * QQ 音乐账号资料（昵称 / 头像），四音源能力对齐的统一数据模型。
 *
 * 放在 `:model` 叶子模块：platform 层的 profile repository 与后续 UI 层都要用它，
 * 且符合门禁的单向依赖（platform → model）。字段全部带默认值，不破坏既有调用方。
 */
data class QQMusicAccountProfile(
    val uin: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
)
