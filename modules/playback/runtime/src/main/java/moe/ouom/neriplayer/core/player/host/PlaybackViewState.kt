package moe.ouom.neriplayer.core.player.host

import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueDisplayState
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist

/**
 * UI 读取播放状态的**唯一**接缝。
 *
 * 背景：UI 层此前有 40+ 个文件直接 `import moe.ouom.neriplayer.core.player.PlayerManager`
 * 并 collect 它的 StateFlow（78 处），这等于让界面依赖一个 1977 行的进程级单例。
 * 后果不是"不好看"，而是：
 *  - UI 无法在 JVM 单测里被喂状态（必须拉起整个 PlayerManager）；
 *  - 任何"把状态搬出单例"的重构都要同时改几十个界面文件，因此没人敢动。
 *
 * 这里只做一件事：**把"读"抽出来**。写操作（play/pause/seek/queue 编辑）暂时仍走
 * `PlayerManager`——那是下一步（注入化）的事，一次只动一层。
 *
 * 约束（刻意设计的）：
 *  - 只暴露 StateFlow，不暴露 `MutableStateFlow`：接缝的实现方才有写权限；
 *  - 只放"UI 真实消费"的字段，避免变成 `PlayerManager` 的镜像副本；
 *  - 实现方必须是**纯委托**（等价于原来的 collect），零行为变化。
 *
 * 迁移规则：新增 UI 代码只允许 `import moe.ouom.neriplayer.core.player.host.playbackViewState`，
 * 直接 import `PlayerManager` 会被 `tools_pub/quality/module_boundaries.py` 的棘轮拦住。
 */
interface PlaybackViewState {
    /** 当前曲目；null 表示空队列。 */
    val currentSong: StateFlow<SongItem?>

    /** 真实播放状态（跟随播放器，淡入淡出期间可能为 false）。 */
    val isPlaying: StateFlow<Boolean>

    /** 播放/暂停按钮的视觉状态（跟随最近一次用户意图，避免图标滞后）。 */
    val controlPlaying: StateFlow<Boolean>

    /** 播放进度（毫秒）。 */
    val position: StateFlow<Long>

    /** 当前曲目总时长（毫秒）；未知为 0。 */
    val duration: StateFlow<Long>

    /** 当前队列快照。 */
    val queue: StateFlow<List<SongItem>>

    /**
     * 队列展示快照的修订号；一旦变化就说明队列内容变了。
     *
     * UI 用它决定"要不要重算排序后的展示列表"，[queue] 单独用不足以表达
     * "同长度但顺序变了"这种变更。
     */
    val queueDisplayRevision: StateFlow<Long>

    val shuffle: StateFlow<Boolean>

    /** 循环模式，取值沿用 `androidx.media3.common.Player.REPEAT_MODE_*`。 */
    val repeatMode: StateFlow<Int>

    /** 本地歌单快照（收藏/加歌弹窗用）。 */
    val playlists: StateFlow<List<LocalPlaylist>>

    /** 本地歌单是否已完成首次加载。 */
    val playlistsReady: StateFlow<Boolean>

    /** USB 独占播放正在准备（此时 UI 应禁用播放控制）。 */
    val usbExclusivePreparing: StateFlow<Boolean>

    /**
     * 取一份队列展示快照。
     *
     * 是"读"而不是 StateFlow：它按需计算，UI 只在 [queueDisplayRevision] 变化后才调用。
     */
    fun queueDisplaySnapshot(): PlayerQueueDisplayState
}

/**
 * 全局读接缝入口。
 *
 * 刻意做成"顶层属性 + object 适配器"而不是构造注入：
 * 这一步的目标是**切断 import**，不是引入 DI。等 `PlayerManager` 的注入化完成
 * （P2）后，这个属性会退化成 `AppContainer` 里的一个实例。
 */
val playbackViewState: PlaybackViewState get() = PlayerManagerViewStateAdapter

/**
 * [PlaybackViewState] 的零行为实现：每个字段都直接返回 `PlayerManager` 现有的 StateFlow 实例。
 *
 * **不允许在这里做 `map`/`combine`/`stateIn`**：任何转换都会引入新的 collect 时机与
 * 调度器语义，破坏"零行为变化"这个前提。要派生状态，请另建 `xxxState` 并在 UI 侧组合。
 */
internal object PlayerManagerViewStateAdapter : PlaybackViewState {
    override val currentSong: StateFlow<SongItem?>
        get() = PlayerManager.currentSongFlow

    override val isPlaying: StateFlow<Boolean>
        get() = PlayerManager.isPlayingFlow

    override val controlPlaying: StateFlow<Boolean>
        get() = PlayerManager.playbackControlPlayingFlow

    override val position: StateFlow<Long>
        get() = PlayerManager.playbackPositionFlow

    override val duration: StateFlow<Long>
        get() = PlayerManager.playbackDurationFlow

    override val queue: StateFlow<List<SongItem>>
        get() = PlayerManager.currentQueueFlow

    override val queueDisplayRevision: StateFlow<Long>
        get() = PlayerManager.currentQueueDisplayRevisionFlow

    override val shuffle: StateFlow<Boolean>
        get() = PlayerManager.shuffleModeFlow

    override val repeatMode: StateFlow<Int>
        get() = PlayerManager.repeatModeFlow

    override val playlists: StateFlow<List<LocalPlaylist>>
        get() = PlayerManager.playlistsFlow

    override val playlistsReady: StateFlow<Boolean>
        get() = PlayerManager.localPlaylistsReadyFlow

    override val usbExclusivePreparing: StateFlow<Boolean>
        get() = PlayerManager.usbExclusivePlaybackPreparingFlow

    override fun queueDisplaySnapshot(): PlayerQueueDisplayState =
        PlayerManager.currentQueueDisplaySnapshot()
}
