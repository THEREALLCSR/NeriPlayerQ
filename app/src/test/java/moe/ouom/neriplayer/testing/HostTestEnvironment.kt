package moe.ouom.neriplayer.testing

import java.io.File
import org.junit.Assume.assumeTrue

/**
 * 本机（Windows）无法建模的宿主能力，按方法跳过，Linux CI 照常执行。
 *
 * 台账 B8：app 模块此前从未进 CI（568 个测试文件无人校验）。接入 CI 前先跑了一遍，
 * 5 条失败里 4 条是宿主环境性，逐条核实如下：
 * - DataStore `Unable to rename ...tmp`：Windows 的 Files.move 覆盖被占用临时文件失败；
 * - 本地侧载元数据 `ComparisonFailure`：测试用 Windows 绝对路径构造 seed，
 *   而侧载定位按 Android POSIX 路径建模，读不到 .npmeta.json。
 *
 * 这与 `:local` / `:download:runtime` 的 `AssumeHostCapability` 是同一策略，
 * 只是 app 只需方法级跳过（不整类），故提供函数而非 Rule。
 */
object HostTestEnvironment {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty()
        .startsWith("Windows", ignoreCase = true)

    fun assumePosixPaths() {
        assumeTrue(
            "该用例按 Android POSIX 路径定位本地侧载文件；" +
                "Windows 绝对路径无法建模，Linux CI 仍执行",
            File.separatorChar == '/',
        )
    }

    fun assumeAtomicRename() {
        assumeTrue(
            "该用例依赖 POSIX 原子 rename（DataStore 临时文件覆盖）；" +
                "Windows 的 Files.move 会失败，Linux CI 仍执行",
            !isWindows,
        )
    }
}
