package moe.ouom.neriplayer.data.model.settings.download

data class DownloadAudioQualitySelection(
    val neteaseQuality: String,
    val youtubeQuality: String,
    val biliQuality: String,
    /**
     * QQ 音乐下载档位（本地 fork 新增）。
     *
     * 带默认值是为了兼容既有构造点与旧配置；新增音源时沿用同样的字段 + 默认值模式，
     * 即可在不破坏旧数据的前提下扩展。
     */
    val qqQuality: String = "lossless"
) {
    companion object
}
