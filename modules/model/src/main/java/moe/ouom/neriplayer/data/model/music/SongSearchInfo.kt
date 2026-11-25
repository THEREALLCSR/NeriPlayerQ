package moe.ouom.neriplayer.data.model.music

import kotlinx.serialization.Serializable

@Serializable
data class SongSearchInfo(
    val id: String,
    val songName: String,
    val singer: String,
    val duration: String,
    val source: MusicPlatform,
    val albumName: String?,
    val coverUrl: String?,
    /**
     * 平台的"第二 ID"：QQ 音乐放 media_mid（高音质文件名要用），
     * 其它平台暂不使用。默认 null，保持既有构造点无需改动。
     */
    val mediaId: String? = null
)
