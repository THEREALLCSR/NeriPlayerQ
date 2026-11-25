package moe.ouom.neriplayer.platform.qqmusic.playback

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * File: moe.ouom.neriplayer.platform.qqmusic.playback/QQMusicPlaybackRepository
 */

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicApiException
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicClient
import moe.ouom.neriplayer.platform.qqmusic.network.QQMusicFailureKind
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐的播放地址解析结果。
 *
 * 与 NeriPlayer 现有的 `SongUrlResult` 相比，这里只保留"一个音源能给出什么"，
 * 不掺播放器状态；等接入 PlayerManager 时再由适配层转成 `PlaybackAudioInfo`。
 */
sealed interface QQMusicPlaybackResult {
    /**
     * 拿到可播放地址。
     *
     * @param qualityKey 实际拿到的档位（不是请求的档位）——
     *   例：请求无损但曲目只有 320k，这里会是 "exhigh"。
     */
    data class Playable(
        val url: String,
        val qualityKey: String,
        val bitrateKbps: Int?,
        val format: String?,
        /**
         * 直链的绝对过期时间（epoch ms）。
         *
         * vkey 是分钟级时效，而预取缓存原先只按"曲长 + 30s"估算 TTL，
         * 暂停很久后再播会拿到已过期的 URL（表现为 403/无声）。
         * 调用方用这个值给缓存键分桶，过期后自然不复用。
         */
        val expiresAtEpochMs: Long,
    ) : QQMusicPlaybackResult

    data object LoginRequired : QQMusicPlaybackResult

    /**
     * 登录态存在但服务端已判定失效（`code=1000/2001`）。
     *
     * 与 [LoginRequired] 分开，是因为这两种情况要引导用户做的事不同：
     * 没登录要"去登录"，已过期要"重新扫码"。改造前它们被折叠成
     * "可能需要该音质权益"，用户会以为是没版权。
     */
    data object LoginExpired : QQMusicPlaybackResult

    data class Unavailable(val reason: String) : QQMusicPlaybackResult

    /**
     * 网络层失败：DNS 解析不到、连接超时、连接被重置。
     *
     * 单独成型，是因为它和 [Unavailable] 要表达的事情完全不同：
     * `Unavailable` 是"这首歌现在给不了地址"（没版权、没权益、已下架），
     * 而这里是"此刻连不上"。混在一起的直接后果是：断网时播放页弹出
     * `Unable to resolve host "u.y.qq.com": No address associated with hostname`
     * ——一句用户看不懂、也无法行动的英文系统错误。
     *
     * 上层据此外理：断网时交给网络恢复机制挂起并自动续播，不弹错误；
     * 有网但连不上时才提示"网络连接失败"。
     */
    data class Transport(val reason: String) : QQMusicPlaybackResult
}

/**
 * QQ 音乐播放地址解析器（VKey）。
 *
 * 解析策略（与 MeloX 中已验证的实现一致）：
 *  1. 把目标档位翻译成 QQ 的文件码（M500 / M800 / F000）；
 *  2. 按"高 → 低"依次请求，第一个拿到 purl 的就是实际可用档位；
 *  3. 直链返回的是相对路径 + sip 域名，需要自己拼成完整 URL 并升级 https；
 *  4. QQ 的 vkey 直链有时效（分钟级），调用方必须"解析完立刻播放"，不能缓存 URL。
 *
 * 错误处理：
 *  - 服务端返回"已登录但失效"（1000/2001）时立即返回 [QQMusicPlaybackResult.LoginExpired]，
 *    **不再逐档重试**（重试只会重复失败并放大风控面）；
 *  - 某一档没有资源（`result != 0` / 空 purl）属于正常降级，继续下一档；
 *  - 网络失败记录原因后继续下一档，全部失败时按**最后一次失败的性质**返回
 *    [QQMusicPlaybackResult.Transport]（连不上）或 [QQMusicPlaybackResult.Unavailable]（没资源）。
 */
class QQMusicPlaybackRepository(
    httpClient: OkHttpClient,
    private val sessionProvider: () -> QQMusicSession,
    /** 允许测试注入 MockWebServer 地址。 */
    baseUrl: String = QQMusicClient.DEFAULT_BASE_URL,
    /** 服务端判定登录失效时通知持有方（用于把会话标记为过期、提示重新登录）。 */
    private val onSessionExpired: () -> Unit = {},
    /** 时间源；测试可注入以验证缓存过期。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = QQMusicClient(httpClient = httpClient, baseUrl = baseUrl)

    /**
     * songMid → media_mid。
     *
     * 同一首歌每次播放都补查一次详情是纯浪费：搜索/歌单已经能带 media_mid，
     * 但老数据、手动输入的歌没有。命中缓存后，重播只剩一次 vkey 请求。
     */
    private val mediaMidCache = LruCache<String, String>(CACHE_SIZE)

    /**
     * songMid → 上次成功的档位。
     *
     * 无损曲目少而账号权益有限，盲试三档意味着每首都可能 3 次请求。
     * 记住上次结果后，重播直接从该档开始（失败仍会继续降级）。
     */
    private val qualityCache = LruCache<String, QualityDecision>()

    private data class QualityDecision(val qualityKey: String, val decidedAtMs: Long)

    /**
     * 解析播放地址。
     *
     * @param qualityKey NeriPlayer 内部档位键（standard / higher / exhigh / lossless / hires…）；
     *                   不认识的键按 standard 处理，保证"最差也能出声"。
     */
    suspend fun resolve(
        songMid: String,
        mediaMid: String?,
        qualityKey: String,
    ): QQMusicPlaybackResult = withContext(Dispatchers.IO) {
        if (songMid.isBlank()) return@withContext QQMusicPlaybackResult.Unavailable("歌曲 ID 为空")
        val session = sessionProvider()
        val nowMs = clock()
        // 搜索结果只有 songMid；而无损/320k 的文件名规则需要 media_mid，
        // 缺了它只能拿到标准档。顺序：入参 → 缓存 → 补查详情。
        val effectiveMediaMid = mediaMid?.takeIf(String::isNotBlank)
            ?: mediaMidCache[songMid]
            ?: runCatching { fetchMediaMid(songMid, session) }.getOrNull()
                ?.also { mediaMidCache.put(songMid, it) }
        val candidates = qqQualityCandidatesFrom(
            qualityKey = qualityKey,
            resumeFrom = qualityCache[songMid]
                ?.takeIf { nowMs - it.decidedAtMs <= QUALITY_DECISION_TTL_MS }
                ?.qualityKey,
        )
        var lastReason: String? = null
        // 只反映"最后一次尝试的失败性质"：某一档网络失败、下一档明确没资源时，
        // 结论应该是"没资源"而不是"网络断了"——否则会一直显示错误的提示。
        var lastWasTransport = false

        for (candidate in candidates) {
            var transportFailure = false
            val resolved = try {
                requestVkeyUrl(songMid, effectiveMediaMid, candidate, session, nowMs)
            } catch (error: QQMusicApiException) {
                if (error.requiresRelogin) {
                    // 登录失效：后面的档位也一定是同样结果，直接中止
                    onSessionExpired()
                    return@withContext QQMusicPlaybackResult.LoginExpired
                }
                transportFailure = error.kind == QQMusicFailureKind.Transport
                lastReason = error.message
                null
            } catch (error: IOException) {
                transportFailure = true
                lastReason = error.message
                null
            }
            if (resolved != null) {
                // 只有真正成功才记住档位；失败不写缓存，避免把"临时没资源"固化成结论
                qualityCache.put(songMid, QualityDecision(candidate.qualityKey, nowMs))
                return@withContext QQMusicPlaybackResult.Playable(
                    url = resolved.url,
                    qualityKey = candidate.qualityKey,
                    bitrateKbps = candidate.bitrateKbps,
                    format = candidate.extension.removePrefix("."),
                    expiresAtEpochMs = resolved.expiresAtEpochMs,
                )
            }
            lastWasTransport = transportFailure
        }

        if (!session.isLoggedIn) {
            QQMusicPlaybackResult.LoginRequired
        } else if (lastWasTransport) {
            // 网络层失败：不要伪装成"没有资源"，也不要让 OkHttp 的原文冒到界面上
            QQMusicPlaybackResult.Transport(
                lastReason ?: "QQ 音乐网络不可用",
            )
        } else {
            QQMusicPlaybackResult.Unavailable(
                lastReason ?: "QQ 音乐没有返回可播放地址（可能需要该音质权益）",
            )
        }
    }

    /**
     * 请求一次 VKey。
     *
     * 请求身份与 MeloX 验证过的一致：Android 客户端（ct=11 / cv=14090008），
     * 带上 `authst = musicKey` 才有权限拿无损；未登录只能拿试听/标准档。
     *
     * @return 可播放的绝对 URL 与其过期时间；这一档没有资源时返回 null。
     */
    private fun requestVkeyUrl(
        songMid: String,
        mediaMid: String?,
        candidate: QQQualityCandidate,
        session: QQMusicSession,
        nowMs: Long,
    ): ResolvedVkey? {
        // VKey 的 guid 是“已注册设备号”，不是随便的随机数：
        // 使用随机 guid 时服务端会返回 104009 / invalidq（2026-10-31 实测）。
        // 优先用 App 会话里的 wid，其次退回账号 uin（两者都曾实测可放行）。
        val guid = session.wid.takeIf { it.isNotBlank() }
            ?: session.uin.ifBlank { "0" }
        val fileName = candidate.fileName(songMid, mediaMid)
        val param = JSONObject()
            .put("uin", session.uin.ifBlank { "0" })
            .put("guid", guid)
            .put("filename", JSONArray().put(fileName))
            .put("vecPrefix", JSONArray().put(candidate.prefix))
            .put("downloadfrom", 0)
            .put("source", "search")
            .put("songmid", JSONArray().put(songMid))
            .put("songtype", JSONArray().put(0))
            .put("ctx", 0)
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 11)
                    .put("cv", 14_090_008)
                    .put("v", 14_090_008)
                    .put("qq", session.uin.ifBlank { "0" })
                    .put("authst", session.musicKey)
                    .put("tmeAppID", "qqmusic")
                    .put("format", "json"),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.vkey.GetVkey")
                    .put("method", "UrlGetVkey")
                    .put("param", param),
            )

        val data = client.post(payload, session)
        val midUrl = data.optJSONArray("midurlinfo")?.optJSONObject(0) ?: return null
        // result != 0 通常代表"这一档没有权限/没有资源"，直接换下一档，不算致命错误。
        if (midUrl.optInt("result", 0) != 0) return null
        val purl = midUrl.optString("purl").trim()
        if (purl.isBlank()) return null
        val expiresAtEpochMs = resolveVkeyExpiryMs(midUrl, data, nowMs)
        if (purl.startsWith("http://") || purl.startsWith("https://")) {
            return ResolvedVkey(upgradeHttps(purl), expiresAtEpochMs)
        }

        val sip = data.optJSONArray("sip") ?: return null
        val domain = (0 until sip.length())
            .mapNotNull { index -> sip.optString(index).trim().takeIf(String::isNotBlank) }
            .firstOrNull { !it.startsWith("http://ws", ignoreCase = true) }
            ?: (0 until sip.length())
                .mapNotNull { index -> sip.optString(index).trim().takeIf(String::isNotBlank) }
                .firstOrNull()
            ?: "https://isure.stream.qqmusic.qq.com/"
        return ResolvedVkey(upgradeHttps(domain + purl), expiresAtEpochMs)
    }

    /**
     * 计算 vkey 直链的绝对过期时间。
     *
     * 服务端并非每个响应都下发有效期，所以策略是"有就用、没有就保守估计"：
     *  - 字段 `< 1e9` 视为相对秒数（`now + n`）；
     *  - `1e9 ~ 1e12` 视为秒级时间戳；
     *  - 更大视为毫秒时间戳；
     *  - 都没有时按 20 分钟（QQ 直链的常见有效期），宁可保守也不要复用过期 URL。
     */
    private fun resolveVkeyExpiryMs(midUrl: JSONObject, data: JSONObject, nowMs: Long): Long {
        val raw = sequenceOf("expiration", "expire", "expire_time")
            .map { midUrl.optLong(it, 0L) }
            .firstOrNull { it > 0L }
            ?: sequenceOf("expiration", "expire_time")
                .map { data.optLong(it, 0L) }
                .firstOrNull { it > 0L }
            ?: return nowMs + DEFAULT_VKEY_TTL_MS
        return when {
            raw > 1_000_000_000_000L -> raw
            raw > 1_000_000_000L -> raw * 1_000L
            else -> nowMs + raw * 1_000L
        }
    }

    /**
     * 查询歌曲的 `media_mid`（高音质文件名要用）。
     *
     * VKey 的文件名规则是 `F000 + media_mid + .flac`；只有 songmid 时，
     * 服务端拿不到高音质资源，会一路降级到 128k。搜索结果、手动输入的歌曲
     * 都可能没有 media_mid，所以播放前补查一次详情。
     */
    private fun fetchMediaMid(songMid: String, session: QQMusicSession): String? {
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", session.uin.ifBlank { "0" }),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put("param", JSONObject().put("song_mid", songMid)),
            )
        return runCatching {
            client.post(payload, session)
                .optJSONObject("track_info")
                ?.optJSONObject("file")
                ?.optString("media_mid")
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun upgradeHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }
}

/** 一次 vkey 解析的产物：直链 + 它的绝对过期时间。 */
private data class ResolvedVkey(
    val url: String,
    val expiresAtEpochMs: Long,
)

/**
 * 极简 LRU（按访问顺序淘汰）。
 *
 * 不引第三方缓存库：这里只存两串短字符串，且必须能被测试清空。
 */
internal class LruCache<K, V>(private val maxEntries: Int = 64) {
    private val entries = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    operator fun get(key: K): V? = synchronized(entries) { entries[key] }

    fun put(key: K, value: V) {
        synchronized(entries) { entries[key] = value }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}

/** 一档 QQ 音质：文件码 + 扩展名 + 对外的通用档位键 + 码率。 */
internal data class QQQualityCandidate(
    val prefix: String,
    val extension: String,
    val qualityKey: String,
    val bitrateKbps: Int? = null,
) {
    /**
     * 文件名规则（与 QQ 客户端一致）：
     *  - 有 media_mid 时：文件码 + media_mid + 扩展名；
     *  - 没有时：文件码 + songmid + songmid + 扩展名。
     *
     * media_mid 与 songmid 不一定相同（高音质资源常用 media_mid），
     * 缺了它会导致无损/320k 拿不到地址，只能退回标准档。
     */
    fun fileName(songMid: String, mediaMid: String?): String {
        val media = mediaMid?.takeIf(String::isNotBlank)
        return if (media != null) {
            "$prefix$media$extension"
        } else {
            "$prefix$songMid$songMid$extension"
        }
    }
}

/**
 * 把 NeriPlayer 的音质键翻译成 QQ 的候选链（从高到低）。
 *
 * 之所以要做"链"而不是一档：很多曲目没有无损资源，或者账号没有该档权益，
 * 服务端会返回空 purl。逐档降级能保证"至少能出声"，同时把实际档位如实回报。
 */
internal fun qqQualityCandidates(qualityKey: String): List<QQQualityCandidate> {
    val lossless = QQQualityCandidate("F000", ".flac", "lossless")
    val exhigh = QQQualityCandidate("M800", ".mp3", "exhigh", 320)
    val standard = QQQualityCandidate("M500", ".mp3", "standard", 128)
    return when (qualityKey.trim().lowercase()) {
        "lossless", "hires", "jymaster", "sky", "jyeffect" -> listOf(lossless, exhigh, standard)
        "exhigh", "higher" -> listOf(exhigh, standard)
        else -> listOf(standard)
    }
}

/**
 * 从"上次成功档位"开始的候选链。
 *
 * 例：请求 lossless，上次实际拿到 exhigh → 本次只试 `[exhigh, standard]`。
 * 无损档已知拿不到，再试一次只是多发一个请求。
 *
 * 注意：仍保留降级，账号权益变化或曲目补档时不会卡在旧结论上。
 */
internal fun qqQualityCandidatesFrom(
    qualityKey: String,
    resumeFrom: String?,
): List<QQQualityCandidate> {
    val chain = qqQualityCandidates(qualityKey)
    val index = chain.indexOfFirst { it.qualityKey == resumeFrom }
    return if (index > 0) chain.subList(index, chain.size) else chain
}

/** 无过期字段时的保守估计：QQ 直链常见有效期约 20 分钟。 */
internal const val DEFAULT_VKEY_TTL_MS: Long = 20L * 60L * 1000L

/** 档位结论的有效期；过期后重新从请求档开始试，避免把临时缺资源固化成永久结论。 */
internal const val QUALITY_DECISION_TTL_MS: Long = 30L * 60L * 1000L

private const val CACHE_SIZE = 64
