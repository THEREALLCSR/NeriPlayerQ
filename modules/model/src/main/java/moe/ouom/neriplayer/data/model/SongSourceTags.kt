package moe.ouom.neriplayer.data.model

// 历史歌曲把来源编码在 album 中，统一标签以保持播放和数据解析兼容
object SongSourceTags {
    const val BILIBILI = "Bilibili"
    const val NETEASE = "Netease"
    // QQ 音乐的来源标记：旧数据把来源编码在 album 前缀里
    const val QQ_MUSIC = "QQMusic"
}
