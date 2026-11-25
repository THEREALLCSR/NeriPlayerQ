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
 * File: moe.ouom.neriplayer.platform.qqmusic.auth/QQMusicSessionStore
 */

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicSearchAuth

/**
 * QQ 音乐登录态。
 *
 * 为什么只需要两样东西：
 *  - [cookie]   完整的 Cookie 串：QQ 音乐的网页接口（歌单、评论等）直接认它；
 *  - [uin]      账号数字 ID：业务请求的 `comm.uin` 参数要用；
 *  - [musicKey] 播放密钥（Cookie 里的 `qm_keyst`）：换播放地址（vkey）时要用，
 *               同时 `g_tk` 也是用它算出来的。
 *
 * 也就是说：**cookie 用来证明“我是谁”，musicKey 用来换“播放权”**。
 */
data class QQMusicSession(
    val cookie: String,
    val uin: String,
    val musicKey: String,
    /** App 登录凭据：写接口（评论/歌单）需要它，网页 Cookie 只够读。 */
    val authst: String = "",
    /** App 侧音乐账号 id（通常等于 uin/QQ 号，但以接口返回为准）。 */
    val musicId: String = "",
    val sid: String = "",
    val wid: String = "",
) {
    val isLoggedIn: Boolean
        get() = uin.isNotBlank() && musicKey.isNotBlank()

    /** 是否具备写权限（App 登录态）。 */
    val canWrite: Boolean
        get() = authst.isNotBlank() && (musicId.ifBlank { uin }).isNotBlank()
}

/**
 * QQ 音乐登录态的本地存储。
 *
 * 设计说明（与项目里其它账号一致的最小实现）：
 *  - 用独立的 SharedPreferences 文件，退出登录 = 清空该文件；
 *  - 不做加密（后续可迁移到 AccountPreferencesOpener，和网易云/B站一致）；
 *  - 只存一行 Cookie 字符串，解析逻辑集中在本文件，方便写单元测试。
 */
class QQMusicSessionStore(context: Context) {

    private val appContext = context.applicationContext

    private val preferences: SharedPreferences
        get() = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 与 NeriPlayer 其它账号仓库的接口保持一致，供设置页的存储提示使用。 */
    val requiresCompatibilityStorageWarning: Boolean = false
    val isAuthStoragePersistent: Boolean = true

    fun read(): QQMusicSession = parse(preferences.getString(KEY_COOKIE, "").orEmpty())

    /**
     * 保存登录态。
     *
     * @throws IllegalArgumentException Cookie 里缺少 uin 或 qm_keyst 时抛出，
     *         避免把半截登录态写进本地（用户会以为登录成功了）。
     */
    fun save(cookie: String): QQMusicSession {
        val session = parse(cookie)
        require(session.isLoggedIn) { "QQ音乐登录态不完整（缺少 uin 或 qm_keyst）" }
        // 重新扫码等价于修复登录态，清掉过期标记，避免 UI 一直提示"已过期"
        preferences.edit()
            .putString(KEY_COOKIE, cookie.trim())
            .remove(KEY_EXPIRED_AT)
            .apply()
        return session
    }

    /**
     * 标记"服务端已判定登录失效"。
     *
     * 为什么不在标记后直接清 Cookie：清掉会让用户失去"谁登录过"的上下文，
     * 也无法区分"从没登录"和"登录过期"。保留 Cookie + 一个时间戳，
     * 让设置页能显示"登录已过期，请重新扫码"，而不是"未登录"。
     */
    fun markExpired() {
        preferences.edit().putLong(KEY_EXPIRED_AT, System.currentTimeMillis()).apply()
    }

    /** 上次被服务端判定失效的时间；null 表示当前会话未被判定过期。 */
    fun expiredAtOrNull(): Long? =
        preferences.getLong(KEY_EXPIRED_AT, 0L).takeIf { it > 0L }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "qqmusic_auth"
        private const val KEY_COOKIE = "qqmusic_cookie"
        private const val KEY_EXPIRED_AT = "qqmusic_expired_at"

        /**
         * 把一条 Cookie 串解析成 [QQMusicSession]。
         *
         * QQ 音乐登录后可能出现多个别名的键，按优先级兼容：
         *  - uin：`qqmusic_uin` → `uin` → `p_uin`
         *  - key：`qm_keyst` → `qqmusic_key` → `music_key`
         *
         * 这个方法不依赖 Android，纯字符串处理，便于单元测试。
         */
        fun parse(cookie: String): QQMusicSession {
            val values = cookieValues(cookie)
            val rawUin = sequenceOf("qqmusic_uin", "uin", "p_uin")
                .map { values[it].orEmpty() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            // 有些账号的 uin 会被写成 "o1234567890" 或带前导零，统一保留纯数字。
            val uin = rawUin.filter(Char::isDigit).trimStart('0')
            val musicKey = sequenceOf("qm_keyst", "qqmusic_key", "music_key")
                .map { values[it].orEmpty() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            return QQMusicSession(
                cookie = cookie.trim(),
                uin = uin,
                musicKey = musicKey,
                authst = values["authst"].orEmpty(),
                musicId = values["musicid"].orEmpty().ifBlank { uin },
                sid = values["sid"].orEmpty(),
                wid = values["wid"].orEmpty(),
            )
        }

        /** 解析 `a=1; b=2` 形式的 Cookie；空值/非法项直接忽略。 */
        fun cookieValues(cookie: String): Map<String, String> = cookie
            .split(';')
            .map(String::trim)
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null
                else entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
            }
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .toMap()
    }
}

/**
 * QQ 音乐接口里到处用到的 `g_tk` 算法。
 *
 * 算法：从 seed 开始，每个字符执行 `h = h * 33 + char`，最后取低 31 位。
 * 两个常用 seed：
 *  - `0`    ：二维码轮询的 `ptqrtoken`；
 *  - `5381` ：登录后业务请求的 `g_tk`（用 musicKey 计算）。
 *
 * 参考实现已在 MeloX 项目中用真实账号验证过，这里保持一致。
 */
fun qqMusicHash33(value: String, seed: Int = 5381): Long {
    var hash = seed.toLong()
    value.forEach { char ->
        hash += (hash shl 5) + char.code
        hash = hash and 0xFFFF_FFFFL
    }
    return hash and 0x7FFF_FFFFL
}

/**
 * 把登录态折算成搜索接口可直接使用的认证快照。
 *
 * 会话读取与 g_tk 计算都留在 auth 包内，`platform.search.api` 只需依赖 model 层的
 * [QQMusicSearchAuth]，不再反向引用本包——门禁规则因此得以满足。
 * 计算方式与改造前 QQMusicSearchApi 内联的写法完全一致（行为等价）：
 *  - uin：解析失败记 0；
 *  - g_tk：musicKey 非空白才做 hash33，否则 0；
 *  - cookie：原样透传，空白由调用方按匿名处理。
 */
fun QQMusicSession.toSearchAuth(): QQMusicSearchAuth = QQMusicSearchAuth(
    uin = uin.toLongOrNull() ?: 0L,
    gTk = musicKey.takeIf(String::isNotBlank)?.let(::qqMusicHash33) ?: 0L,
    cookie = cookie,
)
