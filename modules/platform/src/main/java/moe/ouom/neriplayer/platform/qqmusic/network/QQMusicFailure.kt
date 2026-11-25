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
 * File: moe.ouom.neriplayer.platform.qqmusic.network/QQMusicFailure
 */

import java.io.IOException

/**
 * QQ 音乐失败的**语义**分类。
 *
 * 为什么必须分：改造前所有非 0 返回码都被包成 `IOException(msg)`，
 * 再被上层折叠成一句"可能需要该音质权益"。于是"登录过期""会员不足""风控"
 * "网络失败"在 UI 上完全同形——用户不知道该重新扫码，开发者也拿不到可区分日志。
 *
 * 下列码值不是猜的，来自 `README.qqmusic.md` 与代码注释里的真机复核记录：
 *  - `1000`    写接口遇到旧 Cookie（歌单写入失效）
 *  - `2001`    网关把请求判定为匿名
 *  - `10000`   评论写操作缺少 App 凭据
 *  - `500026`  评论发表被风控
 *  - `104009`  vkey 的 guid 不是已注册设备号（`invalidq`）
 */
enum class QQMusicFailureKind {
    /** 登录态失效，必须重新扫码；调用方应中止并提示用户。 */
    SessionExpired,

    /** 账号没有该能力（会员/App 登录态），换一首或换操作也没用。 */
    PermissionDenied,

    /** 风控拦截，通常是 guid/频率问题，重试可能恢复。 */
    RiskControlled,

    /** 服务端明确拒绝，但没有已知语义（含"该档没有资源"）。 */
    Rejected,

    /** 网络层失败：超时、DNS、连接中断。 */
    Transport,
}

/**
 * QQ 音乐接口异常。
 *
 * [message] 直接是**给用户看的一句话**：它会被塞进 `Unavailable(reason)` 并显示在播放页，
 * 所以不要把 `code=...` 这种内部细节当主文案（原始码放在 [code] 与 [serverMessage]）。
 */
class QQMusicApiException(
    val kind: QQMusicFailureKind,
    val code: Int,
    val serverMessage: String = "",
    cause: Throwable? = null,
) : IOException(
    when (kind) {
        QQMusicFailureKind.SessionExpired -> "QQ 音乐登录已过期，请重新扫码登录"
        QQMusicFailureKind.PermissionDenied ->
            "QQ 音乐账号权限不足（该操作需要会员或 App 登录态）"
        QQMusicFailureKind.RiskControlled -> "QQ 音乐风控拦截，请稍后重试"
        QQMusicFailureKind.Rejected ->
            serverMessage.ifBlank { "QQ 音乐接口返回错误（code=$code）" }
        QQMusicFailureKind.Transport ->
            "QQ 音乐网络请求失败：${serverMessage.ifBlank { cause?.message.orEmpty() }}"
    },
    cause,
) {
    /** 是否需要引导用户重新登录。 */
    val requiresRelogin: Boolean
        get() = kind == QQMusicFailureKind.SessionExpired

    companion object {
        /** 已知语义的返回码；其余一律 `Rejected`（消息用服务端原文）。 */
        fun fromCode(code: Int, serverMessage: String): QQMusicApiException {
            val kind = when (code) {
                // 旧 Cookie / 匿名判定：必须重新登录
                1000, 2001 -> QQMusicFailureKind.SessionExpired
                // 评论写缺少 App 登录态：登录了也没用，属于能力不足
                10000 -> QQMusicFailureKind.PermissionDenied
                // 发表风控 / guid 非法
                500026, 104009 -> QQMusicFailureKind.RiskControlled
                else -> QQMusicFailureKind.Rejected
            }
            return QQMusicApiException(kind, code, serverMessage)
        }

        fun transport(cause: Throwable): QQMusicApiException =
            QQMusicApiException(
                kind = QQMusicFailureKind.Transport,
                code = -1,
                serverMessage = cause.message.orEmpty(),
                cause = cause,
            )

        /**
         * 判断一段错误文本是否表示 guid 非法。
         *
         * 服务端有时把 104009 放进 `msg` 而不是 `code`，只匹配 `invalidq` 会更稳。
         */
        fun isInvalidGuid(message: String): Boolean =
            message.contains("invalidq", ignoreCase = true)
    }
}
