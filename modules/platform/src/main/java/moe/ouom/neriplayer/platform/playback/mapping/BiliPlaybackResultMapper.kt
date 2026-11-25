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
 * File: moe.ouom.neriplayer.platform.playback.mapping/BiliPlaybackResultMapper
 */

import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo

/**
 * B站取流的类型化输入。
 *
 * B站原先没有独立的结果类型：`BiliAudioStreamInfo?` 为 null 时，
 * "没有音频流"、"要大会员"、"网络失败"全部塌成同一个 null。
 * 这里用一个最小的 sealed 类型把它们拆开，mapper 才能保真映射。
 */
sealed interface BiliPlaybackResult {
    /** 拿到了音频流（url / candidateUrls / mimeType 都在这里）。 */
    data class Stream(val stream: BiliAudioStreamInfo) : BiliPlaybackResult

    /** 需要登录或大会员才能取流。 */
    data object LoginRequired : BiliPlaybackResult

    /** 视频确实没有可分离的音频流（无资源），不是网络问题。 */
    data object NoStream : BiliPlaybackResult

    /** 网络层失败。 */
    data class Transport(val reason: String) : BiliPlaybackResult
}

/** B站 → [PlatformPlaybackResult] 的纯映射，无 IO、可单测。 */
object BiliPlaybackResultMapper {
    @JvmStatic
    fun map(result: BiliPlaybackResult): PlatformPlaybackResult = when (result) {
        is BiliPlaybackResult.Stream -> PlatformPlaybackResult.Success(
            url = result.stream.url,
            candidateUrls = result.stream.candidateUrls,
            mimeType = result.stream.mimeType,
        )

        BiliPlaybackResult.LoginRequired ->
            PlatformPlaybackResult.LoginRequired("B站需要登录或大会员才能播放")

        BiliPlaybackResult.NoStream ->
            PlatformPlaybackResult.Unavailable("B站没有返回可用音频流")

        is BiliPlaybackResult.Transport -> PlatformPlaybackResult.Transport(result.reason)
    }
}
