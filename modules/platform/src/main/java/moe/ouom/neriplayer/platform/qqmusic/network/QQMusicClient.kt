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
 * File: moe.ouom.neriplayer.platform.qqmusic.network/QQMusicClient
 */

import java.io.IOException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * `musicu.fcg` 的唯一传输入口。
 *
 * 改造前三处各写一遍：vkey（app 身份 ct=11）、歌单仓（web 身份 ct=24）、
 * `fetchMediaMid`（又一份手写）。三处对 HTTP 状态、空响应、`req_0.code`
 * 的处理还不一致——同一类故障在不同功能上表现成不同错误。
 *
 * 这里只负责"传输 + 拆信封 + 分类错误"，**不组装 comm**（信封身份仍由调用方决定，
 * 因为读接口与取流接口的 ct/cv 不同，混用会改变服务端风控判定）。
 */
class QQMusicClient(
    private val httpClient: OkHttpClient,
    /** 允许测试注入 MockWebServer 地址；生产默认 QQ 的 musicu 网关。 */
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    /**
     * POST 一个 musicu 请求体，返回 `req_0.data`。
     *
     * @param acceptCodes 除 0 以外仍视为成功的业务码（歌单写入的 80081/80083）。
     * @throws QQMusicApiException 业务码非 0、HTTP 失败、响应不可解析时抛出。
     */
    fun post(
        payload: JSONObject,
        session: QQMusicSession,
        acceptCodes: Set<Int> = emptySet(),
    ): JSONObject {
        val request = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", userAgent)
            .header("Referer", "https://y.qq.com/")
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (session.cookie.isNotBlank()) header("Cookie", session.cookie) }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return post(request, acceptCodes)
    }

    fun post(request: Request, acceptCodes: Set<Int> = emptySet()): JSONObject {
        val body = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 401/403 在 QQ 网关上等价于"登录态不被接受"，比笼统的 HTTP 错误更有用
                    if (response.code == 401 || response.code == 403) {
                        throw QQMusicApiException.fromCode(1000, "HTTP ${response.code}")
                    }
                    throw QQMusicApiException(
                        kind = QQMusicFailureKind.Transport,
                        code = response.code,
                        serverMessage = "HTTP ${response.code}",
                    )
                }
                response.body.string()
            }
        } catch (error: QQMusicApiException) {
            throw error
        } catch (error: IOException) {
            throw QQMusicApiException.transport(error)
        }

        if (body.isBlank()) {
            throw QQMusicApiException(
                kind = QQMusicFailureKind.Transport,
                code = -1,
                serverMessage = "空响应",
            )
        }
        val root = try {
            JSONObject(body)
        } catch (error: org.json.JSONException) {
            throw QQMusicApiException.transport(error)
        }
        val req = root.optJSONObject("req_0")
            ?: throw QQMusicApiException(
                kind = QQMusicFailureKind.Rejected,
                code = -1,
                serverMessage = "响应缺少 req_0",
            )
        val code = req.optInt("code", 0)
        if (code != 0 && code !in acceptCodes) {
            val message = req.optString("msg")
            val failure = if (QQMusicApiException.isInvalidGuid(message)) {
                QQMusicApiException(QQMusicFailureKind.RiskControlled, code, message)
            } else {
                QQMusicApiException.fromCode(code, message)
            }
            NPLogger.w(
                TAG,
                "QQ musicu rejected: kind=${failure.kind} code=$code msg=${message.take(160)}"
            )
            throw failure
        }
        return req.optJSONObject("data") ?: JSONObject()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val DEFAULT_USER_AGENT = "QQMusic 14090008(android 15)"
        private const val TAG = "QQMusicClient"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
