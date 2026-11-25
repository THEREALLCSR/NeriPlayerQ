package moe.ouom.neriplayer.data.model.qqmusic.search

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * QQ 音乐搜索所需的认证快照，由登录态持有方（qqmusic.auth）计算后传给纯协议层。
 *
 * 这样 `platform.search.api` 只依赖 model/common/network，不必引用 `qqmusic.auth` 包：
 * 会话读取与 g_tk 计算都留在 auth 侧，搜索层拿到的是可以直接放进请求的三份纯数据。
 *
 * @param uin    请求 comm.uin，未登录或无法解析时为 0。
 * @param gTk    请求 comm.g_tk / g_tk_new_20200303，由 musicKey 经 hash33 计算。
 * @param cookie 请求 Cookie 头，空白表示匿名。
 */
data class QQMusicSearchAuth(
    val uin: Long,
    val gTk: Long,
    val cookie: String,
)
