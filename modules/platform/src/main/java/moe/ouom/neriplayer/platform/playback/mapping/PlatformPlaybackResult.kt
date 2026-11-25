package moe.ouom.neriplayer.platform.playback.mapping

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
 * File: moe.ouom.neriplayer.platform.playback.mapping/PlatformPlaybackResult
 */

/**
 * 三家音源（B站 / 网易云 / YouTube）共用的取流结果。
 *
 * 改造前，这三家把"无资源 / 需登录 / 网络失败"全部折叠成 `SongUrlResult.Failure`，
 * 上层只能弹出同一句无差别报错。这里以四个语义分支把失败原因保真地传给播放层，
 * 与 QQ 音乐的 `QQMusicPlaybackResult` 对齐（QQ 的 5 分支里 LoginExpired 是它特有的）。
 *
 * 采用 sealed 而不是普通 data class：mapper 的 `when` 必须穷尽，
 * 以后新增语义分支时编译器会强制所有 mapper 同步处理，不会再静默丢语义。
 */
sealed interface PlatformPlaybackResult {
    /** 拿到可直接播放的地址。字段与 `SongUrlResult.Success` 对齐，方便后续适配层转换。 */
    data class Success(
        val url: String,
        val candidateUrls: List<String> = emptyList(),
        val mimeType: String? = null,
        val durationMs: Long? = null,
        /** 试听片段等非致命提示，最终由 UI 决定是否展示。 */
        val noticeMessage: String? = null,
        /** 时效性直链的绝对过期时间（epoch ms）；无时效性的音源为 null。 */
        val expiresAtEpochMs: Long? = null,
    ) : PlatformPlaybackResult

    /** 没有登录态，引导用户"去登录"。 */
    data class LoginRequired(val reason: String) : PlatformPlaybackResult

    /** 有登录态但拿不到资源（版权 / 权益 / 已下架 / 该音源没有音频流）。 */
    data class Unavailable(val reason: String) : PlatformPlaybackResult

    /** 此刻连不上（DNS / 超时 / 连接被重置）；应交给网络恢复机制而不是弹版权错误。 */
    data class Transport(val reason: String) : PlatformPlaybackResult
}
