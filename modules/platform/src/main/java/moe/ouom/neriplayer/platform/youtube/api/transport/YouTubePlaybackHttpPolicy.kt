package moe.ouom.neriplayer.platform.youtube.api.transport

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// 脏 IP 下 429/503 退避基数与上限, 避免密集重试进一步拉高限流等级 (#Y5)
private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
private const val RATE_LIMIT_BACKOFF_MAX_MS = 5_000L



/**
 * 携带 HTTP 状态码与 Retry-After 的请求失败异常
 * 仍继承 IOException, 保持既有 catch(IOException) 与消息正则解析 (401/403/429) 兼容
 */
class YouTubeHttpStatusException(
    val statusCode: Int,
    val retryAfterMs: Long?,
    message: String
) : IOException(message)

/** 解析 Retry-After: 仅支持 delta-seconds 整数秒形式, HTTP-date 形式忽略并走指数退避 */
fun parseRetryAfterMs(headerValue: String?): Long? {
    val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
    return if (seconds >= 0L) seconds * 1000L else null
}

/** 429/503 退避时长: 优先 Retry-After, 否则按累计命中次数指数退避, 统一封顶 */
fun rateLimitBackoffMs(error: Throwable?, priorHits: Int): Long? {
    val status = error as? YouTubeHttpStatusException ?: return null
    if (status.statusCode != 429 && status.statusCode != 503) {
        return null
    }
    status.retryAfterMs?.let { return it.coerceIn(0L, RATE_LIMIT_BACKOFF_MAX_MS) }
    return (RATE_LIMIT_BACKOFF_BASE_MS shl priorHits.coerceIn(0, 3))
        .coerceAtMost(RATE_LIMIT_BACKOFF_MAX_MS)
}

/**
 * 这次失败换成另一个 origin 重试有没有意义
 *
 * `music.youtube.com` 与 `www.youtube.com` 是同一批 Google 前端，走的是同一条网络路径。
 * 连接超时、DNS 解析失败、TLS 握手失败都说明"这台设备现在到不了那里"，
 * 换域名只会把同一个超时原样再付一次（真机实测 8022ms + 8037ms）。
 * 只有对端真的可达并明确回了状态码，换前端才有可能是另一个结果。
 */
fun shouldTryNextBootstrapOrigin(error: Throwable?): Boolean = !isYouTubeNetworkUnreachable(error)

/**
 * 失败是否属于"整条网络路径不通"，与具体 origin 无关
 *
 * 连接层错误一共只会产生这几种，IOException 的其余子类（读响应体断流、协议异常）
 * 都已经建立过连接，不能算作网络不可达。协程/合并请求等中间层常把原始异常包一层，
 * 所以沿 cause 链向下找。
 */
fun isYouTubeNetworkUnreachable(error: Throwable?): Boolean {
    var current = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is UnknownHostException -> return true
            is SocketTimeoutException -> return true
            is ConnectException -> return true
            is NoRouteToHostException -> return true
            // 握手失败通常是被中间设备劫持/阻断，两个域名同命运
            is SSLException -> return true
        }
        if (current.cause === current) return false
        current = current.cause
        depth++
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
