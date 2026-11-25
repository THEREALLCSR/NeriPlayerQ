package moe.ouom.neriplayer.core.download.host

import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.bilibili.playback.BiliPlaybackRepository
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackRepository

interface DownloadSourceServices {
    val neteaseClient: NeteaseClient
    val biliClient: BiliClient
    val biliPlaybackRepository: BiliPlaybackRepository
    val youtubeMusicPlaybackRepository: YouTubeMusicPlaybackRepository
    val youtubeMusicDownloadPlaybackRepository: YouTubeMusicPlaybackRepository
    // QQ 音乐下载解析（本地 fork 新增）
    val qqMusicPlaybackRepository: QQMusicPlaybackRepository
}
