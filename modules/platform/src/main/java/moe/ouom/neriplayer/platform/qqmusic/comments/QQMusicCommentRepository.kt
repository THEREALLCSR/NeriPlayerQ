package moe.ouom.neriplayer.platform.qqmusic.comments

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
 * File: moe.ouom.neriplayer.platform.qqmusic.comments/QQMusicCommentRepository
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.comments.CommentEmote
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.platform.comments.CommentApiException
import moe.ouom.neriplayer.platform.comments.repository.CommentRepository
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.auth.qqMusicHash33
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐评论区实现（接入项目统一的 [CommentRepository]）。
 *
 * 入口与网易云一致：播放页右上角的评论按钮 → [CommentSheet]。
 *
 * 平台差异：
 *  - 评论接口的 `topid` 需要**数字歌曲 id**，而 SongItem 只有 songmid，
 *    因此通过 [numericIdProvider] 现查（内部走 `get_song_detail_yqq`）；
 *  - 表情是 `[em]e401203[/em]` 标记，对应 Qzone 表情 CDN
 *    `https://qzonestyle.gtimg.cn/qzone/em/e401203.gif`；
 *  - 点赞 / 发评论 / 楼中楼暂未接入，调用时抛出可读的 API 错误，由 UI 提示。
 */
class QQMusicCommentRepository(
    private val httpClient: OkHttpClient,
    private val sessionProvider: () -> QQMusicSession,
    private val numericIdProvider: suspend (songMid: String) -> Long?,
) : CommentRepository {

    override val platform: CommentPlatform = CommentPlatform.QQ_MUSIC

    override suspend fun loadComments(
        source: CommentSource,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean,
        sort: CommentSort,
        cursor: String?,
    ): CommentPage {
        require(source.platform == platform)
        val songMid = source.secondaryId?.takeIf { it.isNotBlank() }
            ?: throw CommentApiException(0, CommentError.NOT_FOUND, "QQ 音乐评论缺少歌曲 mid")
        val numericId = numericIdProvider(songMid)
            ?: throw CommentApiException(0, CommentError.NOT_FOUND, "无法解析 QQ 音乐歌曲 id")

        return withContext(Dispatchers.IO) {
            val session = sessionProvider()
            val pageIndex = (page - 1).coerceAtLeast(0)
            val gtk = qqMusicHash33(session.musicKey)
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("g_tk", gtk.toString())
                .addQueryParameter("uin", session.uin.ifBlank { "0" })
                .addQueryParameter("format", "json")
                .addQueryParameter("inCharset", "utf-8")
                .addQueryParameter("outCharset", "utf-8")
                .addQueryParameter("notice", "0")
                .addQueryParameter("platform", "yqq.json")
                .addQueryParameter("needNewCode", "0")
                .addQueryParameter("cid", "205360772")
                .addQueryParameter("reqtype", "2")
                .addQueryParameter("biztype", "1")
                .addQueryParameter("topid", numericId.toString())
                .addQueryParameter("cmd", if (pageIndex == 0) "8" else "6")
                .addQueryParameter("needmusiccrit", "0")
                .addQueryParameter("pagenum", pageIndex.toString())
                .addQueryParameter("pagesize", pageSize.coerceIn(1, 50).toString())
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .header("Accept", "application/json, text/plain, */*")
                .apply { if (session.cookie.isNotBlank()) header("Cookie", session.cookie) }
                .build()

            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw CommentApiException(
                        response.code,
                        CommentError.NETWORK,
                        "QQ 音乐评论请求失败：HTTP ${response.code}"
                    )
                }
                response.body.string()
            }
            if (body.isBlank()) {
                throw CommentApiException(0, CommentError.API, "QQ 音乐评论返回空响应")
            }
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw CommentApiException(code, CommentError.API, "QQ 音乐评论失败：code=$code")
            }

            val normal = root.optJSONObject("comment")
            val hot = root.optJSONObject("hot_comment")
            val normalList = normal?.optJSONArray("commentlist") ?: JSONArray()
            val hotList = hot?.optJSONArray("commentlist") ?: JSONArray()
            val total = sequenceOf(
                normal?.optLong("commenttotal", 0L) ?: 0L,
                root.optLong("commenttotal", 0L),
                root.optLong("total", 0L)
            ).firstOrNull { it > 0L }

            val seen = mutableSetOf<String>()
            val comments = buildList {
                fun append(array: JSONArray) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val comment = item.toSongComment() ?: continue
                        if (!seen.add(comment.id)) continue
                        add(comment)
                    }
                }
                // 第一页把"精彩评论"排在前面
                if (pageIndex == 0) append(hotList)
                append(normalList)
            }

            CommentPage(
                comments = comments,
                page = page,
                pageSize = pageSize,
                total = total,
                hasMore = normalList.length() >= pageSize,
            )
        }
    }

    override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
        throw CommentApiException(0, CommentError.API, "QQ 音乐评论暂不支持点赞")
    }

    override suspend fun loadReplies(
        source: CommentSource,
        rootId: String,
        page: Int,
        pageSize: Int,
        cursor: String?,
    ): CommentPage = CommentPage(
        comments = emptyList(),
        page = page,
        pageSize = pageSize,
        total = 0,
        hasMore = false,
    )

    override suspend fun sendComment(
        source: CommentSource,
        content: String,
        target: CommentReplyTarget?,
    ) {
        throw CommentApiException(0, CommentError.API, "QQ 音乐暂不支持发送评论")
    }

    private fun JSONObject.toSongComment(): SongComment? {
        val id = optString("commentid").takeIf { it.isNotBlank() } ?: return null
        val content = optString("rootcommentcontent")
            .ifBlank { optString("middlecommentcontent") }
            .takeIf { it.isNotBlank() } ?: return null
        val timeSeconds = optLong("time", 0L)
        return SongComment(
            id = id,
            userId = optString("encrypt_uin").takeIf { it.isNotBlank() },
            username = optString("nick").ifBlank { "QQ 音乐用户" },
            avatarUrl = optString("avatarurl").takeIf { it.isNotBlank() },
            content = content,
            likeCount = optLong("praisenum", 0L),
            replyCount = null,
            createTime = timeSeconds.takeIf { it > 0L }?.times(1_000L),
            platform = CommentPlatform.QQ_MUSIC,
            userLevel = null,
            isLiked = optInt("ispraise", 0) == 1,
            emotes = parseEmotes(content),
        )
    }

    /** 把 `[em]e401203[/em]` 映射成 Qzone 表情图片，交给统一的富文本渲染。 */
    private fun parseEmotes(content: String): List<CommentEmote> =
        EMOTE_REGEX.findAll(content)
            .map { match ->
                val emojiId = match.groupValues[1]
                CommentEmote(
                    placeholder = match.value,
                    url = "https://qzonestyle.gtimg.cn/qzone/em/$emojiId.gif",
                )
            }
            .distinctBy { it.placeholder }
            .toList()

    private companion object {
        const val BASE_URL = "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val EMOTE_REGEX = Regex("\\[em\\](e\\d+)\\[/em\\]")
    }
}
