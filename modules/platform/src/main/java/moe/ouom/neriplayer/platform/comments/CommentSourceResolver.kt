package moe.ouom.neriplayer.platform.comments

import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliBvidOrNull
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.biliCidOrNull
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicSource
import moe.ouom.neriplayer.data.model.music.MusicSources

internal const val BILIBILI_CHANNEL_ID = "bilibili"
internal const val NETEASE_CHANNEL_ID = "netease"
internal const val QQ_MUSIC_CHANNEL_ID = "qqmusic"

/**
 * 根据歌曲的「逻辑音源」解析评论来源。
 *
 * 关键规则: 平台只由 SongItem 的原始平台信息决定 ——
 * 显式 [SongItem.channelId] 优先, 其次才是 [SongItem.album] 的来源标记;
 * **绝不**由最终播放地址 (mediaUri / streamUrl) 或播放回退结果推断。
 *
 * 因此网易云歌曲即使音频回退到 Bilibili, 仍然返回网易云评论来源 (任务书 §49.2 的规范用例)。
 *
 * @return 平台不支持评论时返回 null (本地歌曲 / YouTube Music / 未知来源)
 */
fun resolveCommentSource(song: SongItem?): CommentSource? {
    if (song == null) return null

    // 音源判定统一走 MusicSources；这里只把"逻辑音源"翻译成评论平台。
    val platform = when (MusicSources.sourceOf(song)) {
        MusicSource.BILIBILI -> CommentPlatform.BILIBILI
        MusicSource.CLOUD_MUSIC -> CommentPlatform.NETEASE
        MusicSource.QQ_MUSIC -> CommentPlatform.QQ_MUSIC
        else -> null
    } ?: return null

    // QQ 音乐：评论区需要数字 id，这里拿不到（audioId 是 songmid），
    // 先把 songmid 放进 secondaryId，由 QQMusicCommentRepository 现查数字 id。
    if (platform == CommentPlatform.QQ_MUSIC) {
        val songMid = song.audioId?.takeIf { it.isNotBlank() } ?: return null
        return CommentSource(
            platform = CommentPlatform.QQ_MUSIC,
            resourceId = song.id.takeIf { it > 0L } ?: 1L,
            secondaryId = songMid
        )
    }

    val explicitResourceId = song.audioId?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    val resourceId = explicitResourceId
        ?: song.id.takeIf { it > 0L }
        ?: return null
    val isBili = platform == CommentPlatform.BILIBILI
    val normalizedSong = if (isBili) song.copy(channelId = BILIBILI_CHANNEL_ID) else song
    val bvid = normalizedSong.biliBvidOrNull().takeIf { isBili }
    val cid = normalizedSong.biliCidOrNull().takeIf { isBili }

    return CommentSource(
        platform = platform,
        resourceId = resourceId,
        secondaryId = bvid,
        subResourceId = cid,
        resourceTitle = song.name.takeIf { isBili && bvid == null && cid == null },
        hasExplicitResourceId = isBili && explicitResourceId != null
    )
}
