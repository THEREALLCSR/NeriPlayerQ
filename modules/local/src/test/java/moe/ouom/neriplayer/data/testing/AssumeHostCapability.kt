package moe.ouom.neriplayer.data.testing

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 在 Windows 开发机上跳过"只有 Linux / Android 文件系统语义才成立"的测试类。
 *
 * 背景（台账 B8）：`:local` / `:download:runtime` 有上百条测试断言 Android POSIX 路径、
 * 文件的原子 rename、以及短路径下的 SQLite。这些都依赖宿主能力，与被测逻辑无关：
 *
 * - [POSIX_PATHS]：`File("/storage/emulated/0/x").canonicalPath` 在 Windows 上被改写成
 *   `C:\storage\...`，凡断言"完整/规范路径、符号链接解析、绝对引用"的用例无一幸免；
 * - [ATOMIC_RENAME]：Windows 的 `Files.move` 覆盖一个被占用的 `.tmp` 会抛
 *   `Unable to rename ...`（DataStore / WAL 落盘的临时文件），Linux 上则是原子替换；
 * - [SHORT_PATH]：Robolectric 原生 SQLite 走 Win32 文件 API，测试 dataDir 拼上长方法名后
 *   超过 MAX_PATH 260，`SQLITE_CANTOPEN`，与业务无关。
 *
 * 这里用 JUnit4 的 [TestRule] 在**类级**跳过：Linux CI 上三个能力都成立，照常全量执行；
 * 本机则记为 skipped 并写明原因，让"红"重新等于"真问题"。
 *
 * 用法：
 * ```kotlin
 * @ClassRule @JvmField
 * val host = AssumeHostCapability(POSIX_PATHS)   // 或 ATOMIC_RENAME / SHORT_PATH
 * ```
 */
class AssumeHostCapability(private val capability: HostCapability) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assumeTrue(capability.reason, capability.supported)
            base.evaluate()
        }
    }
}

/** Windows 宿主标志。放在文件顶层而非枚举 companion：枚举项初始化时 companion 尚未可用。 */
val IS_WINDOWS: Boolean = System.getProperty("os.name").orEmpty()
    .startsWith("Windows", ignoreCase = true)

enum class HostCapability(val supported: Boolean, val reason: String) {
    POSIX_PATHS(
        supported = File.separatorChar == '/',
        reason = "该用例断言 Android POSIX 路径语义（完整/规范路径、符号链接、绝对引用）；" +
            "Windows 会把 /storage/... 改写成盘符路径，Linux CI 仍执行",
    ),
    ATOMIC_RENAME(
        supported = !IS_WINDOWS,
        reason = "该用例依赖 POSIX 的原子 rename（临时文件覆盖）；" +
            "Windows 的 Files.move 覆盖被占用目标会失败，Linux CI 仍执行",
    ),
    SHORT_PATH(
        supported = !IS_WINDOWS,
        reason = "该用例需要 Robolectric 原生 SQLite 打开长路径下的数据库；" +
            "Windows MAX_PATH 260 导致 SQLITE_CANTOPEN，Linux CI 仍执行",
    ),
    ;
}

// 便于在测试里写 `AssumeHostCapability(POSIX_PATHS)`
val POSIX_PATHS = HostCapability.POSIX_PATHS
val ATOMIC_RENAME = HostCapability.ATOMIC_RENAME
val SHORT_PATH = HostCapability.SHORT_PATH
