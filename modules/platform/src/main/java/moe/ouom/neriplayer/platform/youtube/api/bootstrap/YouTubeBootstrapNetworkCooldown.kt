package moe.ouom.neriplayer.platform.youtube.api.bootstrap

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
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 */

import android.content.Context

/**
 * 网络不可达之后，隔多久才值得再试一次预热
 *
 * 被墙/断网的环境下，每次冷启动都会白付一次连接超时。失败一次就认定"这个网络现在到不了"，
 * 冷却期内直接跳过；用户换了网络（切 Wi-Fi/开代理）也不可能等太久，30 分钟是折中值。
 */
internal const val BOOTSTRAP_NETWORK_COOLDOWN_MS: Long = 30L * 60L * 1000L

/**
 * 冷却期内是否跳过预热
 *
 * @param lastFailureAtMs 上次"网络不可达"的落盘时间；null 表示从没失败过
 */
internal fun shouldSkipBootstrapWarm(
    lastFailureAtMs: Long?,
    nowMs: Long,
    cooldownMs: Long
): Boolean {
    if (lastFailureAtMs == null || cooldownMs <= 0L) return false
    val elapsed = nowMs - lastFailureAtMs
    // 时间戳比现在还晚（设备改过时间、落盘被写坏）时不能据此永久禁用预热
    if (elapsed < 0L) return false
    return elapsed < cooldownMs
}

/**
 * 本次失败之后该落盘哪个时间戳
 *
 * 只有网络级失败才启动冷却；HTTP 429 之类说明网络是通的，下一次冷启动应当照常预热，
 * 所以遇到这类失败要把旧的冷却记录清掉（返回 null）。
 */
internal fun cooldownStartForFailure(
    networkUnreachable: Boolean,
    nowMs: Long,
    previous: Long?
): Long? = if (networkUnreachable) nowMs else null

/**
 * 网络不可达时间戳的持久化
 *
 * 进程内存不够用：这个值要跨冷启动才有意义（每次启动都重新付一次超时就是现在的病灶）。
 * context 为 null 的场景（单测里裸建仓库）退化为内存无关的空实现，不启动冷却。
 */
internal class YouTubeBootstrapNetworkCooldownStore(context: Context?) {

    private val prefs = context?.applicationContext
        ?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readLastFailureAtMs(): Long? {
        val stored = prefs?.getLong(KEY_LAST_NETWORK_FAILURE_AT_MS, 0L) ?: 0L
        return stored.takeIf { it > 0L }
    }

    fun shouldSkipWarm(nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldSkipBootstrapWarm(
            lastFailureAtMs = readLastFailureAtMs(),
            nowMs = nowMs,
            cooldownMs = BOOTSTRAP_NETWORK_COOLDOWN_MS
        )

    fun recordOutcome(networkUnreachable: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val target = prefs ?: return
        val next = cooldownStartForFailure(
            networkUnreachable = networkUnreachable,
            nowMs = nowMs,
            previous = readLastFailureAtMs()
        )
        target.edit().apply {
            if (next == null) {
                remove(KEY_LAST_NETWORK_FAILURE_AT_MS)
            } else {
                putLong(KEY_LAST_NETWORK_FAILURE_AT_MS, next)
            }
        }.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "youtube_bootstrap_network"
        const val KEY_LAST_NETWORK_FAILURE_AT_MS = "last_network_failure_at_ms"
    }
}
