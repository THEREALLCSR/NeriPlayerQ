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
 * File: moe.ouom.neriplayer.platform.playback.mapping/YouTubePlaybackResultMapper
 */

import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio

/**
 * YouTube 取流的类型化输入。
 *
 * YouTubePlayableAudio? 为 null 时原先无法区分"视频没有可用音轨"、
 * "需要登录（年龄/地区限制）"和"网络失败"，这里显式拆成三个失败分支。
 */
sealed interface YouTubePlaybackResult {
    /** 拿到了可播放音频流。 */
    data class Stream(val audio: YouTubePlayableAudio) : YouTubePlaybackResult

    /** 播放被登录态/地区/年龄门槛拦截。 */
    data object LoginRequired : YouTubePlaybackResult

    /** 视频确实没有可用音轨（无资源）。 */
    data object NoStream : YouTubePlaybackResult

    /** 网络层失败。 */
    data class Transport(val reason: String) : YouTubePlaybackResult
}

/** YouTube → [PlatformPlaybackResult] 的纯映射，无 IO、可单测。 */
object YouTubePlaybackResultMapper {
    @JvmStatic
    fun map(result: YouTubePlaybackResult): PlatformPlaybackResult = when (result) {
        is YouTubePlaybackResult.Stream -> PlatformPlaybackResult.Success(
            url = result.audio.url,
            mimeType = result.audio.mimeType,
            durationMs = result.audio.durationMs.takeIf { it > 0L },
        )

        YouTubePlaybackResult.LoginRequired ->
            PlatformPlaybackResult.LoginRequired("YouTube 需要登录才能播放")

        YouTubePlaybackResult.NoStream ->
            PlatformPlaybackResult.Unavailable("YouTube 没有返回可用音频流")

        is YouTubePlaybackResult.Transport -> PlatformPlaybackResult.Transport(result.reason)
    }
}
