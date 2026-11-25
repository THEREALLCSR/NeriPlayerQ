package moe.ouom.neriplayer.platform.qqmusic.ranking

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
 * File: moe.ouom.neriplayer.platform.qqmusic.ranking/QQMusicRankingRepository
 */

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.qqmusic.QQMusicRanking
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicApiException
import moe.ouom.neriplayer.platform.qqmusic.track.QQMusicTrack
import moe.ouom.neriplayer.platform.qqmusic.track.parseQqMusicTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐排行榜仓库（music.musicToplist.Toplist / GetAll + GetDetail）。
 *
 * 端点与信封来自公开只读接口的抓包记录（ylw1997/qqmusic-api、W_PlatformMusicApi wiki、
 * Go github.com/guohuiyuan/music-lib/qq 三源交叉），无需登录，因此不带 comm/cookie。
 * 发包与解析范式对齐 `QQMusicSearchApi`：musicu.fcg + POST JSON + `awaitResponse`。
 *
 * 失败语义：业务码非 0 抛 [QQMusicApiException] 交上层处理；HTTP 失败、JSON 畸形、
 * 数据缺失等一律降级空列表，不崩溃（与 `QQMusicProfileRepository` 的降级范式一致）。
 */
class QQMusicRankingRepository(private val client: OkHttpClient) {

    /** GetAll：把 group[].toplist[] 展平为榜单列表。 */
    suspend fun rankings(): List<QQMusicRanking> {
        val request = buildRequest(
            JSONObject()
                .put("module", TOPLIST_MODULE)
                .put("method", "GetAll")
                .put("param", JSONObject())
        )
        return load(request) { data ->
            val groups = data.optJSONArray("group") ?: JSONArray()
            buildList {
                for (index in 0 until groups.length()) {
                    val topLists = groups.optJSONObject(index)?.optJSONArray("toplist") ?: continue
                    for (itemIndex in 0 until topLists.length()) {
                        val item = topLists.optJSONObject(itemIndex) ?: continue
                        add(
                            QQMusicRanking(
                                id = item.optLong("topId", 0L),
                                title = item.optString("title"),
                                coverUrl = item.optString("headPicUrl"),
                            )
                        )
                    }
                }
            }
        }
    }

    /** GetDetail：按 topId/offset/num 拉某榜歌曲，复用 [parseQqMusicTrack] 解析。 */
    suspend fun rankingSongs(
        topId: Long,
        offset: Int = 0,
        num: Int = 30,
    ): List<QQMusicTrack> {
        val param = JSONObject()
            .put("topId", topId)
            .put("offset", offset)
            .put("num", num)
            .put("withTags", false)
        val request = buildRequest(
            JSONObject()
                .put("module", TOPLIST_MODULE)
                .put("method", "GetDetail")
                .put("param", param)
        )
        return load(request) { data ->
            val songs = data.optJSONArray("songInfoList") ?: JSONArray()
            buildList {
                for (index in 0 until songs.length()) {
                    songs.optJSONObject(index)?.let(::parseQqMusicTrack)?.let(::add)
                }
            }
        }
    }

    private fun buildRequest(req0: JSONObject): Request = Request.Builder()
        .url(ENDPOINT)
        .header("User-Agent", USER_AGENT)
        .header("Referer", "https://y.qq.com/")
        .header("Accept", "application/json, text/plain, */*")
        .post(
            JSONObject().put("req_0", req0)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
        )
        .build()

    /**
     * 统一信封处理：先取 HTTP 正文（网络/HTTP 失败降级空列表），
     * 再判业务码（非 0 抛类型化异常，不能被降级吞掉），最后解析 data（畸形降级空列表）。
     */
    private suspend fun <T> load(request: Request, parse: (JSONObject) -> List<T>): List<T> {
        val body = try {
            client.newCall(request).awaitResponse { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return emptyList()
        } ?: return emptyList()

        val req = runCatching { JSONObject(body).optJSONObject("req_0") }
            .getOrNull()
            ?: return emptyList()
        val code = req.optInt("code", 0)
        if (code != 0) throw QQMusicApiException.fromCode(code, req.optString("msg"))

        val data = req.optJSONObject("data") ?: return emptyList()
        return runCatching { parse(data) }.getOrDefault(emptyList())
    }

    private companion object {
        const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val TOPLIST_MODULE = "music.musicToplist.Toplist"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
