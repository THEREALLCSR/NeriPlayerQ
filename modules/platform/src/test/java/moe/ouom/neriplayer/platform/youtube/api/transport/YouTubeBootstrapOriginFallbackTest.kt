package moe.ouom.neriplayer.platform.youtube.api.transport

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换 origin 重试的判据：只有"这个前端拒绝了我"才值得换，"网络路径不通"换十个也一样。
 *
 * 真机日志实证（2026-11-17 01:02）：music.youtube.com 连接超时 8022ms 后，
 * 代码仍去试 www.youtube.com，又烧了 8037ms —— 两个都是 Google 前端，走同一条路径。
 */
class YouTubeBootstrapOriginFallbackTest {

    @Test
    fun `connect timeout does not justify trying the other origin`() {
        // 连接层超时 = 包到不了对端，换域名只是把同一个超时再付一次
        assertFalse(shouldTryNextBootstrapOrigin(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun `dns failure does not justify trying the other origin`() {
        assertFalse(shouldTryNextBootstrapOrigin(UnknownHostException("music.youtube.com")))
        assertFalse(shouldTryNextBootstrapOrigin(UnknownHostException("www.youtube.com")))
    }

    @Test
    fun `refused or unroutable connections do not justify a retry`() {
        assertFalse(shouldTryNextBootstrapOrigin(ConnectException("ECONNREFUSED")))
        assertFalse(shouldTryNextBootstrapOrigin(NoRouteToHostException("no route")))
    }

    @Test
    fun `tls handshake failure does not justify a retry`() {
        // 握手失败通常是被中间设备劫持/阻断，两个域名同命运
        assertFalse(shouldTryNextBootstrapOrigin(SSLHandshakeException("certificate verify failed")))
    }

    @Test
    fun `http status failures do justify trying the other origin`() {
        // 对端可达并明确回了状态码，换个前端可能就好了（429/503/403 都见过）
        assertTrue(shouldTryNextBootstrapOrigin(YouTubeHttpStatusException(429, 1000L, "rate")))
        assertTrue(shouldTryNextBootstrapOrigin(YouTubeHttpStatusException(503, null, "busy")))
        assertTrue(shouldTryNextBootstrapOrigin(YouTubeHttpStatusException(403, null, "denied")))
        assertTrue(shouldTryNextBootstrapOrigin(YouTubeHttpStatusException(500, null, "boom")))
    }

    @Test
    fun `generic io failure after a connection was established justifies a retry`() {
        // 已经连上了才出错（比如读响应体时断流），另一个前端有机会成功
        assertTrue(shouldTryNextBootstrapOrigin(IOException("unexpected end of stream")))
    }

    @Test
    fun `unknown or absent error is treated as worth one retry`() {
        // 判不了就别自作聪明地放弃：这里给一次机会比静默失败好
        assertTrue(shouldTryNextBootstrapOrigin(null))
        assertTrue(shouldTryNextBootstrapOrigin(RuntimeException("weird")))
    }

    @Test
    fun `network level failure is recognised as a whole-network problem`() {
        // 供冷却逻辑复用：这类失败说明"这台设备现在连不上 YouTube"，与具体 origin 无关
        assertTrue(isYouTubeNetworkUnreachable(SocketTimeoutException("connect timed out")))
        assertTrue(isYouTubeNetworkUnreachable(UnknownHostException("www.youtube.com")))
        assertTrue(isYouTubeNetworkUnreachable(ConnectException("ECONNREFUSED")))
        assertFalse(isYouTubeNetworkUnreachable(YouTubeHttpStatusException(429, null, "rate")))
        assertFalse(isYouTubeNetworkUnreachable(IOException("unexpected end of stream")))
        assertFalse(isYouTubeNetworkUnreachable(null))
    }

    @Test
    fun `a wrapped connect timeout is still recognised`() {
        // 中间层（合并请求/协程取消包装）会把原始异常包一层，不能因此漏判
        val wrapped = IOException("bootstrap failed", SocketTimeoutException("connect timed out"))

        assertTrue(isYouTubeNetworkUnreachable(wrapped))
        assertFalse(shouldTryNextBootstrapOrigin(wrapped))
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        // 病态 cause 链（A->B->A）不能让判定陷入死循环
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)

        assertFalse(isYouTubeNetworkUnreachable(first))
    }
}
