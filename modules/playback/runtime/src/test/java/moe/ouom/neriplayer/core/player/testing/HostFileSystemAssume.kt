package moe.ouom.neriplayer.core.player.testing

import java.io.File
import org.junit.Assume.assumeTrue

/**
 * 跳过"断言 Android POSIX 文件系统语义"的用例。
 *
 * 这些用例的前提是：`/storage/emulated/0/...` 这类路径就是真实文件位置，
 * `File.canonicalPath` 不会改写它，`Uri.fromFile` 的 path 与声明路径一致。
 *
 * 在非 POSIX 宿主（Windows）上，这个前提不成立：
 * - `File("/storage/x").canonicalPath` 会变成 `C:\storage\x`；
 * - 树文档 URI 与声明路径再也对不上，文件夹授权恢复、冲突检测、
 *   侧载文件定位会走出完全不同的分支。
 *
 * 此时失败与被测逻辑无关，把红色留在那里只会让"红了 = 有问题"再次失效。
 * 用 assumption 跳过并写明原因：Linux CI 照常执行，本机不再制造假红。
 */
fun assumePosixFileSystem() {
    assumeTrue(
        "该用例断言 Android POSIX 路径语义，非 POSIX 宿主（Windows）无法建模；Linux CI 仍执行",
        File.separatorChar == '/'
    )
}

/**
 * 跳过需要"用极长 classpath 拉起子 JVM"的用例。
 *
 * Windows 的 CreateProcess 命令行上限约 32K，Gradle 的测试 classpath 轻易超出，
 * 子进程直接 `error=206 文件名或扩展名太长`，用例表现为启动失败或永久等待。
 * 这是宿主限制，不是被测的持久化逻辑有问题。
 */
fun assumeSpawnableTestJvm() {
    assumeTrue(
        "该用例需要拉起携带完整测试 classpath 的子 JVM，Windows 命令行长度上限导致无法执行",
        !System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    )
}
