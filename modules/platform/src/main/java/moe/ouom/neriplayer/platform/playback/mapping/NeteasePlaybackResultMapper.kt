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
 * File: moe.ouom.neriplayer.platform.playback.mapping/NeteasePlaybackResultMapper
 */

import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.Notice
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser.PlaybackResult

/**
 * 网易云 → [PlatformPlaybackResult] 的纯映射，无 IO、可单测。
 *
 * 输入直接复用解析器已有的类型化结果 [PlaybackResult]（Success / RequiresLogin / Failure），
 * 不必为这一家再造一套输入类型。语义对应关系：
 *  - RequiresLogin → LoginRequired（引导登录）；
 *  - Failure（NO_PERMISSION / NO_PLAY_URL / UNKNOWN）→ Unavailable（没资源/没权益）；
 *    解析器拿不到的是"资源"语义，不是"连不上"，所以不映射成 Transport；
 *  - Success → Success，并把试听片段提示保真到 noticeMessage。
 */
object NeteasePlaybackResultMapper {
    @JvmStatic
    fun map(result: PlaybackResult): PlatformPlaybackResult = when (result) {
        is PlaybackResult.Success -> PlatformPlaybackResult.Success(
            url = result.url,
            mimeType = result.type,
            durationMs = result.durationMs,
            noticeMessage = if (result.notice == Notice.PREVIEW_CLIP) "试听片段" else null,
        )

        PlaybackResult.RequiresLogin ->
            PlatformPlaybackResult.LoginRequired("网易云音乐需要登录")

        is PlaybackResult.Failure ->
            PlatformPlaybackResult.Unavailable("网易云音乐没有返回可播放地址（${result.reason}）")
    }
}
