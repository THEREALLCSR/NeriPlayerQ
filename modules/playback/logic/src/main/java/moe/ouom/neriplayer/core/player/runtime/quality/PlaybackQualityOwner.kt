package moe.ouom.neriplayer.core.player.runtime.quality

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PreferredQualityKeys
import moe.ouom.neriplayer.data.model.playback.forSource

interface PlaybackQualityPort {
    fun currentAudioSource(): PlaybackAudioSource?
    suspend fun persistPreferredQuality(source: PlaybackAudioSource, key: String)
    suspend fun refreshCurrentSong(source: PlaybackAudioSource, reason: String)
}

class PlaybackQualityOwner(
    private var scope: CoroutineScope,
    private val port: PlaybackQualityPort
) {
    private val mutablePreferredKeys = MutableStateFlow(PreferredQualityKeys())
    val preferredKeys: StateFlow<PreferredQualityKeys> = mutablePreferredKeys
    private val refreshJobs = mutableMapOf<PlaybackAudioSource, Job>()

    val neteasePreferredQuality: String get() = mutablePreferredKeys.value.netease
    val youtubePreferredQuality: String get() = mutablePreferredKeys.value.youtube
    val biliPreferredQuality: String get() = mutablePreferredKeys.value.bili
    /** QQ 音乐当前选择的档位（本地 fork 新增）。 */
    val qqPreferredQuality: String get() = mutablePreferredKeys.value.qq

    fun rebindScope(scope: CoroutineScope) {
        refreshJobs.values.forEach { it.cancel() }
        refreshJobs.clear()
        this.scope = scope
    }

    fun setPreferredQuality(source: PlaybackAudioSource, key: String) {
        val current = mutablePreferredKeys.value
        mutablePreferredKeys.value = when (source) {
            PlaybackAudioSource.NETEASE -> current.copy(netease = key)
            PlaybackAudioSource.YOUTUBE_MUSIC -> current.copy(youtube = key)
            PlaybackAudioSource.BILIBILI -> current.copy(bili = key)
            // QQ 音乐的音质偏好单独存一份，不影响网易云
            PlaybackAudioSource.QQ_MUSIC -> current.copy(qq = key)
            PlaybackAudioSource.LOCAL -> current
        }
    }

    fun changeCurrentPlaybackQuality(optionKey: String) {
        val key = optionKey.trim().lowercase()
        if (key.isBlank()) return
        val source = port.currentAudioSource() ?: return
        if (key == mutablePreferredKeys.value.forSource(source)) return
        // 1) 先更新内存里的偏好：紧接着的重新解析要立刻用上新档位。
        //    原实现只写了持久化，内存值不变 ⇒ 解析器仍拿旧档位（QQ 表现为"切了没反应"）。
        setPreferredQuality(source, key)
        // 2) 持久化 + 让当前歌曲按新档位重新解析一次。
        scope.launch {
            port.persistPreferredQuality(source, key)
            port.refreshCurrentSong(source, "quality:$key")
        }
    }

    fun scheduleRefresh(source: PlaybackAudioSource, reason: String) {
        if (source == PlaybackAudioSource.LOCAL) return
        refreshJobs.remove(source)?.cancel()
        refreshJobs[source] = scope.launch { port.refreshCurrentSong(source, reason) }
    }
}
