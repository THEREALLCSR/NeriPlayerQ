package moe.ouom.neriplayer.platform.qqmusic.network

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
 * File: moe.ouom.neriplayer.platform.qqmusic.network/QQMusicRequestHeaders
 */

/**
 * QQ 音乐**拉流**（CDN 直链）所需的请求头，唯一一份定义。
 *
 * 改造前的状态是两条链路各说各话：
 *  - 下载侧（`AudioDownloadManagerAttempt`）单独加了 `Referer`，注释写"QQ CDN 只校验 Referer"；
 *  - 播放侧（`ConditionalHttpDataSourceFactory`）只有 B 站与 YouTube 的注入分支，QQ 缺席。
 *
 * 两边的差异不会立刻出问题（CDN 目前对播放宽松），但一旦收紧，
 * 播放会整片 403，而且没有集中修改点。这里把取值收口，两侧共用。
 */
object QQMusicRequestHeaders {
    const val REFERER: String = "https://y.qq.com/"
    const val USER_AGENT: String = "QQMusic 14090008(android 15)"

    /**
     * 在已有请求头上补齐 QQ CDN 需要的两项。
     *
     * `Referer` 强制覆盖（CDN 只认 `y.qq.com`）；`User-Agent` 仅在缺省时补，
     * 避免覆盖调用方（例如下载器）已经选好的 UA。
     */
    fun applyTo(headers: MutableMap<String, String>) {
        headers["Referer"] = REFERER
        headers.putIfAbsent("User-Agent", USER_AGENT)
    }
}

/**
 * 是否为 QQ 音乐直链的 CDN 域名。
 *
 * 只认 `qqmusic.qq.com` 这一棵子树（`isure/dl/ws*.stream.qqmusic.qq.com`），
 * 不做"包含 qqmusic 就算"的宽松匹配——那会把第三方域名也带上 Referer。
 */
fun isQqMusicCdnHost(host: String?): Boolean {
    val normalized = host?.trim()?.lowercase() ?: return false
    if (normalized.isEmpty()) return false
    return normalized == "qqmusic.qq.com" || normalized.endsWith(".qqmusic.qq.com")
}
