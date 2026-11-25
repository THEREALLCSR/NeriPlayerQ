package moe.ouom.neriplayer.core.player.runtime.prefetch

import moe.ouom.neriplayer.data.model.playback.SongUrlResult

/**
 * 预取 URL 的存活时间。
 *
 * 两个约束取小：
 *  1. 按曲长估算（播完还留 30s 余量）；
 *  2. 直链自身的过期时间（[expiresAtEpochMs]，目前只有 QQ 的 vkey 会填）。
 *
 * 改造前只有第 1 条：QQ 的分钟级直链被缓存到"曲长 + 30s"，
 * 暂停久一点再播就会拿到过期 URL（403 / 无声）。
 */
fun resolveGenericUrlPrefetchTtlMs(
    currentTrackDurationMs: Long,
    defaultTtlMs: Long = GENERIC_URL_PREFETCH_TTL_MS,
    maxTtlMs: Long = GENERIC_URL_PREFETCH_MAX_TTL_MS,
    expiresAtEpochMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Long {
    val durationBasedTtl = currentTrackDurationMs
        .takeIf { it > 0L }
        ?.plus(30_000L)
    val base = (durationBasedTtl ?: defaultTtlMs).coerceIn(1L, maxTtlMs)
    val hardDeadline = expiresAtEpochMs
        ?.let { it - nowMs - URL_EXPIRY_SAFETY_MS }
        ?.coerceAtLeast(1L)
    return if (hardDeadline != null && hardDeadline < base) hardDeadline else base
}

/** 直链过期前预留的余量：留 1 分钟给"解析完 → 真正开始播"的链路。 */
internal const val URL_EXPIRY_SAFETY_MS: Long = 60_000L

const val GENERIC_MEDIA_PREFETCH_BYTES = 1_536L * 1024L
private const val GENERIC_MEDIA_PREFETCH_MIN_BYTES = 256L * 1024L

fun resolveGenericMediaPrefetchBytes(expectedContentLength: Long?): Long {
    return expectedContentLength
        ?.takeIf { it > 0L }
        ?.coerceAtMost(GENERIC_MEDIA_PREFETCH_BYTES)
        ?.coerceAtLeast(GENERIC_MEDIA_PREFETCH_MIN_BYTES)
        ?: GENERIC_MEDIA_PREFETCH_BYTES
}

fun resolveGenericMediaPrefetchCacheKey(
    genericCacheKey: String,
    result: SongUrlResult.Success
): String {
    return result.cacheKeyOverride
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: genericCacheKey
}
