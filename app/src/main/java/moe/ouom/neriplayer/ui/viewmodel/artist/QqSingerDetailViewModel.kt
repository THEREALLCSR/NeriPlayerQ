package moe.ouom.neriplayer.ui.viewmodel.artist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.artist/QqSingerDetailViewModel
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicAlbum
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicLibraryRepository
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicSinger
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicTrack
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary

/**
 * QQ 歌手详情页状态。
 *
 * 对照网易云的 `NeteaseArtistDetailUiState`：字段命名保持一致；
 * 简介(description) 与专辑(albums) 复用统一的 [AlbumSummary]，
 * 让 QQ 与网易云的专辑行可以共用同一套组件。
 */
data class QqSingerDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val singer: QQMusicSinger? = null,
    val tracks: List<QQMusicTrack> = emptyList(),
    val total: Int = 0,
    /** 歌手百科简介；singerDetail 失败时留空，不影响歌曲主列表。 */
    val description: String = "",
    /** 歌手专辑；singerAlbums 失败时留空，不影响歌曲主列表。 */
    val albums: List<AlbumSummary> = emptyList(),
    /**
     * 原始 QQ 专辑（含 mid），专辑详情路由依赖 [QQMusicAlbum.mid] 构造；
     * [AlbumSummary] 只有 albumId(Long)，无法据此跳转，故额外保留原对象，与 albums 按 index 对齐。
     */
    val qqAlbums: List<QQMusicAlbum> = emptyList(),
    val loadingMore: Boolean = false,
    /** 一次性提示（下载入队、加载更多失败等）。 */
    val message: String? = null
)

/**
 * QQ 歌手作品加载器。
 *
 * 职责与 `NeteaseArtistDetailViewModel` 对齐：首屏加载、重试、分页、提示；
 * 页面只负责渲染，不再自己维护协程与去重逻辑。
 */
class QqSingerDetailViewModel internal constructor(
    private val repository: QQMusicLibraryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor() : this(AppContainer.qqMusicLibraryRepository)

    private val _uiState = MutableStateFlow(QqSingerDetailUiState())
    val uiState: StateFlow<QqSingerDetailUiState> = _uiState

    private var singerMid: String = ""
    private var page: Int = 0
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    /** 专辑歌曲数补数任务；切换歌手/重载时取消，避免旧歌手结果串台。 */
    private var albumCountJob: Job? = null

    /** 首次进入或切换歌手时调用；同一歌手重复调用不会重复请求。 */
    fun start(singer: QQMusicSinger) {
        if (singerMid == singer.mid && _uiState.value.tracks.isNotEmpty()) return
        singerMid = singer.mid
        page = 0
        _uiState.value = QqSingerDetailUiState(loading = true, singer = singer)
        reload()
    }

    fun retry() {
        if (singerMid.isBlank()) return
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        albumCountJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val queryMid = singerMid
            // 详情/专辑是"锦上添花"：各自 try/catch 降级为空，任一失败都不拖垮歌曲主列表。
            // 与 singerSongs 并行发起，避免串行等待拖慢首屏。
            val detailDeferred = async(ioDispatcher) {
                runCatching { repository.singerDetail(queryMid) }.getOrNull()
            }
            val albumsDeferred = async(ioDispatcher) {
                runCatching { repository.singerAlbums(queryMid) }
                    .getOrDefault(emptyList())
            }
            runCatching {
                withContext(ioDispatcher) {
                    repository.singerSongs(queryMid, page = 1)
                }
            }.onSuccess { result ->
                page = 1
                // 原始列表（含 mid）先留存供导航，再映射成共享行组件用的 AlbumSummary。
                val originalAlbums = albumsDeferred.await()
                _uiState.update {
                    it.copy(
                        loading = false,
                        tracks = result.tracks,
                        total = result.total.coerceAtLeast(result.tracks.size),
                        description = detailDeferred.await()?.description.orEmpty(),
                        qqAlbums = originalAlbums,
                        albums = originalAlbums.map { album -> album.toAlbumSummary() },
                        error = null
                    )
                }
                // 首屏已渲染（专辑行先显示原值），再异步逐张补歌曲数，不阻塞首屏。
                enrichAlbumSongCounts(originalAlbums)
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = failure.message ?: "加载歌手作品失败"
                    )
                }
            }
        }
    }

    /**
     * 专辑歌曲数补数：GetAlbumList 不返回歌曲数（totalNum 恒 0），
     * 用专辑详情接口 totalNum（真机 49 首）逐张补；分批+降级，不拖垮首屏。
     */
    private fun enrichAlbumSongCounts(albums: List<QQMusicAlbum>) {
        albumCountJob?.cancel()
        if (albums.isEmpty()) return
        albumCountJob = viewModelScope.launch {
            albums.chunked(ALBUM_COUNT_BATCH).forEach { chunk ->
                val counts = chunk.map { album ->
                    async(ioDispatcher) {
                        runCatching { repository.albumSongCount(album.mid) }.getOrNull()
                    }
                }.awaitAll()
                _uiState.update { current ->
                    val enrichedAlbums = current.qqAlbums.toMutableList()
                    chunk.forEachIndexed { index, album ->
                        val count = counts.getOrNull(index) ?: return@forEachIndexed
                        val pos = enrichedAlbums.indexOfFirst { it.mid == album.mid }
                        if (pos >= 0) {
                            enrichedAlbums[pos] = enrichedAlbums[pos].copy(songCount = count)
                        }
                    }
                    current.copy(
                        qqAlbums = enrichedAlbums,
                        albums = enrichedAlbums.map { it.toAlbumSummary() }
                    )
                }
            }
        }
    }

    /** 滚动到底时调用；内部做去重与 total 收敛。 */
    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore) return
        if (state.tracks.size >= state.total) return
        if (moreJob?.isActive == true) return
        moreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            runCatching {
                withContext(ioDispatcher) {
                    repository.singerSongs(singerMid, page = page + 1)
                }
            }.onSuccess { result ->
                val existing = _uiState.value.tracks.mapTo(mutableSetOf()) { it.songMid }
                val fresh = result.tracks.filter { existing.add(it.songMid) }
                page += 1
                _uiState.update { current ->
                    if (fresh.isEmpty()) {
                        // 服务端没有更多可去重的新歌：把 total 收敛到当前数量，避免反复请求
                        current.copy(loadingMore = false, total = current.tracks.size)
                    } else {
                        current.copy(
                            loadingMore = false,
                            tracks = current.tracks + fresh,
                            total = result.total.coerceAtLeast(current.tracks.size + fresh.size)
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

    fun setMessage(message: String?) {
        _uiState.update { it.copy(message = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private companion object {
        /** 每批并发补数的专辑数：太大易触发风控，太小则补数慢。 */
        const val ALBUM_COUNT_BATCH = 3
    }
}

/** QQ 专辑 → 统一专辑摘要，与网易云共用同一套专辑行组件。 */
private fun QQMusicAlbum.toAlbumSummary(): AlbumSummary = AlbumSummary(
    id = albumId,
    name = title,
    picUrl = coverUrl.orEmpty(),
    size = songCount
)
