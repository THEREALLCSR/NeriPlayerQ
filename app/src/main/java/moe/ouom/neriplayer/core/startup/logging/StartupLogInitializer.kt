package moe.ouom.neriplayer.core.startup.logging

import android.content.Context
import moe.ouom.neriplayer.common.logging.NPLogger

internal object StartupLogInitializer {
    /**
     * 是否写文件日志。
     *
     * 三个来源取或：
     *  - [devModeEnabled] / [alwaysRecordLogsEnabled]：用户在设置里显式开启；
     *  - [debugBuild]：debug 包默认开启。
     *
     * 为什么 debug 包要默认开：上一轮真机验证的日志是"用户手动去设置里打开"才有的，
     * 换台设备/换个人测试就再也拿不到上下文——测试版应当自带黑匣子。
     * release 包不受影响，仍严格跟随用户开关，避免写满正式用户的存储。
     */
    fun shouldEnableFileLogging(
        devModeEnabled: Boolean,
        alwaysRecordLogsEnabled: Boolean,
        debugBuild: Boolean
    ): Boolean {
        return debugBuild || devModeEnabled || alwaysRecordLogsEnabled
    }

    fun sync(
        context: Context,
        devModeEnabled: Boolean,
        alwaysRecordLogsEnabled: Boolean,
        debugBuild: Boolean
    ) {
        NPLogger.init(
            context = context,
            enableFileLogging = shouldEnableFileLogging(
                devModeEnabled = devModeEnabled,
                alwaysRecordLogsEnabled = alwaysRecordLogsEnabled,
                debugBuild = debugBuild
            )
        )
    }
}
