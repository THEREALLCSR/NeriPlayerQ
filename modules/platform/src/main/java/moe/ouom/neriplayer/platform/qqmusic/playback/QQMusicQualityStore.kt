package moe.ouom.neriplayer.platform.qqmusic.playback

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
 * File: moe.ouom.neriplayer.platform.qqmusic.playback/QQMusicQualityStore
 */

import android.content.Context

/**
 * QQ 音乐音质偏好的本地持久化。
 *
 * 为什么不走 SettingsRepository 的 DataStore：
 *  - 项目的设置项由 AutoSettingsSchema 生成，加一个键要动 schema/快照/迁移；
 *  - QQ 音质只有三档，用一个独立的小 SharedPreferences 就够，
 *    也不会和网易云/哔哩哔哩的音质偏好互相影响。
 *
 * 读取时机：PlayerManager 初始化拿到 Application 后恢复；
 * 写入时机：用户在播放页切换音质时。
 */
class QQMusicQualityStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 上次选择的档位；没有记录过时返回 null（调用方回退到默认"无损"）。 */
    fun read(): String? {
        val value = preferences.getString(KEY_QUALITY, null)?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    /** 记录档位；只接受 QQ 支持的三档，避免脏数据。 */
    fun write(qualityKey: String) {
        val normalized = qualityKey.trim().lowercase()
        if (normalized !in SUPPORTED_QUALITIES) return
        preferences.edit().putString(KEY_QUALITY, normalized).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "qqmusic_quality"
        const val KEY_QUALITY = "preferred_quality"
        val SUPPORTED_QUALITIES = setOf("lossless", "exhigh", "standard")
    }
}
