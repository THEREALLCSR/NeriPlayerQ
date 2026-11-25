@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.network.holdPlaybackForNetworkIfOffline
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.runtime.source.retrySongUrlResolution

import moe.ouom.neriplayer.core.player.runtime.source.SONG_URL_RESOLUTION_RETRY_COUNT

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliSponsorBlockTarget
import moe.ouom.neriplayer.platform.bilibili.playback.resolver.resolveBiliSong
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution as LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.core.player.lifecycle.updateAudioOffloadPreferences
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlaybackQualityOption
import moe.ouom.neriplayer.platform.qqmusic.playback.QQMusicPlaybackResult
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.core.player.policy.audio.mergeLocalPlaybackAudioInfoWithRemoteQuality
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshDeferredCompletion
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshRequestHandle
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshRequestSemantics
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResultSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResultKind
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackStartPlan
import moe.ouom.neriplayer.core.player.runtime.refresh.resolveRefreshApplyAction
import moe.ouom.neriplayer.core.player.runtime.refresh.resolveRefreshedMediaStartPosition
import moe.ouom.neriplayer.core.player.runtime.refresh.shouldApplyRefreshResult
import moe.ouom.neriplayer.core.player.runtime.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.core.player.playback.advanceAfterPlaybackFailure
import moe.ouom.neriplayer.core.player.playback.BiliSponsorBlockPlaybackController
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.core.player.playback.preparePlayerForManagedStart
import moe.ouom.neriplayer.core.player.playback.startProgressUpdates
import moe.ouom.neriplayer.core.player.prefetch.consumeGenericUrlPrefetch
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveQqQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.quality.effectiveYouTubeQuality
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseAutoBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.watchdog.configureActivePlaybackCandidates
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.core.player.watchdog.resetPlaybackProgressAdvanceBaseline
import moe.ouom.neriplayer.core.player.watchdog.schedulePlaybackStartupWatchdog
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.logging.playbackLogReference
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.ltw.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
import moe.ouom.neriplayer.core.player.ltw.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.data.ltw.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.data.ltw.playback.shouldPreferListenTogetherSourceBeforeNeteaseFallback
import moe.ouom.neriplayer.data.ltw.playback.shouldSuppressListenTogetherResolverError
import moe.ouom.neriplayer.common.coroutines.runCatchingNonCancellation
import java.io.File
import kotlin.time.Duration.Companion.milliseconds


internal suspend fun PlayerManager.getNeteaseSongUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowLocalFallback: Boolean = true,
    allowPreviewFallback: Boolean = true,
    allowAutoBiliFallback: Boolean = true
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val effectiveQuality = effectiveNeteaseQuality()
        val qualityCandidates = buildNeteaseQualityCandidates(effectiveQuality)
        var previewFallback: SongUrlResult.Success? = null
        var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null
        var requiresLogin = false

        for ((index, quality) in qualityCandidates.withIndex()) {
            val resp = neteaseClient.getSongDownloadUrl(
                song.id,
                level = quality
            )
            NPLogger.d("NERI-PlayerManager", "id=${song.id}, level=$quality, resp=$resp")

            when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                    requiresLogin = true
                    if (shouldRetryNeteaseWithLowerQualityAfterLogin(
                            qualityIndex = index,
                            lastQualityIndex = qualityCandidates.lastIndex
                        )
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质需要登录，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                    break
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                    val success = buildNeteaseSuccessResult(
                        parsed = parsed,
                        resolvedQualityKey = quality,
                        fallbackDurationMs = song.durationMs,
                        getLocalizedString = { getLocalizedString(it) }
                    ).let { result ->
                        if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                            result.copy(
                                cacheKeyOverride = buildNeteasePreviewCacheKey(
                                    songId = song.id,
                                    preferredQuality = quality
                                )
                            )
                        } else {
                            result
                        }
                    }
                    if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                        if (quality != effectiveQuality) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质不可用，已自动降级: id=${song.id}, preferred=$effectiveQuality, resolved=$quality"
                            )
                        }
                        return@withContext success
                    }

                    previewFallback = success
                    if (index < qualityCandidates.lastIndex) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质仅返回试听片段，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                    lastFailureReason = parsed.reason
                    if (index < qualityCandidates.lastIndex &&
                        shouldRetryNeteaseWithLowerQuality(parsed.reason)
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质不可播放，继续尝试更低音质: id=${song.id}, level=$quality, reason=${parsed.reason}"
                        )
                        continue
                    }
                    break
                }
            }
        }

        if (previewFallback != null ||
            lastFailureReason == NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION
        ) {
            if (allowLocalFallback) {
                tryResolveNeteaseMatchedLocalSource(song)?.let {
                    return@withContext it
                }
            }
            if (allowAutoBiliFallback) {
                tryResolveNeteaseAutoBiliSource(song, sideEffects)?.let {
                    return@withContext it
                }
            }
        }

        if (allowPreviewFallback) {
            previewFallback?.let { return@withContext it }
        }

        if (requiresLogin) {
            return@withContext SongUrlResult.RequiresLogin
        }

        if (!suppressError) {
            val messageRes = when (lastFailureReason) {
                NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                    CoreCommonR.string.player_netease_no_permission_switch_platform
                NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                null -> CoreCommonR.string.error_no_play_url
            }
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
            }
        }
        SongUrlResult.Failure
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get url", e)
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

/**
 * QQ 音乐播放地址解析（本地 fork 新增）。
 *
 * 数据约定：
 *  - song.audioId   = songMid（QQ 歌曲的唯一 ID）
 *  - song.subAudioId = mediaMid（高音质文件名要用的 ID，可为空）
 *  - song.channelId = "qqmusic"（由 QQ 歌单页构造 SongItem 时写入）
 *
 * 为什么固定请求 "lossless"：QQ 的候选链会从无损逐档降到 320k/128k，
 * 传最高档等价于"能拿多高拿多高"，同时把实际档位写进 PlaybackAudioInfo，
 * 播放器界面上显示的是真实音质而不是请求音质。
 */
internal suspend fun PlayerManager.getQqMusicAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    val songMid = song.audioId?.takeIf(String::isNotBlank) ?: song.id.toString()
    // 用"用户当前选择的档位"而不是写死 lossless：
    // 切音质后 PlaybackQualityOwner 会更新 qqPreferredQuality 并触发本函数重新解析，
    // 这样切换才会真正生效（降级链仍在 repository 内部处理）。
    val requestedQuality = effectiveQqQuality().takeIf(String::isNotBlank) ?: "lossless"
    when (
        val result = qqMusicPlaybackRepository.resolve(
            songMid = songMid,
            mediaMid = song.subAudioId,
            qualityKey = requestedQuality
        )
    ) {
        is QQMusicPlaybackResult.Playable -> SongUrlResult.Success(
            url = result.url,
            // vkey 是分钟级直链：把过期时间交给预取缓存，避免 TTL 只按曲长估算
            expiresAtEpochMs = result.expiresAtEpochMs,
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.QQ_MUSIC,
                qualityKey = result.qualityKey,
                qualityLabel = when (result.qualityKey) {
                    "lossless" -> "无损"
                    "exhigh" -> "高品质"
                    else -> "标准"
                },
                qualityOptions = listOf(
                    PlaybackQualityOption("lossless", "无损"),
                    PlaybackQualityOption("exhigh", "高品质"),
                    PlaybackQualityOption("standard", "标准")
                ),
                codecLabel = result.format?.uppercase(),
                mimeType = if (result.format.equals("flac", ignoreCase = true)) {
                    "audio/flac"
                } else {
                    "audio/mpeg"
                },
                bitrateKbps = result.bitrateKbps
            )
        )

        QQMusicPlaybackResult.LoginRequired -> {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError("请先在设置 → 账号里登录 QQ 音乐"))
                }
            }
            SongUrlResult.Failure
        }

        // 登录态存在但已被服务端判定失效：必须引导"重新登录"，
        // 不能说成"没有版权/没有权益"——这两件事用户要做的事完全不同。
        QQMusicPlaybackResult.LoginExpired -> {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError("QQ 音乐登录已过期，请重新扫码登录"))
                }
            }
            SongUrlResult.Failure
        }

        // 网络层失败：不是"没这首歌"，也不是"没权益"。
        // 断网时上层会自动挂起播放并在网络恢复后续播（holdPlaybackForNetworkIfOffline），
        // 这里再弹一句 DNS 报错只会让用户以为播放坏了；只有"有网但连不上"
        // （代理、 captive portal、服务端不可达）才值得提示一次。
        is QQMusicPlaybackResult.Transport -> {
            if (!suppressError && isPlaybackNetworkUsable(application)) {
                sideEffects.emitError {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(CoreCommonR.string.player_playback_network_error)
                        )
                    )
                }
            }
            SongUrlResult.Failure
        }

        is QQMusicPlaybackResult.Unavailable -> {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(result.reason))
                }
            }
            SongUrlResult.Failure
        }
    }
}

internal suspend fun PlayerManager.getBiliAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    playbackRequestTokenOverride: Long? = null
): SongUrlResult = withContext(Dispatchers.IO) {
    try {
        val resolved = resolveBiliSong(song, biliClient)
        if (resolved == null || resolved.cid == 0L) {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(
                        PlayerEvent.ShowError(
                            getLocalizedString(CoreCommonR.string.player_playback_video_info_unavailable)
                        )
                    )
                }
            }
            return@withContext SongUrlResult.Failure
        }

        if (!isListenTogetherActive() && _currentSongFlow.value?.sameIdentityAs(song) == true) {
            val requestToken = playbackRequestTokenOverride ?: playbackRequestToken
            BiliSponsorBlockPlaybackController.onBiliTrackResolved(
                song = song,
                target = BiliSponsorBlockTarget(
                    bvid = resolved.videoInfo.bvid,
                    cid = resolved.cid,
                    durationMs = resolved.pageInfo
                        ?.durationSec
                        ?.toLong()
                        ?.times(1_000L)
                        ?.takeIf { it > 0L }
                        ?: song.durationMs
                ),
                requestToken = requestToken,
                scope = ioScope
            )
            BiliVideoSkipPlaybackController.onBiliTrackResolved(
                song = song,
                target = BiliVideoSkipTarget(
                    bvid = resolved.videoInfo.bvid,
                    cid = resolved.cid
                ),
                requestToken = requestToken
            )
        }

        val (availableStreams, audioStream) = biliRepo.getAudioWithDecision(
            resolved.videoInfo.bvid,
            resolved.cid,
            preferredKeyOverride = effectiveBiliQuality()
        )

        if (audioStream?.url != null) {
            NPLogger.d("NERI-PlayerManager-BiliAudioUrl", audioStream.url)
            SongUrlResult.Success(
                url = audioStream.url,
                candidateUrls = audioStream.candidateUrls,
                mimeType = audioStream.mimeType,
                expectedContentLength = null,
                audioInfo = buildBiliPlaybackAudioInfo(audioStream, availableStreams) {
                    getLocalizedString(it)
                },
                representationIdentity = buildBiliRepresentationIdentity(audioStream)
            )
        } else {
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
                }
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e("NERI-PlayerManager", "Failed to get Bili play url", e)
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

internal suspend fun PlayerManager.getYouTubeMusicAudioUrl(
    song: SongItem,
    suppressError: Boolean = false,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult = withContext(Dispatchers.IO) {
    val videoId = extractYouTubeMusicVideoId(song.mediaUri)
    if (videoId.isNullOrBlank()) {
        if (!suppressError) {
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
            }
        }
        return@withContext SongUrlResult.Failure
    }

    val resolveStartedAtMs = System.currentTimeMillis()
    try {
        val preferredQuality = youtubeRecoveryStrategy?.preferredQualityOverride
            ?: effectiveYouTubeQuality()
        val requireDirect = youtubeRecoveryStrategy?.requireDirect ?: false
        // 首播保留用户选择; 出错恢复时才切到更稳的 m4a 直链
        val preferM4a = youtubeRecoveryStrategy?.preferM4a ?: false
        val resolvedPlayableAudio = youtubeMusicPlaybackRepository.getBestPlayableAudio(
            videoId = videoId,
            preferredQualityOverride = preferredQuality,
            forceRefresh = forceRefresh,
            requireDirect = requireDirect,
            preferM4a = preferM4a,
            shareInFlight = youtubeRecoveryStrategy == null,
            allowUnverifiedDirectFallback =
                youtubeRecoveryStrategy?.allowUnverifiedDirectFallback ?: true
        )?.takeIf { it.url.isNotBlank() }
        if (resolvedPlayableAudio != null) {
            sideEffects.updateDuration {
                maybeUpdateSongDuration(song, resolvedPlayableAudio.durationMs)
            }
            NPLogger.d(
                "NERI-PlayerManager",
                "Resolved YouTube Music stream: videoId=$videoId, quality=$preferredQuality, recovery=${youtubeRecoveryStrategy != null}, preferM4a=$preferM4a, type=${resolvedPlayableAudio.streamType}, mime=${resolvedPlayableAudio.mimeType}, contentLength=${resolvedPlayableAudio.contentLength}, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            SongUrlResult.Success(
                url = resolvedPlayableAudio.url,
                durationMs = resolvedPlayableAudio.durationMs.takeIf { it > 0L },
                mimeType = resolvedPlayableAudio.mimeType,
                expectedContentLength = resolvedPlayableAudio.contentLength,
                audioInfo = buildYouTubePlaybackAudioInfo(resolvedPlayableAudio) {
                    getLocalizedString(it)
                },
                representationIdentity = buildYouTubeRepresentationIdentity(resolvedPlayableAudio),
                cacheKeyOverride = youtubeRecoveryStrategy?.let { strategy ->
                    computeYouTubeCacheKey(
                        videoId = videoId,
                        preferredQuality = strategy.preferredQualityOverride,
                        preferM4a = strategy.preferM4a
                    )
                }
            )
        } else {
            NPLogger.w(
                "NERI-PlayerManager",
                "Resolve YouTube Music stream returned empty: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}"
            )
            if (!suppressError) {
                sideEffects.emitError {
                    postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.error_no_play_url)))
                }
            }
            SongUrlResult.Failure
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        NPLogger.e(
            "NERI-PlayerManager",
            "Failed to get YouTube Music play url: videoId=$videoId, elapsedMs=${System.currentTimeMillis() - resolveStartedAtMs}",
            e
        )
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(
                            CoreCommonR.string.player_playback_url_error_detail,
                            e.message.orEmpty()
                        )
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}
