package moe.ouom.neriplayer.ui.viewmodel.playlist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/QqPlaylistDetailViewModel
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicLibraryRepository
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicPlaylist
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicTrack

private const val PAGE_SIZE = 100

/**
 * QQ 歌单详情状态。
 *
 * 只承载"数据 + 加载过程"；多选、弹窗、搜索词等纯 UI 状态仍留在页面内。
 */
data class QqPlaylistDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tracks: List<QQMusicTrack> = emptyList(),
    val total: Int = 0,
    val loadingMore: Boolean = false,
    val message: String? = null,
    /** 下一个请求偏移：按请求步长推进，避免服务端过滤无版权歌曲导致"卡在 98 首"。 */
    val nextBegin: Int = 0
)

/**
 * QQ 歌单详情加载器：首屏、分页、重试。
 *
 * 分页逻辑与页面原有实现一致：
 * - 按 `nextBegin`（请求步长）推进，而不是用已加载条数；
 * - 跨页按 `songMid` 去重；
 * - 某一页没有新歌时把 `total` 收敛，停止继续请求。
 */
class QqPlaylistDetailViewModel internal constructor(
    private val repository: QQMusicLibraryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor() : this(AppContainer.qqMusicLibraryRepository)

    private val _uiState = MutableStateFlow(QqPlaylistDetailUiState())
    val uiState: StateFlow<QqPlaylistDetailUiState> = _uiState

    private var dissid: String = ""
    private var currentPlaylist: QQMusicPlaylist? = null
    private var loadJob: Job? = null
    private var moreJob: Job? = null

    fun start(playlist: QQMusicPlaylist) {
        if (dissid == playlist.id && _uiState.value.tracks.isNotEmpty()) return
        currentPlaylist = playlist
        dissid = playlist.id
        _uiState.value = QqPlaylistDetailUiState(loading = true)
        reload()
    }

    /** 重新拉取第一页（重试、增删歌曲后调用）。 */
    fun reload() {
        if (dissid.isBlank()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                withContext(ioDispatcher) {
                    repository.playlistTracksPage(
                        dissid = dissid,
                        begin = 0,
                        limit = PAGE_SIZE
                    )
                }
            }.onSuccess { page ->
                // 歌单允许重复歌曲：先按 mid 去重，页面 key 再用"序号 + mid"兜底
                val unique = page.tracks.distinctBy { it.songMid }
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = null,
                        tracks = unique,
                        total = page.total.coerceAtLeast(unique.size),
                        nextBegin = PAGE_SIZE
                    )
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = failure.message ?: "加载歌单失败"
                    )
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore) return
        if (state.nextBegin >= state.total) return
        if (moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val requestBegin = _uiState.value.nextBegin
            runCatching {
                withContext(ioDispatcher) {
                    repository.playlistTracksPage(
                        dissid = dissid,
                        begin = requestBegin,
                        limit = PAGE_SIZE
                    )
                }
            }.onSuccess { page ->
                val existing = _uiState.value.tracks.mapTo(mutableSetOf()) { it.songMid }
                val fresh = page.tracks.filter { existing.add(it.songMid) }
                _uiState.update { current ->
                    if (fresh.isEmpty()) {
                        // 这一页没有新歌：说明已经到末尾（或服务端忽略了偏移），停止翻页
                        current.copy(
                            loadingMore = false,
                            total = current.tracks.size,
                            nextBegin = requestBegin + PAGE_SIZE
                        )
                    } else {
                        current.copy(
                            loadingMore = false,
                            tracks = current.tracks + fresh,
                            total = page.total.coerceAtLeast(current.tracks.size + fresh.size),
                            nextBegin = requestBegin + PAGE_SIZE
                        )
                    }
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        loadingMore = false,
                        message = failure.message ?: "加载更多失败"
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** 重命名当前歌单；成功后通过 [onRenamed] 把新标题回传给页面。 */
    fun rename(newName: String, onRenamed: (String) -> Unit) {
        val playlist = currentPlaylist ?: return
        val title = newName.trim()
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    repository.updatePlaylist(playlist, name = title)
                }
            }.onSuccess { updated ->
                currentPlaylist = updated
                _uiState.update { it.copy(message = "歌单已重命名") }
                onRenamed(updated.title)
            }.onFailure { failure ->
                _uiState.update { it.copy(message = failure.message ?: "重命名失败") }
            }
        }
    }

    /** 删除当前歌单；成功后通过 [onDeleted] 让页面返回上一级。 */
    fun delete(onDeleted: () -> Unit) {
        val playlist = currentPlaylist ?: return
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) { repository.deletePlaylist(playlist) }
            }.onSuccess {
                _uiState.update { it.copy(message = "歌单已删除") }
                onDeleted()
            }.onFailure { failure ->
                _uiState.update { it.copy(message = failure.message ?: "删除歌单失败") }
            }
        }
    }
}
