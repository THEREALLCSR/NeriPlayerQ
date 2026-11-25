package moe.ouom.neriplayer.architecture

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
 * File: moe.ouom.neriplayer.architecture/QqDetailViewModelFactoryParadigmTest
 */

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 详情页 ViewModel 创建范式统一（迭代 5）的红灯契约测试。
 *
 * `QqSingerDetailViewModel` 与 `QqPlaylistDetailViewModel` 是 `AndroidViewModel`，
 * 但它们的 `application` 参数**只在构造时传给父类，类体从未使用**（无 getApplication）。
 * 这导致两个后果：
 *  1. 两个 Screen 必须手写 `viewModelFactory { initializer { ...(context.applicationContext) } }`，
 *    样板代码多、且 ViewModel 与 Android 生命周期绑定、难以单测；
 *  2. 既然 Application 根本没用，这个耦合是纯粹的历史惯性。
 *
 * 范式：ViewModel 改为普通 `ViewModel`（构造只依赖 repository/dispatcher，可注入），
 * Screen 用默认 `viewModel()` 创建，不再手写 factory。
 *
 * 本测试静态扫描钉住两点：两个 Screen 无手写 factory、两个 VM 不再继承 AndroidViewModel。
 * 改动前红、改完绿。Probe VM（真用了 getApplication 做剪贴板）不在本测试范围，另迭代处理。
 */
class QqDetailViewModelFactoryParadigmTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun source(relative: String): String = File(projectRoot, relative).readText()

    private val screens = listOf(
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/artist/QqMusicSingerDetailScreen.kt",
        "app/src/main/java/moe/ouom/neriplayer/ui/screen/playlist/QqMusicPlaylistDetailScreen.kt",
    )

    private val viewModels = listOf(
        "app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/QqSingerDetailViewModel.kt",
        "app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/playlist/QqPlaylistDetailViewModel.kt",
    )

    @Test
    fun `QQ detail screens do not hand-write viewModelFactory`() {
        val offenders = screens.filter { source(it).contains("viewModelFactory") }
        assertTrue(
            "以下 QQ 详情页仍手写 viewModelFactory，应改用默认 viewModel()：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `QQ detail view models are not AndroidViewModel`() {
        val offenders = viewModels.filter { source(it).contains("AndroidViewModel") }
        assertTrue(
            "以下 QQ ViewModel 仍继承 AndroidViewModel（且其 application 从未被使用）：$offenders",
            offenders.isEmpty(),
        )
    }
}
