package moe.ouom.neriplayer.platform.youtube.api.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络不可达后的冷却：付费一次 8 秒是运气不好，每次冷启动都付费是设计问题。
 */
class YouTubeBootstrapNetworkCooldownTest {

    private val cooldown = 30L * 60L * 1000L

    @Test
    fun `no recorded failure means warm may run`() {
        assertFalse(shouldSkipBootstrapWarm(lastFailureAtMs = null, nowMs = 1_000L, cooldownMs = cooldown))
    }

    @Test
    fun `right after a network failure warm is skipped`() {
        assertTrue(
            shouldSkipBootstrapWarm(
                lastFailureAtMs = 10_000L,
                nowMs = 10_500L,
                cooldownMs = cooldown
            )
        )
    }

    @Test
    fun `skip holds until the cooldown window closes`() {
        val failedAt = 10_000L
        assertTrue(
            shouldSkipBootstrapWarm(failedAt, failedAt + cooldown - 1L, cooldown)
        )
        assertFalse(
            shouldSkipBootstrapWarm(failedAt, failedAt + cooldown, cooldown)
        )
        assertFalse(
            shouldSkipBootstrapWarm(failedAt, failedAt + cooldown + 1L, cooldown)
        )
    }

    @Test
    fun `a timestamp from the future does not lock the feature out`() {
        // 设备时间被改回过、或落盘时间戳被写坏时，不能永久禁用预热
        assertFalse(
            shouldSkipBootstrapWarm(
                lastFailureAtMs = 9_000_000L,
                nowMs = 1_000L,
                cooldownMs = cooldown
            )
        )
    }

    @Test
    fun `zero cooldown never skips`() {
        assertFalse(shouldSkipBootstrapWarm(10_000L, 10_000L, cooldownMs = 0L))
    }

    @Test
    fun `only network level failures start a cooldown`() {
        assertTrue(
            cooldownStartForFailure(
                networkUnreachable = true,
                nowMs = 42L,
                previous = null
            ) == 42L
        )
        // HTTP 429 之类的失败说明网络是通的，下一次冷启动应该照常预热
        assertEquals(
            null,
            cooldownStartForFailure(networkUnreachable = false, nowMs = 42L, previous = 42L)
        )
    }
}
