package moe.ouom.neriplayer.platform.qqmusic.auth

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
 * File: moe.ouom.neriplayer.platform.qqmusic.auth/QQMusicSessionAutoRefresh
 */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * QQ 音乐登录态自动续签编排器（第 10 次迭代红灯覆盖）。
 *
 * 把"读 store → isNearExpiry 判断 → refresh → 校验 → 回写 store"的编排封装成可注入的纯 Kotlin 类，
 * 让启动自检能离线单测。契约对齐 QM-Download v0.6 的 QmApplication.ensureSessionFresh：
 * - 未登录/未临近过期：不发请求、不回写，返回 false；
 * - 临近过期（或 force）：续签成功后回写新 cookie，返回 true；
 * - 失败降级：refresh 抛错（含业务码拒绝）/ 返回未登录 cookie 时，保留原登录态、不回写、不抛异常，返回 false；
 * - 并发串行：Mutex 保证同一时刻只有一个续签；进程内"已续签过"标记避免同一 refresh_token 被并发重复消费。
 *
 * 注意：saveCookie 由调用方提供（如 store.save 自己会 parse/校验），本类只负责编排，不直接依赖 Android。
 */
class QQMusicSessionAutoRefresh(
    private val readCookie: () -> String,        // 从 store 读完整 cookie 串
    private val saveCookie: (String) -> Unit,    // 回写新 cookie 串（store.save 自己会 parse/校验）
    private val httpClient: OkHttpClient,
    private val refreshUrl: String = QQMusicSessionRefresher.REFRESH_URL,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    /**
     * 本次进程内是否已成功续签过。
     *
     * 续签响应可能不含 musickeyCreateTime，此时 [QQMusicSessionRefresher.mergeRefreshedCookie]
     * 保留旧 createtime，续签后的 cookie 仍会被 [QQMusicSessionRefresher.isNearExpiry] 判为"临近过期"。
     * 仅靠 Mutex 串行 + createtime 变化会让并发进入者在第一个成功后继续续签、重复消费 refresh_token。
     * 用进程内标记在"成功后"短路后续非 force 调用，保证并发只回写一次。
     * 每个实例独立持有该标记（测试间为新实例，互不影响）。
     */
    @Volatile
    private var refreshedThisProcess: Boolean = false

    suspend fun ensureFresh(force: Boolean = false): Boolean = mutex.withLock {
        // 1. 空 / 未登录（用 store 的 parse 判定）→ 不续签、不回写。
        val cookie = readCookie()
        if (cookie.isBlank()) return@withLock false
        if (!QQMusicSessionStore.parse(cookie).isLoggedIn) return@withLock false

        // 2. 非 force 时：进程内已续签过，或未临近过期 → 跳过。
        if (!force) {
            if (refreshedThisProcess) return@withLock false
            if (!QQMusicSessionRefresher.isNearExpiry(cookie, nowMillis = clock())) return@withLock false
        }

        // 3. 续签；取消必须重抛给上层（协程协作取消），其它失败一律降级返回 false，绝不回写。
        val newCookie = try {
            QQMusicSessionRefresher.refresh(cookie, httpClient, url = refreshUrl)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return@withLock false
        }

        // 4. 新 cookie 必须仍是有效登录态，否则视为失败、不回写。
        if (!QQMusicSessionStore.parse(newCookie).isLoggedIn) return@withLock false

        // 5. 回写并标记本次进程已成功续签。
        saveCookie(newCookie)
        refreshedThisProcess = true
        true
    }
}
