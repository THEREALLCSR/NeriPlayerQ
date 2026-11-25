package moe.ouom.neriplayer.platform.search.api.client

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
 *
 * File: moe.ouom.neriplayer.platform.search.api.client/QQMusicSearchApi
 * Created: 2025/8/17
 */

import android.annotation.SuppressLint
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicApiException
import moe.ouom.neriplayer.platform.search.api.NativeLyricSearchApi
import moe.ouom.neriplayer.platform.search.api.codec.decodeQQMusicLyricPayload
import moe.ouom.neriplayer.platform.search.api.codec.stripUntranslatedPlaceholderLines
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicDetailResponse
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicLyricContainer
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicSearchAuth
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicSearchResponse
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicSongMetadata
import moe.ouom.neriplayer.data.model.qqmusic.search.QQMusicTrackInfo
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.qqmusic.qqMusicAlbumCoverUrl
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
class QQMusicSearchApi(
    private val client: OkHttpClient,
    private val debugLogging: Boolean = false,
    /**
     * QQ 登录态读取器（本地 fork 新增）：
     * 搜索接口需要 `uin / g_tk / Cookie`，匿名请求会被网关以 2001 拒绝。
     *
     * 这里只接收 model 层的纯数据快照 [QQMusicSearchAuth]，由登录态持有方负责计算
     * g_tk 并传入，使本类无需反向依赖 `platform.qqmusic.auth`（满足包边界门禁）。
     */
    private val authProvider: () -> QQMusicSearchAuth? = { null }
) : NativeLyricSearchApi {

    companion object {
        private const val TAG = "QQMusicSearchApi"
        private const val DEBUG_JSON_PREVIEW_MAX_CHARS = 512
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        return withContext(Dispatchers.IO) {
            // 旧的 c.y.qq.com/soso/fcgi-bin/client_search_cp 目前会返回 HTTP 500，
            // 改用 musicu 的搜索模块（与 MeloX 中验证过的写法一致）。
            // 注意：
            //  - searchid 每次必须重新生成，复用会让网关返回 2001 + 空列表；
            //  - comm 必须带 uin / g_tk，且用 POST + Cookie，匿名 GET 会同样返回 2001。
            val auth = authProvider()
            val gtk = auth?.gTk ?: 0L
            val comm = JSONObject()
                .put("ct", 11)
                .put("cv", 0)
                .put("platform", "yqq.json")
                .put("chid", "0")
                .put("uin", auth?.uin ?: 0L)
                .put("g_tk", gtk)
                .put("g_tk_new_20200303", gtk)
                .put("format", "json")
                .put("inCharset", "utf-8")
                .put("outCharset", "utf-8")
                .put("notice", 0)
                .put("need_new_code", 1)

            val searchParam = JSONObject()
                .put(
                    "searchid",
                    "${System.currentTimeMillis()}${(1000..9999).random()}${(0..999).random()}"
                )
                .put("query", keyword.trim())
                .put("search_type", 0)
                .put("num_per_page", 20)
                .put("page_num", page.coerceAtLeast(1))
                .put("highlight", false)
                .put("grp", true)
                .put("selectors", JSONObject())
                .put("vec_selectors", JSONArray())

            val requestData = JSONObject()
                .put("comm", comm)
                .put(
                    "req_0",
                    JSONObject()
                        .put("module", "music.search.SearchCgiService")
                        .put("method", "DoSearchForQQMusicDesktop")
                        .put("param", searchParam)
                ).toString()

            val httpRequest = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                )
                .header("Referer", "https://y.qq.com/")
                .header("Accept", "application/json, text/plain, */*")
                .apply {
                    if (auth != null && auth.cookie.isNotBlank()) {
                        header("Cookie", auth.cookie)
                    }
                }
                .post(
                    requestData.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
                .build()

            val responseJson = executeRequest(httpRequest) as String
            logDetailResponse(label = "musicu.search", responseJson = responseJson)
            val request = JSONObject(responseJson).optJSONObject("req_0")
                ?: throw IOException("QQ 音乐搜索响应缺少 req_0")
            val code = request.optInt("code", -1)
            if (code != 0) {
                // 复用取流链路的统一码值分类，让上层拿到 kind / requiresRelogin 语义，
                // 而不是只能显示一句裸 IOException 字符串。
                throw QQMusicApiException.fromCode(code, request.optString("msg"))
            }
            val list = request.optJSONObject("data")
                ?.optJSONObject("body")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?: JSONArray()

            buildList {
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    val mid = item.optString("mid").ifBlank { item.optString("songmid") }
                    if (mid.isBlank()) continue
                    val singers = item.optJSONArray("singer")?.let { array ->
                        buildList {
                            for (i in 0 until array.length()) {
                                array.optJSONObject(i)?.optString("name")
                                    ?.takeIf(String::isNotBlank)
                                    ?.let(::add)
                            }
                        }.joinToString(" / ")
                    }.orEmpty()
                    val album = item.optJSONObject("album")
                    // media_mid 是"高音质文件名"的组成部分。搜索结果此前不带它，
                    // 导致点开播放时要再查一次详情，且拿不到 320k/无损的文件名。
                    val mediaId = sequenceOf(
                        item.optJSONObject("file")?.optString("media_mid"),
                        item.optString("media_mid"),
                    )
                        .map { it.orEmpty().trim() }
                        .firstOrNull(String::isNotBlank)
                    add(
                        SongSearchInfo(
                            id = mid,
                            songName = item.optString("name").ifBlank { item.optString("title") },
                            singer = singers,
                            duration = formatDuration(item.optLong("interval", 0L)),
                            source = MusicPlatform.QQ_MUSIC,
                            albumName = album?.optString("name")?.takeIf(String::isNotBlank),
                            coverUrl = qqMusicAlbumCoverUrl(album?.optString("mid")),
                            mediaId = mediaId
                        )
                    )
                }
            }
        }
    }

    override suspend fun getSongInfo(id: String): SongDetails = getNativeSongInfo(id)

    suspend fun getSongMetadata(id: String): QQMusicSongMetadata = withContext(Dispatchers.IO) {
        val song = fetchSongData(id)
        QQMusicSongMetadata(
            details = song.toSongDetails(lyric = null, translatedLyric = null),
            durationMs = song.interval.takeIf { it > 0L }?.times(1000L) ?: 0L
        )
    }

    override suspend fun getNativeSongInfo(id: String): SongDetails {
        return withContext(Dispatchers.IO) {
            val songData = fetchSongData(id)
            val (lyric, translatedLyric) = getNativeLyrics(id)
            songData.toSongDetails(
                lyric = lyric,
                translatedLyric = translatedLyric
            )
        }
    }

    private suspend fun fetchSongData(id: String): QQMusicTrackInfo {
        val detailRequestData = JSONObject().put(
            "songinfo", JSONObject()
                .put("method", "get_song_detail_yqq")
                .put("module", "music.pf_song_detail_svr")
                .put("param", JSONObject().put("song_mid", id))
        ).toString()

        val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("data", detailRequestData)
            .build()

        val responseJson = executeRequest(url.toString()) as String
        logDetailResponse(label = url.encodedPath, responseJson = responseJson)

        val songInfoJson = JSONObject(responseJson).optJSONObject("songinfo")?.toString()
            ?: throw IOException("响应中找不到 songinfo 字段")

        return json.decodeFromString<QQMusicDetailResponse>(songInfoJson).data?.trackInfo
            ?: throw IOException("找不到ID为 $id 的歌曲详情")
    }

    private fun QQMusicTrackInfo.toSongDetails(
        lyric: String?,
        translatedLyric: String?
    ): SongDetails {
        return SongDetails(
            id = mid,
            songName = name,
            singer = singer.joinToString(" / ") { it.name },
            album = album.name,
            coverUrl = "https://y.qq.com/music/photo_new/T002R800x800M000${album.mid}.jpg",
            lyric = lyric,
            translatedLyric = translatedLyric
        )
    }

    private fun logDetailResponse(label: String, responseJson: String) {
        val preview = responseJson
            .replace(Regex("\\s+"), " ")
            .take(DEBUG_JSON_PREVIEW_MAX_CHARS)
        if (debugLogging) {
            NPLogger.d(TAG, "获取歌曲详情响应: label=$label, length=${responseJson.length}, preview=$preview")
            return
        }
        NPLogger.d(TAG, "获取歌曲详情响应: labelHash=${label.hashCode()}, length=${responseJson.length}")
    }

    suspend fun getNativeLyrics(songMid: String): Pair<String?, String?> {
        return try {
            val lyricRequestData = JSONObject().put(
                "req", JSONObject()
                    .put("method", "GetPlayLyricInfo")
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put(
                        "param", JSONObject()
                            .put("songMID", songMid)
                            // 不显式点名要翻译, 接口只回一个空的 trans
                            .put("trans", 1)
                            .put("qrc", 0)
                            .put("crypt", 0)
                    )
            ).toString()

            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("data", lyricRequestData)
                .build()

            val request = Request.Builder().url(url)
                .header("Referer", "https://y.qq.com")
                .build()

            val responseJson = executeRequest(request) as String
            val envelope = json.decodeFromString<QQMusicLyricContainer>(responseJson).req
            if (envelope == null || envelope.code != 0) {
                if (envelope != null) {
                    NPLogger.w(
                        TAG,
                        "QQ lyric request rejected: songMid=$songMid code=${envelope.code}"
                    )
                }
                Pair(null, null)
            } else {
                val lyricResponse = envelope.data

                val lyric = decodeQQMusicLyricPayload(lyricResponse?.lyric)

                val translatedLyric = stripUntranslatedPlaceholderLines(
                    decodeQQMusicLyricPayload(lyricResponse?.trans)
                )

                Pair(lyric, translatedLyric)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e(TAG, "获取QQ音乐歌词失败", error)
            Pair(null, null)
        }
    }

    @Throws(IOException::class)
    private suspend fun executeRequest(url: String, asBytes: Boolean = false): Any {
        val request = Request.Builder().url(url).build()
        return executeRequest(request, asBytes)
    }

    @Throws(IOException::class)
    private suspend fun executeRequest(request: Request, asBytes: Boolean = false): Any {
        return client.newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (asBytes) body.bytes() else body.string()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}
