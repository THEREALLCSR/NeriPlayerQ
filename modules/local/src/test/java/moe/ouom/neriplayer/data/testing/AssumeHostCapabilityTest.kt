package moe.ouom.neriplayer.data.testing

import org.junit.Assert.fail
import org.junit.ClassRule
import org.junit.Test

/**
 * 验证 [AssumeHostCapability] 自身行为：被跳过的类里的失败断言不应变成 failure。
 *
 * 这个类的 `deliberatelyFailingOnWindows` 在 Windows 上必失败，但类级 Rule 应把整个类
 * 标记为 skipped。若 Rule 失效，这条会成为 failure 暴露出来。Linux CI 上 POSIX 成立，
 * 所以那条断言也要改成"不失败"以保证 CI 全绿。
 */
class AssumeHostCapabilityTest {

    companion object {
        @JvmField
        @ClassRule
        val host = AssumeHostCapability(POSIX_PATHS)
    }

    @Test
    fun capabilityFlagsReflectHost() {
        // POSIX_PATHS.supported 与分隔符一致，这条在两边都成立
        assert(HostCapability.POSIX_PATHS.supported == (java.io.File.separatorChar == '/'))
    }

    @Test
    fun deliberatelyFailingOnWindows() {
        if (IS_WINDOWS) {
            fail("这条在 Windows 上本应被类级 Rule 跳过；如果读到它说明 Rule 失效了")
        }
    }
}
