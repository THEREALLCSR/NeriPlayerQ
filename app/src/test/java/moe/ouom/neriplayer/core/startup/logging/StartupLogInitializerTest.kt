package moe.ouom.neriplayer.core.startup.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupLogInitializerTest {
    @Test
    fun `enables file logging when dev mode is enabled`() {
        assertTrue(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = true,
                alwaysRecordLogsEnabled = false,
                debugBuild = false,
            )
        )
    }

    @Test
    fun `enables file logging when always record logs is enabled`() {
        assertTrue(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = false,
                alwaysRecordLogsEnabled = true,
                debugBuild = false,
            )
        )
    }

    @Test
    fun `keeps file logging disabled when all switches are off`() {
        assertFalse(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = false,
                alwaysRecordLogsEnabled = false,
                debugBuild = false,
            )
        )
    }

    @Test
    fun `debug build always enables file logging regardless of switches`() {
        // 迭代二加 debugBuild 参数的初衷：debug 包默认落文件日志，
        // 否则 Application.onCreate 阶段的探针日志会丢失（见 07 号文档）。
        assertTrue(
            StartupLogInitializer.shouldEnableFileLogging(
                devModeEnabled = false,
                alwaysRecordLogsEnabled = false,
                debugBuild = true,
            )
        )
    }
}
