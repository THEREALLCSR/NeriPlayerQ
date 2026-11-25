package moe.ouom.neriplayer.ui.viewmodel.debug

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
 * File: moe.ouom.neriplayer.ui.viewmodel.debug/QqMusicApiProbeViewModel
 */

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.measureTimeMillis

private const val TAG_PROBE = "NERI-QqApiProbeVM"

data class QqProbeUiState(
    val running: Boolean = false,
    val lastMessage: String = "",
    val lastJsonPreview: String = "",
    val authSummary: String = "",
    val keyword: String = "少女与战车",
    val songMid: String = "0039MnYb0qxYhV",
    val dissid: String = "7011264340",
    val albumMid: String = "000fmep84NuOdH",
    val commentTopId: String = "97773",
    val resultSummary: String = ""
)

/**
 * QQ 音乐接口调试：把目前接入 QQ 音源用到的接口集中到开发者模式里，
 * 方便后续排查（与网易云 `NeteaseApiProbeViewModel` 同样的交互方式）。
 */
class QqMusicApiProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val sessionStore = AppContainer.qqMusicSessionStore
    private val libraryRepository = AppContainer.qqMusicLibraryRepository
    private val playbackRepository = AppContainer.qqMusicPlaybackRepository
    private val commentRepository = AppContainer.qqMusicCommentRepository
    private val searchApi = AppContainer.qqMusicSearchApi

    private val _ui = MutableStateFlow(
        QqProbeUiState(authSummary = buildAuthSummary())
    )
    val ui: StateFlow<QqProbeUiState> = _ui.asStateFlow()

    fun onKeywordChange(value: String) {
        _ui.value = _ui.value.copy(keyword = value)
    }

    fun onSongMidChange(value: String) {
        _ui.value = _ui.value.copy(songMid = value.trim())
    }

    fun onDissidChange(value: String) {
        _ui.value = _ui.value.copy(dissid = value.filter(Char::isDigit).take(20))
    }

    fun onAlbumMidChange(value: String) {
        _ui.value = _ui.value.copy(albumMid = value.trim())
    }

    fun onCommentTopIdChange(value: String) {
        _ui.value = _ui.value.copy(commentTopId = value.filter(Char::isDigit).take(20))
    }

    private fun buildAuthSummary(): String {
        val session = sessionStore.read()
        return if (session.isLoggedIn) {
            "已登录 · uin=${session.uin} · cookie=${session.cookie.length} 字节"
        } else {
            "未登录（搜索/歌单接口需要登录）"
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun launchAndCopy(label: String, block: suspend () -> String) {
        if (_ui.value.running) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(running = true, lastMessage = "请求中：$label")
            val result = runCatching { block() }
            result.onSuccess { json ->
                copyToClipboard("qqmusic_$label", json)
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "成功：$label（原始 JSON 已复制到剪贴板）",
                    lastJsonPreview = json.take(4000)
                )
            }.onFailure { failure ->
                NPLogger.e(TAG_PROBE, "probe $label failed", failure)
                _ui.value = _ui.value.copy(
                    running = false,
                    lastMessage = "失败：$label → ${failure.message}",
                    lastJsonPreview = ""
                )
            }
        }
    }

    private inline fun <T> JSONArray.putJson(item: T, build: JSONObject.(T) -> Unit): JSONArray {
        val obj = JSONObject()
        obj.build(item)
        put(obj)
        return this
    }

    fun callSessionAndCopy() = launchAndCopy("session") {
        val session = sessionStore.read()
        JSONObject()
            .put("loggedIn", session.isLoggedIn)
            .put("uin", session.uin)
            .put("musicKeyLength", session.musicKey.length)
            .put("cookieLength", session.cookie.length)
            .toString()
    }

    fun callUserPlaylistsAndCopy() = launchAndCopy("userPlaylists") {
        val playlists = withContext(Dispatchers.IO) { libraryRepository.userPlaylists() }
        JSONArray().apply {
            playlists.take(20).forEach { playlist ->
                putJson(playlist) {
                    put("id", it.id)
                    put("dirId", it.dirId)
                    put("title", it.title)
                    put("trackCount", it.trackCount)
                    put("playCount", it.playCount)
                }
            }
        }.toString()
    }

    /**
     * debug 诊断：复制歌单列表第一个对象的原始字段。
     * 与 [callUserPlaylistsAndCopy] 不同，这里走 platform 的 raw 探针，能看到未解析的字段名与值，
     * 用于真机确认播放量到底藏在哪个字段（含 "play"/"listen"/"num"/"cnt" 的键）。
     */
    fun callUserPlaylistsRawAndCopy() = launchAndCopy("playlistsRaw") {
        val data = withContext(Dispatchers.IO) { libraryRepository.userPlaylistsRaw() }
        val playlists = data.optJSONArray("v_playlist")
        val first = playlists?.optJSONObject(0)
        if (first == null) {
            JSONObject().put("v_playlistCount", 0).toString()
        } else {
            val allKeys = JSONArray()
            val keys = first.keys()
            while (keys.hasNext()) allKeys.put(keys.next())

            val playRelated = JSONObject()
            val playHints = listOf("play", "listen", "num", "cnt")
            val fieldKeys = first.keys()
            while (fieldKeys.hasNext()) {
                val key = fieldKeys.next()
                if (playHints.any { key.contains(it, ignoreCase = true) }) {
                    playRelated.put(key, first.opt(key))
                }
            }
            JSONObject()
                .put("v_playlistCount", playlists.length())
                .put("allKeys", allKeys)
                .put("playRelatedFields", playRelated)
                .toString()
        }
    }

    fun callPlaylistTracksAndCopy() = launchAndCopy("playlistTracks") {
        val dissid = _ui.value.dissid
        require(dissid.isNotBlank()) { "请先填写歌单 dissid" }
        val page = withContext(Dispatchers.IO) {
            libraryRepository.playlistTracksPage(dissid = dissid, begin = 0, limit = 20)
        }
        JSONObject()
            .put("total", page.total)
            .put("count", page.tracks.size)
            .put(
                "tracks",
                JSONArray().apply {
                    page.tracks.take(20).forEach { track ->
                        putJson(track) {
                            put("songMid", it.songMid)
                            put("mediaMid", it.mediaMid)
                            put("title", it.title)
                            put("artist", it.artist)
                            put("album", it.album)
                        }
                    }
                }
            ).toString()
    }

    /**
     * debug 诊断：复制歌单详情接口的原始字段（找播放量真值）。
     * 播放量在 `dirinfo`（歌单信息对象）里，不在 data 顶层，故先展开 dirinfo。
     */
    fun callPlaylistDetailRawAndCopy() = launchAndCopy("playlistDetailRaw") {
        val dissid = _ui.value.dissid
        require(dissid.isNotBlank()) { "请先填写歌单 dissid" }
        val data = withContext(Dispatchers.IO) {
            libraryRepository.playlistDetailRaw(dissid)
        }
        fun allKeys(obj: JSONObject): JSONArray = JSONArray().apply {
            val keys = obj.keys()
            while (keys.hasNext()) put(keys.next())
        }
        fun playRelated(obj: JSONObject): JSONObject = JSONObject().apply {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.contains("play", true) || key.contains("listen", true) ||
                    key.contains("num", true) || key.contains("visit", true)
                ) {
                    put(key, obj.opt(key))
                }
            }
        }
        // 播放量在 dirinfo（歌单信息对象）里；先输出它，避免被 4000 字符预览截断。
        val dirinfo = data.optJSONObject("dirinfo") ?: JSONObject()
        JSONObject()
            .put("dissid", dissid)
            .put("dirinfoAllKeys", allKeys(dirinfo))
            .put("dirinfoRaw", dirinfo)
            .put("dirinfoPlayRelated", playRelated(dirinfo))
            .put("dataAllKeys", allKeys(data))
            .put("playRelatedFields", playRelated(data))
            .put("dataLen", data.length())
            .toString()
    }

    fun callSearchSongsAndCopy() = launchAndCopy("searchSongs") {
        val keyword = _ui.value.keyword
        require(keyword.isNotBlank()) { "请先填写关键词" }
        val results = withContext(Dispatchers.IO) { searchApi.search(keyword, 1) }
        JSONObject()
            .put("keyword", keyword)
            .put("count", results.size)
            .put(
                "songs",
                JSONArray().apply {
                    results.take(20).forEach { song ->
                        putJson(song) {
                            put("id", it.id)
                            put("name", it.songName)
                            put("singer", it.singer)
                            put("album", it.albumName)
                        }
                    }
                }
            ).toString()
    }

    fun callSearchPlaylistsAndCopy() = launchAndCopy("searchPlaylists") {
        val keyword = _ui.value.keyword
        require(keyword.isNotBlank()) { "请先填写关键词" }
        val results = withContext(Dispatchers.IO) { libraryRepository.searchPlaylists(keyword, page = 1) }
        JSONObject()
            .put("keyword", keyword)
            .put(
                "playlists",
                JSONArray().apply {
                    results.take(20).forEach { playlist ->
                        putJson(playlist) {
                            put("id", it.id)
                            put("title", it.title)
                            put("trackCount", it.trackCount)
                        }
                    }
                }
            ).toString()
    }

    fun callSearchSingersAndCopy() = launchAndCopy("searchSingers") {
        val keyword = _ui.value.keyword
        require(keyword.isNotBlank()) { "请先填写关键词" }
        val results = withContext(Dispatchers.IO) { libraryRepository.searchSingers(keyword, page = 1) }
        JSONObject()
            .put("keyword", keyword)
            .put(
                "singers",
                JSONArray().apply {
                    results.take(20).forEach { singer ->
                        putJson(singer) {
                            put("mid", it.mid)
                            put("name", it.name)
                            put("songCount", it.songCount)
                        }
                    }
                }
            ).toString()
    }

    fun callSearchAlbumsAndCopy() = launchAndCopy("searchAlbums") {
        val keyword = _ui.value.keyword
        require(keyword.isNotBlank()) { "请先填写关键词" }
        val results = withContext(Dispatchers.IO) { libraryRepository.searchAlbums(keyword, page = 1) }
        JSONObject()
            .put("keyword", keyword)
            .put(
                "albums",
                JSONArray().apply {
                    results.take(20).forEach { album ->
                        putJson(album) {
                            put("mid", it.mid)
                            put("id", it.albumId)
                            put("title", it.title)
                            put("singer", it.singer)
                            put("songCount", it.songCount)
                            put("publishDate", it.publishDate)
                        }
                    }
                }
            ).toString()
    }

    /**
     * debug 诊断：复制搜索专辑接口首项的原始字段（search_type=2 / groupKey=album）。
     * 走 platform 的 `searchGroupRaw` 原样返回首项，保留
     * songNum / song_count 等真值与未知字段，避免固定字段解析把它们丢掉。
     */
    fun callSearchAlbumRawAndCopy() = launchAndCopy("searchAlbumRaw") {
        val keyword = _ui.value.keyword
        require(keyword.isNotBlank()) { "请先填写关键词" }
        val raw = withContext(Dispatchers.IO) {
            libraryRepository.searchGroupRaw(keyword, searchType = 2, groupKey = "album")
        }
        JSONObject()
            .put("keyword", keyword)
            .put("searchType", 2)
            .put("groupKey", "album")
            .put("firstAlbumRaw", raw)
            .toString()
    }

    /**
     * debug 诊断：复制搜索歌单接口首项的原始字段（search_type=3 / groupKey=songlist）。
     * 目的是读取播放量真值（play_cnt / listennum）——歌单列表接口该字段恒为 0。
     */
    fun callSearchPlaylistRawAndCopy() = launchAndCopy("searchPlaylistRaw") {
        val keyword = _ui.value.keyword
        require(keyword.isNotBlank()) { "请先填写关键词" }
        val raw = withContext(Dispatchers.IO) {
            libraryRepository.searchGroupRaw(keyword, searchType = 3, groupKey = "songlist")
        }
        JSONObject()
            .put("keyword", keyword)
            .put("searchType", 3)
            .put("groupKey", "songlist")
            .put("firstPlaylistRaw", raw)
            .toString()
    }

    fun callAlbumTracksAndCopy() = launchAndCopy("albumTracks") {
        val albumMid = _ui.value.albumMid
        require(albumMid.isNotBlank()) { "请先填写专辑 albumMid" }
        val page = withContext(Dispatchers.IO) {
            libraryRepository.albumTracksPage(albumMid = albumMid, begin = 0, limit = 20)
        }
        JSONObject()
            .put("total", page.total)
            .put("count", page.tracks.size)
            .put(
                "tracks",
                JSONArray().apply {
                    page.tracks.take(20).forEach { track ->
                        putJson(track) {
                            put("songMid", it.songMid)
                            put("title", it.title)
                            put("artist", it.artist)
                            put("album", it.album)
                        }
                    }
                }
            ).toString()
    }

    fun callSingerSongsAndCopy() = launchAndCopy("singerSongs") {
        val keyword = _ui.value.keyword
        val singers = withContext(Dispatchers.IO) { libraryRepository.searchSingers(keyword, page = 1) }
        val singer = singers.firstOrNull() ?: error("没有搜到歌手：$keyword")
        val page = withContext(Dispatchers.IO) {
            libraryRepository.singerSongs(singer.mid, page = 1, pageSize = 20)
        }
        JSONObject()
            .put("singer", singer.name)
            .put("mid", singer.mid)
            .put("total", page.total)
            .put("count", page.tracks.size)
            .toString()
    }

    /**
     * debug 诊断：复制歌手专辑接口的原始字段（走 platform raw 探针，未解析）。
     * mid 来源与 [callSingerSongsAndCopy] 一致：关键词搜索结果的第一个歌手——探针屏
     * 没有独立 singerMid 输入框，复用同一来源最省事。搜不到歌手时复制错误提示，不抛异常。
     */
    fun callSingerAlbumsRawAndCopy() = launchAndCopy("singerAlbumsRaw") {
        val keyword = _ui.value.keyword
        val singers = withContext(Dispatchers.IO) { libraryRepository.searchSingers(keyword, page = 1) }
        val mid = singers.firstOrNull()?.mid
        if (mid.isNullOrBlank()) {
            JSONObject().put("error", "请先搜索歌手以获得 mid（关键词：$keyword）").toString()
        } else {
            val data = withContext(Dispatchers.IO) { libraryRepository.singerAlbumsRaw(mid) }

            fun allKeys(obj: JSONObject): JSONArray = JSONArray().apply {
                val keys = obj.keys()
                while (keys.hasNext()) put(keys.next())
            }

            val albums = data.optJSONArray("albumList") ?: data.optJSONArray("albums")
            val first = albums?.optJSONObject(0)
            JSONObject()
                // 完整字段列表 / 首项原始 JSON 必须排最前：lastJsonPreview 仅保留 4000 字符，
                // 放末尾会被 data 展开挤掉，真机一直读不到 songNum 等字段真值。
                .put("firstAlbumAllKeys", if (first != null) allKeys(first) else JSONArray())
                .put("firstAlbumRaw", first ?: JSONObject())
                .put("albumsCount", albums?.length() ?: 0)
                .put("dataAllKeys", allKeys(data))
                .put("mid", mid)
                .toString()
        }
    }

    fun callVKeyAndCopy() = launchAndCopy("vkey") {
        val songMid = _ui.value.songMid
        require(songMid.isNotBlank()) { "请先填写 songMid" }
        val result = withContext(Dispatchers.IO) {
            playbackRepository.resolve(songMid = songMid, mediaMid = null, qualityKey = "lossless")
        }
        when (result) {
            is moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackResult.Playable ->
                JSONObject()
                    .put("playable", true)
                    .put("format", result.format)
                    .put("qualityKey", result.qualityKey)
                    .put("urlLength", result.url.length)
                    .put("urlHost", runCatching { java.net.URI(result.url).host }.getOrNull())
                    .toString()

            is moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackResult.LoginRequired ->
                JSONObject().put("playable", false).put("reason", "loginRequired").toString()

            is moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackResult.LoginExpired ->
                JSONObject().put("playable", false).put("reason", "loginExpired").toString()

            is moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackResult.Transport ->
                JSONObject().put("playable", false).put("reason", "transport").put("detail", result.reason).toString()

            is moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackResult.Unavailable ->
                JSONObject().put("playable", false).put("reason", result.reason).toString()
        }
    }

    fun callDailyRecommendAndCopy() = launchAndCopy("dailyRecommend") {
        val tracks = withContext(Dispatchers.IO) { libraryRepository.dailyRecommendSongs() }
        JSONObject()
            .put("count", tracks.size)
            .put(
                "tracks",
                JSONArray().apply {
                    tracks.take(20).forEach { track ->
                        putJson(track) {
                            put("songMid", it.songMid)
                            put("title", it.title)
                            put("artist", it.artist)
                        }
                    }
                }
            ).toString()
    }

    fun callPersonalRadarAndCopy() = launchAndCopy("personalRadar") {
        val tracks = withContext(Dispatchers.IO) { libraryRepository.personalRadarTracks() }
        JSONObject()
            .put("count", tracks.size)
            .put(
                "tracks",
                JSONArray().apply {
                    tracks.take(20).forEach { track ->
                        putJson(track) {
                            put("songMid", it.songMid)
                            put("title", it.title)
                            put("artist", it.artist)
                        }
                    }
                }
            ).toString()
    }

    fun callCommentsAndCopy() = launchAndCopy("comments") {
        val topId = _ui.value.commentTopId
        require(topId.isNotBlank()) { "请先填写评论 topid（数字歌曲 id）" }
        val page = withContext(Dispatchers.IO) {
            commentRepository.loadComments(
                source = CommentSource(
                    platform = CommentPlatform.QQ_MUSIC,
                    resourceId = topId.toLongOrNull() ?: 1L,
                    secondaryId = _ui.value.songMid.takeIf { it.isNotBlank() }
                ),
                page = 1,
                pageSize = 20
            )
        }
        JSONObject()
            .put("count", page.comments.size)
            .put("hasMore", page.hasMore)
            .put("total", page.total)
            .put(
                "comments",
                JSONArray().apply {
                    page.comments.take(5).forEach { comment ->
                        putJson(comment) {
                            put("id", it.id)
                            put("user", it.username)
                            put("content", it.content.take(120))
                            put("likes", it.likeCount)
                        }
                    }
                }
            ).toString()
    }
}
