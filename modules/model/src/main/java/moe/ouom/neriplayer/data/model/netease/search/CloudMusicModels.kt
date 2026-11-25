package moe.ouom.neriplayer.data.model.netease.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// result 必须有默认值：网易云在风控/未登录等情况下返回 {"code":-460,"msg":"..."} 这类
// 不含 result 的错误体，若声明成"可空但必填"，kotlinx 会抛 MissingFieldException，
// 让"搜不到结果"变成异常，调用方只能靠 catch 兜底。
@Serializable data class CloudMusicSearchResponse(val result: CloudMusicSearchResult? = null)
@Serializable data class CloudMusicSearchResult(val songs: List<CloudMusicSongSummary>? = null)
@Serializable data class CloudMusicSongSummary(
    val id: Long,
    val name: String,
    @SerialName("dt") val duration: Long,
    @SerialName("ar") val artists: List<CloudMusicArtist>,
    @SerialName("al") val album: CloudMusicAlbum
)

@Serializable data class CloudMusicSongDetail(
    val name: String,
    @SerialName("artists") val artists: List<CloudMusicArtist>,
    @SerialName("album") val album: CloudMusicAlbum,
)

@Serializable data class CloudMusicSongDetailResponse(val songs: List<CloudMusicSongDetail>)
@Serializable data class CloudMusicArtist(val name: String)
@Serializable data class CloudMusicAlbum(val name: String, val picUrl: String?)
@Serializable
data class CloudMusicLyricResponse(
    val lrc: CloudMusicLrc?,
    val tlyric: CloudMusicLrc? = null,
    val yrc: CloudMusicLrc? = null,
    val ytlrc: CloudMusicLrc? = null
)

@Serializable
data class CloudMusicLrc(val lyric: String?)
