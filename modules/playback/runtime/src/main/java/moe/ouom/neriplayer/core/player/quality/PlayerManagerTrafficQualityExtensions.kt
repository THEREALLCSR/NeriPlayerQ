package moe.ouom.neriplayer.core.player.quality

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataBiliAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.playback.normalizeMobileDataYouTubeAudioQuality
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentTrafficNetworkType

internal fun PlayerManager.effectiveNeteaseQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.NETEASE,
        defaultQuality = preferredQuality
    )
}

internal fun PlayerManager.effectiveYouTubeQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.YOUTUBE_MUSIC,
        defaultQuality = youtubePreferredQuality
    )
}

internal fun PlayerManager.effectiveBiliQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.BILIBILI,
        defaultQuality = biliPreferredQuality
    )
}

/**
 * QQ 音乐当前生效档位（本地 fork 新增）。
 *
 * 参与两件事：
 *  1. 播放解析时作为请求档位；
 *  2. 播放缓存 key 的一部分 —— 缺了它，切档位会命中旧缓存，界面音质永远不变。
 */
internal fun PlayerManager.effectiveQqQuality(): String {
    return resolveTrafficAwareQuality(
        source = PlaybackAudioSource.QQ_MUSIC,
        defaultQuality = qqPreferredQuality
    )
}

private fun PlayerManager.resolveTrafficAwareQuality(
    source: PlaybackAudioSource,
    defaultQuality: String
): String {
    if (!isApplicationInitialized()) {
        return defaultQuality
    }
    val networkType = application.currentTrafficNetworkType()
    if (networkType == TrafficNetworkType.WIFI) {
        return defaultQuality
    }
    if (mobileDataFollowDefaultAudioQuality) {
        return defaultQuality
    }

    return when (source) {
        PlaybackAudioSource.NETEASE ->
            normalizeMobileDataNeteaseAudioQuality(mobileDataNeteaseAudioQuality)
        PlaybackAudioSource.YOUTUBE_MUSIC ->
            normalizeMobileDataYouTubeAudioQuality(mobileDataYouTubeAudioQuality)
        PlaybackAudioSource.BILIBILI ->
            normalizeMobileDataBiliAudioQuality(mobileDataBiliAudioQuality)
        else -> defaultQuality
    }
}
