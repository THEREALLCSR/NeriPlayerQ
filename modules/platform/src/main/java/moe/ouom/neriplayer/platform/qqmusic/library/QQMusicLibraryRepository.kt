package moe.ouom.neriplayer.platform.qqmusic.library

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
 * File: moe.ouom.neriplayer.platform.qqmusic.library/QQMusicLibraryRepository
 */

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.track.parseQqMusicTrack
import moe.ouom.neriplayer.platform.qqmusic.track.parseQqMusicTrackWrapper
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** QQ 音乐专辑（搜索/详情用的精简模型）。 */
data class QQMusicAlbum(
    val mid: String,
    val albumId: Long,
    val title: String,
    val singer: String,
    val coverUrl: String?,
    val songCount: Int,
    val publishDate: String?,
)

/** QQ 音乐歌单（列表页用的精简模型）。 */
data class QQMusicPlaylist(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val trackCount: Int,
    /** 写操作句柄：加歌/删除都用它（`tid` 是歌单 id，两者不是一回事）。 */
    val dirId: Long = 0L,
    /** 播放量（列表接口的 listennum），用于与网易云共用同一套副标题文案。 */
    val playCount: Long = 0L,
)

/** QQ 音乐歌手（搜索结果用）。 */
data class QQMusicSinger(
    val mid: String,
    val name: String,
    val coverUrl: String?,
    val songCount: Int,
)

/** QQ 音乐歌手详情（歌手页简介用，简介来自 `data.singer_brief`）。 */
data class QQMusicSingerDetail(
    val mid: String,
    val name: String,
    val description: String,
)

/** 一页歌单歌曲 + 服务端声明的总数（用于判断是否还有下一页）。 */
data class QQMusicPlaylistTracks(
    val tracks: List<QQMusicTrack>,
    val total: Int,
)

/**
 * QQ 音乐歌曲模型已下沉到 [moe.ouom.neriplayer.platform.qqmusic.track.QQMusicTrack]，
 * 这里保留同名别名，让已有的 `import ...library.QQMusicTrack` 继续可用。
 *
 * 保留别名的代价是"看起来还有两套模型"，但搜遍全仓已经没有第二份定义：
 * 别名只是引用转发，字段与解析都只有一处。
 */
typealias QQMusicTrack = moe.ouom.neriplayer.platform.qqmusic.track.QQMusicTrack

/**
 * QQ 音乐"我的音乐库"数据源。
 *
 * 两个接口（与 MeloX 中验证过的一致）：
 *  - 歌单列表：`music.musicasset.PlaylistBaseRead / GetPlaylistByUin`
 *  - 歌单详情：`music.srfDissInfo.DissInfo / CgiGetDiss`
 *
 * 请求身份：
 *  - 用"网页身份"（ct=24 / platform=yqq.json）即可读取自己的歌单；
 *  - 必须带 Cookie（登录态）与 `comm.uin`，否则只能拿到公开数据；
 *  - `g_tk = hash33(musicKey)`，QQ 用它做接口级校验。
 */
class QQMusicLibraryRepository(
    httpClient: OkHttpClient,
    private val sessionProvider: () -> QQMusicSession,
    /** 允许测试注入 MockWebServer 地址。 */
    baseUrl: String = moe.ouom.neriplayer.platform.qqmusic.network.QQMusicClient.DEFAULT_BASE_URL,
) {

    private val client = moe.ouom.neriplayer.platform.qqmusic.network.QQMusicClient(
        httpClient = httpClient,
        baseUrl = baseUrl,
    )
    /**
     * 拉取当前账号的歌单列表。
     *
     * 未登录时直接返回空列表（UI 显示"请先登录"），不浪费一次请求。
     */
    suspend fun userPlaylists(): List<QQMusicPlaylist> = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext emptyList()

        val payload = musicuPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseRead",
            method = "GetPlaylistByUin",
            param = JSONObject().put("uin", session.uin),
        )
        val data = postMusicu(payload, session)
        val array = data.optJSONArray("v_playlist") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = sequenceOf("dissid", "tid", "id")
                    .map { key -> item.opt(key)?.toString().orEmpty() }
                    .firstOrNull { it.isNotBlank() && it != "0" }
                    ?: item.opt("dirId")?.toString()?.takeIf { it != "0" }
                    ?: continue
                val playlist = QQMusicPlaylist(
                    id = id,
                    title = sequenceOf("dirName", "dissname", "title", "name")
                        .map { item.optString(it) }
                        .firstOrNull(String::isNotBlank)
                        ?: "未命名歌单",
                    coverUrl = firstImageUrl(item),
                    trackCount = sequenceOf("songNum", "songnum", "song_cnt")
                        .map { item.optInt(it, 0) }
                        .firstOrNull { it > 0 } ?: 0,
                    dirId = item.optLong("dirId", 0L),
                    playCount = item.optLong("play_cnt", item.optLong("listennum", 0L)),
                )
                // 封面兜底：没有封面但已经有歌的歌单，用第一首歌的封面。
                // 新建歌单在加入第一首歌之前一直保持"无封面"。
                val enriched = if (
                    playlist.coverUrl.isNullOrBlank() &&
                    playlist.trackCount > 0 &&
                    index < COVER_ENRICH_LIMIT
                ) {
                    val firstCover = runCatching {
                        playlistTracks(playlist.id, limit = 1).firstOrNull()?.coverUrl
                    }.getOrNull()
                    if (firstCover.isNullOrBlank()) playlist else playlist.copy(coverUrl = firstCover)
                } else {
                    playlist
                }
                add(enriched)
            }
        }
    }

    /**
     * debug 诊断：返回 v_playlist 列表接口的原始 data（未解析），用于确认播放量真实字段名。
     * 生产 UI 不要调用。
     *
     * session 校验与请求 payload 与 [userPlaylists] 完全一致，但拿到 `req_0.data` 后
     * 直接原样返回——不做任何字段解析，保留未知字段（真机定位播放量字段名的关键）。
     */
    suspend fun userPlaylistsRaw(): JSONObject = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext JSONObject()

        val payload = musicuPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseRead",
            method = "GetPlaylistByUin",
            param = JSONObject().put("uin", session.uin),
        )
        val data = postMusicu(payload, session)
        if (data.length() == 0) JSONObject() else data
    }

    /**
     * 拉取某个歌单的歌曲。
     *
     * @param dissid 歌单 ID（[userPlaylists] 返回的 id）
     * @param begin  从第几首开始（用于"滚动到底自动加载下一页"）
     * @param limit  本次取多少首（QQ 单页上限 200）
     */
    suspend fun playlistTracks(
        dissid: String,
        begin: Int = 0,
        limit: Int = 100,
    ): List<QQMusicTrack> = playlistTracksPage(dissid, begin, limit).tracks

    /**
     * 分页拉取歌单歌曲，并返回服务端声明的歌曲总数。
     *
     * 为什么需要 total：QQ 的 CgiGetDiss 单页最多返回 100 首左右，
     * 而且会把无版权/已下架的歌曲过滤掉，实际条数可能少于请求数。
     * 只按"本页是否满 100"判断 hasMore 会提前停止（表现为只显示前 98 首）。
     */
    suspend fun playlistTracksPage(
        dissid: String,
        begin: Int = 0,
        limit: Int = 100,
    ): QQMusicPlaylistTracks =
        withContext(Dispatchers.IO) {
            val session = sessionProvider()
            if (!session.isLoggedIn) return@withContext QQMusicPlaylistTracks(emptyList(), 0)
            val numericId = dissid.toLongOrNull() ?: return@withContext QQMusicPlaylistTracks(emptyList(), 0)

            val payload = musicuPayload(
                session = session,
                module = "music.srfDissInfo.DissInfo",
                method = "CgiGetDiss",
                param = JSONObject()
                    .put("disstid", numericId)
                    .put("dirid", 0)
                    .put("tag", true)
                    .put("song_begin", begin.coerceAtLeast(0))
                    .put("song_num", limit.coerceIn(1, 200))
                    .put("userinfo", true)
                    .put("orderlist", true)
                    .put("onlysonglist", false),
            )
            val data = postMusicu(payload, session)
            val songs = data.optJSONArray("songlist") ?: JSONArray()
            val total = sequenceOf("total_song_num", "songnum", "song_num", "total")
                .map { data.optInt(it, 0) }
                .firstOrNull { it > 0 } ?: songs.length()
            val tracks = buildList {
                for (index in 0 until songs.length()) {
                    val song = songs.optJSONObject(index) ?: continue
                    parseQqMusicTrack(song)?.let(::add)
                }
            }
            QQMusicPlaylistTracks(tracks = tracks, total = total)
        }

    /**
     * debug 诊断：原样返回歌单详情（`music.srfDissInfo.DissInfo / CgiGetDiss`）的 data。
     *
     * 与 [playlistTracksPage] 同 payload（disstid/dirid/tag/userinfo/orderlist），
     * 但 song_num 只取 1（不为拿头部字段拉整页歌曲），拿到 `req_0.data` 后原样返回——
     * 不解析、不过滤，保留未知字段（真机确认播放量字段名 visitnum/listennum 的关键）。
     * 非数字 dissid 或未登录返回空 JSONObject 且不发请求。生产 UI 不要调用。
     */
    suspend fun playlistDetailRaw(dissid: String): JSONObject = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext JSONObject()
        val numericId = dissid.toLongOrNull() ?: return@withContext JSONObject()
        val payload = musicuPayload(
            session = session,
            module = "music.srfDissInfo.DissInfo",
            method = "CgiGetDiss",
            param = JSONObject()
                .put("disstid", numericId)
                .put("dirid", 0)
                .put("tag", true)
                .put("song_begin", 0)
                .put("song_num", 1)
                .put("userinfo", true)
                .put("orderlist", true)
                .put("onlysonglist", false),
        )
        postMusicu(payload, session)
    }

    /**
     * 歌单播放量（真值）。
     *
     * 真机依据（2026-11-25，dissid=7011264340）：列表接口 v_playlist 的 play_cnt 恒 0；
     * 歌单详情 CgiGetDiss 的 `data.dirinfo.listennum` 是真值（实测 146608 = 14.6万）。
     *
     * 只取 song_num = 1 拿头部 dirinfo，不为计数拉整页歌曲；listennum > 0 才返回，
     * 0/缺失/无 dirinfo 视为"未核实"返回 null（不伪造 0）。
     * 非数字 dissid 或未登录返回 null 且不发请求。
     */
    suspend fun playlistPlayCount(dissid: String): Long? = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext null
        val numericId = dissid.toLongOrNull() ?: return@withContext null
        val payload = musicuPayload(
            session = session,
            module = "music.srfDissInfo.DissInfo",
            method = "CgiGetDiss",
            param = JSONObject()
                .put("disstid", numericId)
                .put("dirid", 0)
                .put("tag", true)
                .put("song_begin", 0)
                .put("song_num", 1)
                .put("userinfo", true)
                .put("orderlist", true)
                .put("onlysonglist", false),
        )
        val data = postMusicu(payload, session)
        val count = data.optJSONObject("dirinfo")?.optLong("listennum", 0L) ?: 0L
        count.takeIf { it > 0L }
    }

    /**
     * 新建歌单。
     *
     * 接口：`music.musicasset.PlaylistBaseWrite / AddPlaylist`
     *  - 返回的 `data.result` 里同时有 `dirId`（后续加歌/删除用的句柄）和 `tid`（歌单 id）；
     *  - 本仓库把 `tid` 作为 [QQMusicPlaylist.id]，与读歌单时的 dissid 对齐。
     *
     * @throws IOException 未登录、名称非法或服务端拒绝时抛出。
     */
    suspend fun createPlaylist(name: String): QQMusicPlaylist = withContext(Dispatchers.IO) {
        val title = name.trim()
        if (title.isEmpty() || title.length > 80) {
            throw IOException("歌单名称需要 1-80 个字符")
        }
        val session = sessionProvider()
        if (!session.isLoggedIn) throw IOException("QQ 音乐未登录，无法新建歌单")

        val payload = musicuPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseWrite",
            method = "AddPlaylist",
            param = JSONObject()
                .put("dirName", title)
                .put("dirDesc", "")
                .put("dirPicUrl", "")
                .put("taglist", JSONArray()),
        )
        val data = postMusicu(payload, session)
        val result = data.optJSONObject("result")
            ?: throw IOException("QQ 音乐没有返回新建结果")
        val tid = result.optLong("tid", 0L)
        val dirId = result.optInt("dirId", 0)
        if (tid <= 0L) {
            throw IOException("QQ 音乐新建歌单失败（没有返回歌单 id）")
        }
        QQMusicPlaylist(
            id = tid.toString(),
            title = result.optString("dirName").ifBlank { title },
            coverUrl = null,
            trackCount = 0,
            dirId = dirId.toLong(),
        )
    }

    /**
     * 往歌单里添加歌曲。
     *
     * 接口：`music.musicasset.PlaylistDetailWrite / AddSonglist`
     *  - 参数用 `dirId`（写句柄），不是 `tid`；
     *  - QQ 要求提供**数字 songId + songType**，而我们的界面只有 songmid，
     *    所以这里先逐首查一次详情拿 id/type；
     *  - 单批最多 20 首（接口限制）；调用方可能传整页/全选，本函数内部分批以匹配该上限。
     *
     * @return 实际添加成功的歌曲数。
     */
    suspend fun addSongsToPlaylist(
        playlist: QQMusicPlaylist,
        songMids: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) throw IOException("QQ 音乐未登录，无法添加歌曲")
        if (playlist.dirId <= 0L) {
            throw IOException("歌单缺少写入句柄（dirId），请刷新歌单列表后重试")
        }
        // 调用方（收藏歌单/全选）可能把整页歌曲（可达 100 首）一次性传入。
        // 先逐首查详情拿数字 id/type，再把 identity 按 QQ 单批上限 20 分批写入：
        // 既避免 take(20) 静默丢弃第 21 首起的歌曲，也不会因单批超量被服务端拒绝。
        val identities = songMids
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { mid -> runCatching { songIdentity(mid, session) }.getOrNull() }
            .toList()
        if (identities.isEmpty()) throw IOException("没有可添加的歌曲")

        var added = 0
        for (batch in identities.chunked(MAX_ADD_SONGS_PER_REQUEST)) {
            val songInfo = JSONArray().apply {
                batch.forEach { (songId, songType) ->
                    put(JSONObject().put("songId", songId).put("songType", songType))
                }
            }
            val payload = musicuPayload(
                session = session,
                module = "music.musicasset.PlaylistDetailWrite",
                method = "AddSonglist",
                param = JSONObject().put("dirId", playlist.dirId).put("v_songInfo", songInfo),
            )
            postMusicu(payload, session)
            added += batch.size
        }
        added
    }

    /**
     * 查询歌曲的数字 id（评论区接口用 `topid` 需要它）。
     *
     * @return 查不到时返回 null（未登录、歌曲不存在或接口异常）。
     */
    suspend fun songNumericId(songMid: String): Long? = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext null
        runCatching { songIdentity(songMid, session)?.first }.getOrNull()
    }

    /** 查询歌曲的数字 id 与类型（AddSonglist 需要它们）。 */
    private fun songIdentity(songMid: String, session: QQMusicSession): Pair<Long, Int>? {
        val payload = musicuPayload(
            session = session,
            module = "music.pf_song_detail_svr",
            method = "get_song_detail_yqq",
            param = JSONObject().put("song_mid", songMid),
        )
        val track = postMusicu(payload, session).optJSONObject("track_info") ?: return null
        val id = track.optLong("id", 0L)
        if (id <= 0L) return null
        return id to track.optInt("type", 0)
    }

    /**
     * 从歌单移除歌曲。
     *
     * 接口：`music.musicasset.PlaylistDetailWrite / DelSonglist`
     * 同样需要 `dirId` 与数字 `songId/songType`。
     *
     * @return 实际移除的歌曲数。
     */
    suspend fun removeSongsFromPlaylist(
        playlist: QQMusicPlaylist,
        songMids: List<String>,
    ): Int = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) throw IOException("QQ 音乐未登录，无法移除歌曲")
        if (playlist.dirId <= 0L) {
            throw IOException("歌单缺少写入句柄（dirId），请刷新歌单列表后重试")
        }
        // 同 addSongsToPlaylist：调用方可能传整页/全选（可达 100 首）。
        // 先查齐所有 identity，再按 QQ 单批上限 20 分批 DelSonglist，避免静默截断或超量被拒。
        val identities = songMids
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { mid -> runCatching { songIdentity(mid, session) }.getOrNull() }
            .toList()
        if (identities.isEmpty()) throw IOException("没有可移除的歌曲")

        var removed = 0
        for (batch in identities.chunked(MAX_ADD_SONGS_PER_REQUEST)) {
            val songInfo = JSONArray().apply {
                batch.forEach { (songId, songType) ->
                    put(JSONObject().put("songId", songId).put("songType", songType))
                }
            }
            val payload = musicuPayload(
                session = session,
                module = "music.musicasset.PlaylistDetailWrite",
                method = "DelSonglist",
                param = JSONObject().put("dirId", playlist.dirId).put("v_songInfo", songInfo),
            )
            postMusicu(payload, session)
            removed += batch.size
        }
        removed
    }

    /**
     * 修改歌单信息（名称 / 简介 / 公开状态）。
     *
     * 接口：`music.musicasset.PlaylistBaseWrite / EditPlaylist`。
     * `mask` 是位掩码，决定本次更新哪些字段：
     *  - 1：名称（`dirNewName`）
     *  - 2：简介（`dirNewDesc`）
     *  - 16：公开状态（`dirShow`，1 公开 / 0 私密）
     *
     * 从 App 反编译确认（QQMusic 20.9.5.8）。
     *
     * @throws IOException 未登录、缺少 `dirId`、名称为空或服务端拒绝时抛出。
     */
    suspend fun updatePlaylist(
        playlist: QQMusicPlaylist,
        name: String? = null,
        description: String? = null,
        isPublic: Boolean? = null,
    ): QQMusicPlaylist = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) throw IOException("QQ 音乐未登录，无法修改歌单")
        if (playlist.dirId <= 0L) {
            throw IOException("歌单缺少写入句柄（dirId），请刷新歌单列表后重试")
        }

        var mask = 0L
        val param = JSONObject().put("dirId", playlist.dirId)
        name?.trim()?.let { title ->
            if (title.isEmpty() || title.length > 80) {
                throw IOException("歌单名称需要 1-80 个字符")
            }
            mask = mask or 1L
            param.put("dirNewName", title)
        }
        description?.let { desc ->
            mask = mask or 2L
            param.put("dirNewDesc", desc)
        }
        isPublic?.let { public ->
            mask = mask or 16L
            param.put("dirShow", if (public) 1 else 0)
        }
        if (mask == 0L) throw IOException("没有要修改的内容")
        param.put("mask", mask)
        param.put("bFmtUtf8", true)

        val payload = musicuPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseWrite",
            method = "EditPlaylist",
            param = param,
        )
        val data = postMusicu(payload, session)
        val result = data.optJSONObject("result") ?: data
        playlist.copy(
            title = result.optString("dirName").ifBlank { name?.trim() ?: playlist.title },
            trackCount = result.optInt("songNum", playlist.trackCount),
        )
    }

    /**
     * 删除歌单。
     *
     * 接口：`music.musicasset.PlaylistBaseWrite / DelPlaylist`。
     * 与加歌/删歌一样使用 `dirId`（写句柄），不是 `tid`。
     */
    suspend fun deletePlaylist(playlist: QQMusicPlaylist) = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) throw IOException("QQ 音乐未登录，无法删除歌单")
        if (playlist.dirId <= 0L) {
            throw IOException("歌单缺少写入句柄（dirId），请刷新歌单列表后重试")
        }
        val payload = musicuPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseWrite",
            method = "DelPlaylist",
            param = JSONObject().put("dirId", playlist.dirId),
        )
        postMusicu(payload, session)
    }

    /**
     * 搜索公开歌单（对应搜索页的"歌单"分类）。
     *
     * 接口：`music.search.SearchCgiService / DoSearchForQQMusicDesktop`，`search_type = 3`。
     */
    suspend fun searchPlaylists(keyword: String, page: Int): List<QQMusicPlaylist> =
        withContext(Dispatchers.IO) {
            val session = sessionProvider()
            if (!session.isLoggedIn) return@withContext emptyList()
            searchGroup(session, keyword, page, searchType = 3, groupKey = "songlist")
                .mapNotNull { item ->
                    val dissid = item.optString("dissid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    QQMusicPlaylist(
                        id = dissid,
                        title = item.optString("dissname").ifBlank { "未命名歌单" },
                        coverUrl = item.optString("imgurl").takeIf { it.isNotBlank() }?.let(::upgradeHttps),
                        trackCount = item.optInt("song_count", 0),
                        playCount = item.optLong("play_cnt", item.optLong("listennum", 0L)),
                    )
                }
        }

    /**
     * 搜索专辑（`search_type = 2`）。
     *
     * 实测字段：`albumMID / albumID / albumName / singerName / song_count / publicTime / albumPic`。
     */
    suspend fun searchAlbums(keyword: String, page: Int): List<QQMusicAlbum> =
        withContext(Dispatchers.IO) {
            val session = sessionProvider()
            if (!session.isLoggedIn) return@withContext emptyList()
            searchGroup(session, keyword, page, searchType = 2, groupKey = "album")
                .mapNotNull { item ->
                    val mid = sequenceOf("albumMID", "albumMid")
                        .map { item.optString(it) }
                        .firstOrNull(String::isNotBlank)
                        ?: return@mapNotNull null
                    QQMusicAlbum(
                        mid = mid,
                        albumId = item.optLong("albumID", 0L),
                        title = item.optString("albumName").ifBlank { "未命名专辑" },
                        singer = item.optString("singerName").ifBlank { "未知歌手" },
                        coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M000$mid.jpg",
                        songCount = item.optInt("song_count", 0),
                        publishDate = item.optString("publicTime").takeIf { it.isNotBlank() },
                    )
                }
        }

    /**
     * 专辑曲目（分页）。
     *
     * 接口：`music.musichallAlbum.AlbumSongList / GetAlbumSongList`
     */
    suspend fun albumTracksPage(
        albumMid: String,
        begin: Int,
        limit: Int
    ): QQMusicPlaylistTracks = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext QQMusicPlaylistTracks(emptyList(), 0)
        val payload = musicuPayload(
            session = session,
            module = "music.musichallAlbum.AlbumSongList",
            method = "GetAlbumSongList",
            param = JSONObject()
                .put("albumMid", albumMid)
                .put("begin", begin.coerceAtLeast(0))
                .put("num", limit.coerceIn(1, 200))
        )
        val data = postMusicu(payload, session)
        val list = data.optJSONArray("songList") ?: JSONArray()
        val tracks = buildList {
            for (index in 0 until list.length()) {
                val wrapper = list.optJSONObject(index) ?: continue
                parseQqMusicTrackWrapper(wrapper)?.let(::add)
            }
        }
        QQMusicPlaylistTracks(
            tracks = tracks,
            total = data.optInt("totalNum", tracks.size).coerceAtLeast(tracks.size)
        )
    }

    /**
     * 专辑歌曲总数（真值）。
     *
     * 真机依据（2026-11-25）：
     * - 歌手页 `GetAlbumList` 的专辑项完整 13 字段里没有 `songNum` / `song_count`，
     *   其 `totalNum` 恒为 0 —— 列表接口拿不到歌曲数，专辑 Tab 只能显示"0 首"；
     * - 专辑详情接口 `music.musichallAlbum.AlbumSongList / GetAlbumSongList` 返回的
     *   `totalNum` 是真值（"少女与战车"原声带实测 `total = 49`）。
     *
     * 因此这里用详情接口补数，且 `num = 1` 只取一首：目的是拿 `totalNum`，
     * 不为计数去拉整张专辑的曲目。
     *
     * 只用 `totalNum > 0` 的真值：为 0 或缺字段都视为"未核实"，返回 null，
     * 交由上层保持原样，不把未知伪造成 0。未登录返回 null 且不发请求。
     */
    suspend fun albumSongCount(albumMid: String): Int? = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext null
        val payload = musicuPayload(
            session = session,
            module = "music.musichallAlbum.AlbumSongList",
            method = "GetAlbumSongList",
            param = JSONObject()
                .put("albumMid", albumMid)
                .put("begin", 0)
                .put("num", 1)
        )
        val data = postMusicu(payload, session)
        val total = data.optInt("totalNum", 0)
        total.takeIf { it > 0 }
    }

    /**
     * 搜索歌手（对应搜索页的"歌手"分类）。
     *
     * 接口同上，`search_type = 1`。
     */
    suspend fun searchSingers(keyword: String, page: Int): List<QQMusicSinger> =
        withContext(Dispatchers.IO) {
            val session = sessionProvider()
            if (!session.isLoggedIn) return@withContext emptyList()
            searchGroup(session, keyword, page, searchType = 1, groupKey = "singer")
                .mapNotNull { item ->
                    val mid = item.optString("singerMID").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    QQMusicSinger(
                        mid = mid,
                        name = item.optString("singerName").ifBlank { "未知歌手" },
                        coverUrl = item.optString("singerPic").takeIf { it.isNotBlank() }?.let(::upgradeHttps),
                        songCount = item.optInt("songNum", 0),
                    )
                }
        }

    /**
     * 拉取歌手作品（歌手详情页用）。
     *
     * 接口：`musichall.song_list_server / GetSingerSongList`
     *  - `begin/number` 分页，`order = 1` 表示按热度/默认排序；
     *  - 每项形如 `{ songInfo: {...} }`，兼容直接返回歌曲对象的情况。
     */
    suspend fun singerSongs(
        singerMid: String,
        page: Int,
        pageSize: Int = 100,
    ): QQMusicPlaylistTracks = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext QQMusicPlaylistTracks(emptyList(), 0)
        val size = pageSize.coerceIn(1, 200)
        val payload = musicuPayload(
            session = session,
            module = "musichall.song_list_server",
            method = "GetSingerSongList",
            param = JSONObject()
                .put("singerMid", singerMid)
                .put("order", 1)
                .put("number", size)
                .put("begin", (page.coerceAtLeast(1) - 1) * size)
        )
        val data = postMusicu(payload, session)
        val list = data.optJSONArray("songList") ?: JSONArray()
        val tracks = buildList {
            for (index in 0 until list.length()) {
                val wrapper = list.optJSONObject(index) ?: continue
                parseQqMusicTrackWrapper(wrapper)?.let(::add)
            }
        }
        val total = sequenceOf("totalNum", "total_num", "total")
            .map { data.optInt(it, 0) }
            .firstOrNull { it > 0 } ?: tracks.size
        QQMusicPlaylistTracks(tracks = tracks, total = total)
    }

    /**
     * 歌手详情（歌手页简介）。
     *
     * 接口：`music.web_singer_info_svr / get_singer_detail_info`，简介在 `data.singer_brief`
     * （A 级，simple-music 2026-08-24 实测）。
     *
     * 注意：param 内字段名（singerMid）按项目 [singerSongs] 的惯例填写，真机待验证。
     */
    suspend fun singerDetail(singerMid: String): QQMusicSingerDetail = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) {
            return@withContext QQMusicSingerDetail(mid = singerMid, name = "", description = "")
        }
        val payload = musicuPayload(
            session = session,
            module = "music.web_singer_info_svr",
            method = "get_singer_detail_info",
            // param 字段名按惯例，真机待验证
            param = JSONObject().put("singerMid", singerMid),
        )
        val data = postMusicu(payload, session)
        QQMusicSingerDetail(
            mid = sequenceOf("singer_mid", "mid")
                .map { data.optString(it) }
                .firstOrNull(String::isNotBlank) ?: singerMid,
            name = sequenceOf("singer_name", "name")
                .map { data.optString(it) }
                .firstOrNull(String::isNotBlank) ?: "",
            description = sequenceOf("singer_brief", "brief", "description")
                .map { data.optString(it) }
                .firstOrNull(String::isNotBlank) ?: "",
        )
    }

    /**
     * 歌手专辑列表（歌手页专辑 Tab）。
     *
     * 接口：`music.musichallAlbum.AlbumListServer / GetAlbumList`（A 级）。
     * 解析与封面拼法复用 [searchAlbums]，专辑模型直接复用 [QQMusicAlbum]。
     *
     * 实测字段（第 026 次 raw 探针，2026-11-24）：`albumMid / albumName / albumTranName /
     * publishDate / totalNum / albumType / albumPmid / albumID / singerName / tags / specIcon /
     * pubText / trace`。歌曲数取 totalNum（专辑项无 song_count；song_count 仅作旧 mock 兜底）。
     *
     * 注意：param 内字段名（singerMid / offset / limit）按项目惯例填写，真机待验证。
     */
    suspend fun singerAlbums(
        singerMid: String,
        offset: Int = 0,
        limit: Int = 30,
    ): List<QQMusicAlbum> = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext emptyList()
        val payload = musicuPayload(
            session = session,
            module = "music.musichallAlbum.AlbumListServer",
            method = "GetAlbumList",
            // param 字段名按惯例，真机待验证
            param = JSONObject()
                .put("singerMid", singerMid)
                .put("offset", offset)
                .put("limit", limit),
        )
        val data = postMusicu(payload, session)
        val albums = data.optJSONArray("albumList")
            ?: data.optJSONArray("albums")
            ?: JSONArray()
        buildList {
            for (index in 0 until albums.length()) {
                val item = albums.optJSONObject(index) ?: continue
                val mid = sequenceOf("albumMID", "albumMid")
                    .map { item.optString(it) }
                    .firstOrNull(String::isNotBlank) ?: continue
                add(QQMusicAlbum(
                    mid = mid,
                    albumId = item.optLong("albumID", 0L),
                    title = item.optString("albumName").ifBlank { "未命名专辑" },
                    singer = item.optString("singerName").ifBlank { "未知歌手" },
                    coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M000$mid.jpg",
                    songCount = item.optInt("totalNum", item.optInt("song_count", 0)),
                    publishDate = item.optString("publicTime").takeIf { it.isNotBlank() },
                ))
            }
        }
    }

    /**
     * debug 诊断：返回歌手专辑接口的原始 data（未解析），用于确认真机字段真值。生产 UI 不要调用。
     *
     * session 校验与请求 payload 与 [singerAlbums] 完全一致，但拿到 `req_0.data` 后
     * 直接原样返回——不做任何字段解析，保留未知字段（真机诊断关键）。
     */
    suspend fun singerAlbumsRaw(singerMid: String): JSONObject = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext JSONObject()

        val payload = musicuPayload(
            session = session,
            module = "music.musichallAlbum.AlbumListServer",
            method = "GetAlbumList",
            param = JSONObject()
                .put("singerMid", singerMid)
                .put("offset", 0)
                .put("limit", 30),
        )
        val data = postMusicu(payload, session)
        if (data.length() == 0) JSONObject() else data
    }

    /**
     * debug 诊断：原样返回搜索接口（`DoSearchForQQMusicDesktop`）指定分组首项的完整 JSON。
     *
     * 请求 payload 与 [searchGroup] 同源（module/method/param 一致，便于真机对照），
     * 但拿到 `req_0.data` 后直接取 `body.<groupKey>.list[0]` 原样返回——不解析、不过滤，
     * 保留未知字段与真值（如搜索专辑的 songNum/song_count、搜索歌单的 play_cnt/listennum）。
     * 列表为空或未登录时返回空 JSONObject；未登录不发请求。生产 UI 不要调用。
     */
    suspend fun searchGroupRaw(
        keyword: String,
        searchType: Int,
        groupKey: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext JSONObject()

        val payload = musicuPayload(
            session = session,
            module = "music.search.SearchCgiService",
            method = "DoSearchForQQMusicDesktop",
            param = JSONObject()
                .put("searchid", System.nanoTime().toString())
                .put("query", keyword.trim())
                .put("search_type", searchType)
                .put("num_per_page", 20)
                .put("page_num", 1)
                .put("highlight", false)
                .put("grp", true)
                .put("selectors", JSONObject())
                .put("vec_selectors", JSONArray())
        )
        val data = postMusicu(payload, session)
        data.optJSONObject("body")
            ?.optJSONObject(groupKey)
            ?.optJSONArray("list")
            ?.optJSONObject(0)
            ?: JSONObject()
    }

    /**
     * 每日推荐（QQ 系统目录 `dirid = 202`，实测返回 30 首）。
     *
     * 接口：`music.srfDissInfo.aiDissInfo / uniform_get_Dissinfo`
     */
    suspend fun dailyRecommendSongs(): List<QQMusicTrack> = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext emptyList()
        val payload = musicuPayload(
            session = session,
            module = "music.srfDissInfo.aiDissInfo",
            method = "uniform_get_Dissinfo",
            param = JSONObject()
                .put("dirid", 202)
                .put("optype", 3)
                .put("tag", 1)
                .put("userinfo", 0)
                .put("pic_dpi", 300)
        )
        val data = postMusicu(payload, session)
        val list = data.optJSONArray("songlist") ?: JSONArray()
        buildList {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let(::parseQqMusicTrackWrapper)?.let(::add)
            }
        }
    }

    /**
     * 「我喜欢」列表（QQ 系统目录 `dirid = 201`，`disstid = 0`）。
     *
     * 接口：`music.srfDissInfo.DissInfo / CgiGetDiss`——与 [playlistTracksPage] 同构，
     * 唯一差别是走系统目录（dirid=201）而非真实歌单（dirid=0）。
     * 登录态校验、错误分类均复用现有链路。
     */
    suspend fun likedSongs(): List<QQMusicTrack> = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext emptyList()
        val payload = musicuPayload(
            session = session,
            module = "music.srfDissInfo.DissInfo",
            method = "CgiGetDiss",
            param = JSONObject()
                .put("disstid", 0)
                .put("dirid", 201)
                .put("song_begin", 0)
                .put("song_num", 200)
                .put("tag", true)
                .put("userinfo", true),
        )
        val data = postMusicu(payload, session)
        val list = data.optJSONArray("songlist") ?: JSONArray()
        buildList {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let(::parseQqMusicTrack)?.let(::add)
            }
        }
    }

    /**
     * 私人雷达 / 猜你喜欢（QQ 个性化电台，`id = 99` 为私人 FM）。
     *
     * 接口：`music.radioProxy.MbTrackRadioSvr / get_radio_track`
     */
    suspend fun personalRadarTracks(num: Int = 15): List<QQMusicTrack> = withContext(Dispatchers.IO) {
        val session = sessionProvider()
        if (!session.isLoggedIn) return@withContext emptyList()
        val payload = musicuPayload(
            session = session,
            module = "music.radioProxy.MbTrackRadioSvr",
            method = "get_radio_track",
            param = JSONObject()
                .put("id", 99)
                .put("firstplay", 1)
                .put("num", num.coerceIn(1, 50))
        )
        val data = postMusicu(payload, session)
        val list = data.optJSONArray("tracks") ?: JSONArray()
        buildList {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let(::parseQqMusicTrackWrapper)?.let(::add)
            }
        }
    }

    private fun searchGroup(
        session: QQMusicSession,
        keyword: String,
        page: Int,
        searchType: Int,
        groupKey: String,
    ): List<JSONObject> {
        val payload = musicuPayload(
            session = session,
            module = "music.search.SearchCgiService",
            method = "DoSearchForQQMusicDesktop",
            param = JSONObject()
                .put("searchid", System.nanoTime().toString())
                .put("query", keyword.trim())
                .put("search_type", searchType)
                .put("num_per_page", 20)
                .put("page_num", page.coerceAtLeast(1))
                .put("highlight", false)
                .put("grp", true)
                .put("selectors", JSONObject())
                .put("vec_selectors", JSONArray())
        )
        val data = postMusicu(payload, session)
        val list = data.optJSONObject("body")
            ?.optJSONObject(groupKey)
            ?.optJSONArray("list")
            ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun upgradeHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }

    // ── 网络细节 ────────────────────────────────────────────────────────────

    /** 组装 musicu.fcg 的标准请求体（网页身份）。信封细节见 [qqMusicPayload]。 */
    private fun musicuPayload(
        session: QQMusicSession,
        module: String,
        method: String,
        param: JSONObject,
    ): JSONObject = qqMusicPayload(
        session = session,
        module = module,
        method = method,
        param = param
    )

    /**
     * 发送请求并剥掉统一信封，返回 `req_0.data`。
     *
     * 传输与错误分类统一走 [moe.ouom.neriplayer.platform.qqmusic.network.QQMusicClient]：
     * 改造前三处各自处理 HTTP 状态与 `req_0.code`，导致同一个"登录过期"在不同功能上
     * 表现成不同文案。
     */
    private fun postMusicu(payload: JSONObject, session: QQMusicSession): JSONObject =
        client.post(payload, session, acceptCodes = WRITE_ACCEPT_CODES)

    private fun firstImageUrl(item: JSONObject): String? = sequenceOf(
        "picUrl", "bigpicUrl", "coverUrl", "logo", "picurl",
    )
        .map { item.optString(it) }
        .firstOrNull(String::isNotBlank)
        ?.let { if (it.startsWith("http://")) "https://${it.substringAfter("://")}" else it }

    private companion object {
        const val MAX_ADD_SONGS_PER_REQUEST = 20
        const val COVER_ENRICH_LIMIT = 8

        /** 歌单写接口的“已完成”类返回码（带 result / retCode=0）。 */
        val WRITE_ACCEPT_CODES = setOf(80081, 80083)
    }
}
