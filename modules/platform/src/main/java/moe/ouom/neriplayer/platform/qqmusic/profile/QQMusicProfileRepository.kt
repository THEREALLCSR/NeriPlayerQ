package moe.ouom.neriplayer.platform.qqmusic.profile

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
 * File: moe.ouom.neriplayer.platform.qqmusic.profile/QQMusicProfileRepository
 */

import moe.ouom.neriplayer.data.model.qqmusic.QQMusicAccountProfile
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.auth.qqMusicHash33
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * QQ 音乐账号资料（头像 / 昵称）仓库。
 *
 * 端点与字段候选移植自 QM-Download v0.6 的已验证实现：
 * `c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg`。
 * 失败（含 HTTP 非 2xx、响应畸形、空资料）一律降级为 null，与网易云 loadProfile 一致。
 */
class QQMusicProfileRepository(private val client: OkHttpClient) {

    suspend fun loadProfile(uin: String): QQMusicAccountProfile? {
        if (uin.isBlank()) return null
        return runCatching {
            val url = PROFILE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("uin", uin)
                .addQueryParameter("format", "json")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).awaitResponse { response ->
                if (!response.isSuccessful) return@awaitResponse null
                val root = JSONObject(response.body.string())
                val data = root.optJSONObject("data") ?: JSONObject()
                val creator = data.optJSONObject("creator")
                val nickname = sequenceOf(
                    creator?.optString("nickname"),
                    data.optString("nickname"),
                ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
                val avatarUrl = sequenceOf(
                    creator?.optString("headpic"),
                    data.optString("headpic"),
                ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
                if (nickname.isBlank() && avatarUrl.isBlank()) {
                    null
                } else {
                    QQMusicAccountProfile(uin = uin, nickname = nickname, avatarUrl = avatarUrl)
                }
            }
        }.getOrNull()
    }

    /**
     * 登录态资料读取（QM-Download v0.6 已验证实现的等价补强版）。
     *
     * 与 [loadProfile] 的弱化版不同，本重载带齐全套网页身份参数（g_tk / Cookie /
     * loginUin / userid），供 app 层登录后读取昵称与头像；未登录直接返回 null 且不发请求。
     */
    suspend fun load(session: QQMusicSession): QQMusicAccountProfile? {
        if (!session.isLoggedIn) return null
        return runCatching {
            val url = PROFILE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("g_tk", qqMusicHash33(session.musicKey).toString())
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("cid", "205360838")
                .addQueryParameter("needNewCode", "0")
                .addQueryParameter("loginUin", session.uin)
                .addQueryParameter("hostUin", "0")
                .addQueryParameter("userid", session.uin)
                .addQueryParameter("reqfrom", "1")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cookie", session.cookie)
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).awaitResponse { response ->
                if (!response.isSuccessful) return@awaitResponse null
                val root = JSONObject(response.body.string())
                if (root.optInt("code", -1) != 0) return@awaitResponse null
                val data = root.optJSONObject("data") ?: root
                val creator = data.optJSONObject("creator")
                    ?: data.optJSONObject("user")
                    ?: data.optJSONObject("profile")
                val nickname = sequenceOf(
                    creator?.optString("nick"),
                    creator?.optString("nickname"),
                    data.optString("nick"),
                ).firstOrNull { !it.isNullOrBlank() }?.trim()
                    ?: "QQ ${session.uin}"
                val avatarUrl = sequenceOf(
                    creator?.optString("headpic"),
                    creator?.optString("avatarUrl"),
                    data.optString("headpic"),
                ).firstOrNull { !it.isNullOrBlank() }?.trim()?.let(::normalize)
                QQMusicAccountProfile(
                    uin = session.uin,
                    nickname = nickname,
                    avatarUrl = avatarUrl.orEmpty(),
                )
            }
        }.getOrNull()
    }

    /** 头像归一化：http → https、// → https:（QM-Download normalize 行为）。 */
    private fun normalize(raw: String): String = when {
        raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
        raw.startsWith("//") -> "https:$raw"
        else -> raw
    }

    private companion object {
        const val PROFILE_URL = "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
