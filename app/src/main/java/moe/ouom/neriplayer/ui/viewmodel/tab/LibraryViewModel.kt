package moe.ouom.neriplayer.ui.viewmodel.tab

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/LibraryViewModel
 * Created: 2025/8/11
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.identity.toPlaybackSongItem
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLibraryPlaylist
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicPlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistDeleteResult
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject

private const val BILI_DETAIL_BATCH_SIZE = 6
private const val BILI_RESOURCE_TYPE_COLLECTION = 21
private const val QQ_PLAY_COUNT_BATCH = 3

/** 媒体库页面 UI 状态 */
data class LibraryUiState(
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val neteasePlaylists: List<PlaylistSummary> = emptyList(),
    val neteaseAlbums: List<AlbumSummary> = emptyList(),
    val neteaseError: String? = null,
    val youtubeMusicPlaylists: List<YouTubeMusicPlaylist> = emptyList(),
    val youtubeMusicError: String? = null,
    val biliPlaylists: List<BiliPlaylist> = emptyList(),
    val biliError: String? = null,
    val neteaseRefreshing: Boolean = false,
    val youtubeMusicRefreshing: Boolean = false,
    val biliRefreshing: Boolean = false,
    // QQ 音乐（本地 fork）：刷新状态放进 ViewModel，调用点与网易云一致
    val qqMusicPlaylists: List<moe.ouom.neriplayer.platform.qqmusic.library.QQMusicPlaylist> = emptyList(),
    val qqMusicError: String? = null,
    val qqMusicRefreshing: Boolean = false
)

@Suppress("unused")
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val localRepo = LocalPlaylistRepository.getInstance(application)

    private val neteaseCookieRepo = AppContainer.neteaseCookieRepo
    private val neteaseClient = AppContainer.neteaseClient

    private val biliCookieRepo = AppContainer.biliCookieRepo
    private val biliClient = AppContainer.biliClient
    private val youtubeAuthRepo = AppContainer.youtubeAuthRepo
    private var qqMusicJob: kotlinx.coroutines.Job? = null
    private var qqPlayCountJob: Job? = null
    /** 播放量补数缓存：dissid → playCount（>0），避免反复进出媒体库重复请求。 */
    private val qqPlayCountCache = mutableMapOf<String, Long>()


    private val _uiState = MutableStateFlow(
        LibraryUiState(localPlaylists = localRepo.playlists.value)
    )
    val uiState: StateFlow<LibraryUiState> = _uiState
    private var lastYouTubeAuthFingerprint: String? = null
    private var youtubeEnabled = YouTubeFeatureGate.isEnabled()
    private var lastObservedYouTubeEnabled: Boolean? = null
    private val neteaseRefreshOwner = LibraryNeteaseRefreshOwner(
        scope = viewModelScope,
        state = _uiState,
        loadUserId = { withContext(Dispatchers.IO) { neteaseClient.getCurrentUserId() } },
        loadPlaylists = { userId ->
            withContext(Dispatchers.IO) {
                parseNeteaseLibraryPlaylists(neteaseClient.getUserPlaylists(userId))
            }
        },
        loadAlbums = { userId ->
            withContext(Dispatchers.IO) {
                parseNeteaseLibraryAlbums(neteaseClient.getUserStaredAlbums(userId))
            }
        }
    )
    private val biliRefreshOwner = LibraryBilibiliRefreshOwner(
        scope = viewModelScope,
        state = _uiState,
        currentCookies = biliCookieRepo::getCookiesOnce,
        load = ::loadBilibiliPlaylists
    )
    private val youtubeRefreshOwner = LibraryYouTubeRefreshOwner(
        scope = viewModelScope,
        state = _uiState,
        isEnabled = { youtubeEnabled },
        load = ::loadYouTubeMusicPlaylists
    )

    init {
        // 本地歌单：把"已下载"的歌曲也并入"本地文件"系统歌单。
        // 下载歌曲目前只存在于下载目录的索引里（媒体库 → 本地文件 → 已下载 能看到），
        // 但列表页的"本地文件"数量来自 LocalPlaylistRepository，所以这里做一次合并。
        viewModelScope.launch {
            if (!localRepo.awaitInitialized()) return@launch
            combine(localRepo.playlists, GlobalDownloadManager.downloadedSongs) { list, downloads ->
                if (downloads.isEmpty()) return@combine list
                val downloadedSongs = downloads.map { it.toPlaybackSongItem() }
                val existing = LocalFilesPlaylist.firstOrNull(list, getApplication())
                val seen = existing?.songs
                    ?.mapTo(mutableSetOf()) { it.stableKey() }
                    ?: mutableSetOf()
                val extra = downloadedSongs.filter { seen.add(it.stableKey()) }
                if (extra.isEmpty()) return@combine list
                val base = existing ?: LocalPlaylist(
                    id = LocalFilesPlaylist.SYSTEM_ID,
                    name = LocalFilesPlaylist.currentName(getApplication()),
                    songs = mutableListOf()
                )
                val merged = base.copy(
                    songs = (base.songs + extra).toMutableList(),
                    modifiedAt = System.currentTimeMillis()
                )
                if (existing != null) {
                    list.map { playlist -> if (playlist.id == existing.id) merged else playlist }
                } else {
                    listOf(merged) + list
                }
            }.collect { merged ->
                _uiState.value = _uiState.value.copy(localPlaylists = merged)
            }
        }

        // 网易云歌单和专辑共用一次刷新，避免各自提前结束 loading
        viewModelScope.launch {
            var observedSession: String? = null
            neteaseCookieRepo.cookieFlow.collect { cookies ->
                val nextSession = cookies["MUSIC_U"]?.takeIf { it.isNotBlank() }
                if (nextSession != observedSession || nextSession == null) {
                    neteaseRefreshOwner.cancel()
                }
                observedSession = nextSession
                if (nextSession != null) {
                    refreshNeteaseLibrary()
                } else {
                    _uiState.update {
                        it.copy(
                            neteasePlaylists = emptyList(),
                            neteaseAlbums = emptyList(),
                            neteaseError = null
                        )
                    }
                }
            }
        }

        // YouTube Music
        viewModelScope.launch {
            combine(
                youtubeAuthRepo.authFlow,
                AppContainer.settingsRepo.youtubeEnabledFlow
            ) { bundle, enabled ->
                bundle to enabled
            }.collect { (bundle, enabled) ->
                youtubeEnabled = enabled
                val nextFingerprint = bundle.buildRefreshObserverFingerprint()
                val authChanged = nextFingerprint != lastYouTubeAuthFingerprint
                val enabledChanged = enabled != lastObservedYouTubeEnabled
                if (!authChanged && !enabledChanged) {
                    return@collect
                }
                lastYouTubeAuthFingerprint = nextFingerprint
                lastObservedYouTubeEnabled = enabled
                if (!enabled || !bundle.hasYouTubeMusicCookieContext()) {
                    youtubeRefreshOwner.clear()
                } else if (authChanged) {
                    youtubeRefreshOwner.onSessionChanged()
                } else {
                    refreshYouTubeMusicPlaylists()
                }
            }
        }

        // Bilibili
        viewModelScope.launch {
            biliCookieRepo.cookieFlow.collect { cookies ->
                if (!cookies["SESSDATA"].isNullOrBlank()) {
                    refreshBilibili()
                } else {
                    biliRefreshOwner.clear()
                }
            }
        }
    }

    fun refreshBilibili() {
        biliRefreshOwner.refresh()
    }

    /** QQ 音乐：进入媒体库时懒加载歌单（与网易云一样由 ViewModel 维护状态）。 */
    fun ensureQqMusicLoaded() {
        if (_uiState.value.qqMusicPlaylists.isEmpty() && !_uiState.value.qqMusicRefreshing) {
            refreshQqMusicPlaylists()
        }
    }

    fun refreshQqMusicPlaylists() {
        if (qqMusicJob?.isActive == true) return
        qqPlayCountJob?.cancel()
        qqMusicJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(qqMusicRefreshing = true, qqMusicError = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    AppContainer.qqMusicLibraryRepository.userPlaylists()
                }
            }.onSuccess { playlists ->
                _uiState.value = _uiState.value.copy(
                    qqMusicPlaylists = playlists,
                    qqMusicRefreshing = false,
                    qqMusicError = null
                )
                enrichQqPlaylistPlayCounts(playlists)
            }.onFailure { failure ->
                _uiState.value = _uiState.value.copy(
                    qqMusicRefreshing = false,
                    qqMusicError = failure.message ?: "加载 QQ 音乐歌单失败"
                )
            }
        }
    }

    /**
     * 播放量补数：v_playlist 的 play_cnt 恒 0，用歌单详情 dirinfo.listennum（真机 146608）补；
     * 分批 + 缓存 + 降级，不拖垮媒体库列表。
     */
    private fun enrichQqPlaylistPlayCounts(playlists: List<QQMusicPlaylist>) {
        if (playlists.isEmpty()) return
        qqPlayCountJob = viewModelScope.launch {
            playlists.chunked(QQ_PLAY_COUNT_BATCH).forEach { chunk ->
                val counts = chunk.map { playlist ->
                    async(Dispatchers.IO) {
                        qqPlayCountCache[playlist.id]
                            ?: runCatching { AppContainer.qqMusicLibraryRepository.playlistPlayCount(playlist.id) }
                                .getOrNull()
                                ?.also { qqPlayCountCache[playlist.id] = it }
                    }
                }.awaitAll()
                _uiState.value = _uiState.value.copy(
                    qqMusicPlaylists = _uiState.value.qqMusicPlaylists.map { playlist ->
                        val index = chunk.indexOfFirst { it.id == playlist.id }
                        val count = if (index >= 0) counts.getOrNull(index) else null
                        if (count != null) playlist.copy(playCount = count) else playlist
                    }
                )
            }
        }
    }

    fun createQqPlaylist(name: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    AppContainer.qqMusicLibraryRepository.createPlaylist(name)
                }
            }.map { }
            onResult(result)
            if (result.isSuccess) refreshQqMusicPlaylists()
        }
    }

    private suspend fun loadBilibiliPlaylists(cookies: Map<String, String>): LibraryBilibiliLoadResult {
        val account = resolveLibraryBiliMid(
            cookies = cookies,
            fetchLoginMid = biliClient::fetchLoginMid,
            saveUserMid = biliCookieRepo::saveUserMid
        )
        val mid = when (account) {
            is LibraryBiliMid.Ready -> account.mid
            LibraryBiliMid.Persisted, LibraryBiliMid.Superseded -> return LibraryBilibiliLoadResult.Unchanged
            LibraryBiliMid.Missing -> {
                return LibraryBilibiliLoadResult.Failed(
                    getApplication<Application>().getString(CoreCommonR.string.error_get_user_id)
                )
            }
        }
        val mapped = withContext(Dispatchers.IO) {
            val created = async { biliClient.getUserCreatedFavFolders(mid) }
            val collected = async {
                runCatching { biliClient.getUserCollectedFavFolders(mid) }
                    .onFailure { error ->
                        NPLogger.w(
                            "LibraryViewModel-Bili",
                            "Failed to fetch collected fav folders",
                            error
                        )
                    }
                    .getOrDefault(emptyList())
            }

            val createdPlaylists = mapBiliFolders(
                folders = created.await(),
                kind = BiliPlaylistKind.CREATED_FAVORITE,
                currentMid = mid
            )
            val collectedPlaylists = mapBiliFolders(
                folders = collected.await(),
                kind = BiliPlaylistKind.COLLECTED_FAVORITE,
                currentMid = mid
            )

            (createdPlaylists + collectedPlaylists)
                .filter { it.mediaId != 0L && it.title.isNotBlank() }
                .distinctBy { "${it.kind}:${it.mediaId}" }
        }

        NPLogger.d("LibraryViewModel-Bili", mapped)
        return LibraryBilibiliLoadResult.Loaded(mapped)
    }

    private suspend fun mapBiliFolders(
        folders: List<FavFolder>,
        kind: BiliPlaylistKind,
        currentMid: Long
    ): List<BiliPlaylist> = coroutineScope {
        folders
            .filter { it.state == 0 && it.mediaId != 0L }
            .chunked(BILI_DETAIL_BATCH_SIZE)
            .flatMap { batch ->
                batch.map { folder ->
                    async(Dispatchers.IO) {
                        mapLibraryBiliFolder(
                            folder = folder,
                            kind = kind,
                            currentMid = currentMid,
                            onDetailFailure = ::logBiliFolderDetailFailure,
                            loadDetail = biliClient::getFavFolderInfo
                        )
                    }
                }.awaitAll()
            }
            .filterNotNull()
    }

    private fun logBiliFolderDetailFailure(error: Throwable) {
        NPLogger.e(
            "LibraryViewModel-Bili",
            getApplication<Application>().getString(CoreCommonR.string.music_get_detail_failed),
            error
        )
    }


    fun refreshNeteaseLibrary() {
        neteaseRefreshOwner.refresh()
    }

    fun refreshYouTubeMusicPlaylists() {
        youtubeRefreshOwner.refresh()
    }

    private suspend fun loadYouTubeMusicPlaylists(): List<YouTubeMusicPlaylist> {
        val playlists = withContext(Dispatchers.IO) {
            AppContainer.youtubeMusicClient.getLibraryPlaylists(
                resolveMissingTrackCounts = false
            )
        }
        return playlists.map(::mapYouTubeMusicPlaylist)
    }

    fun createLocalPlaylist(name: String) {
        launchPlaylistMutation("createLocalPlaylist") { localRepo.createPlaylist(name) }
    }

    fun addSongToFavorites(song: SongItem) {
        launchPlaylistMutation("addSongToFavorites") { localRepo.addToFavorites(song) }
    }

    fun renameLocalPlaylist(playlistId: Long, newName: String) {
        launchPlaylistMutation("renameLocalPlaylist") {
            localRepo.renamePlaylist(playlistId, newName)
        }
    }

    fun deleteLocalPlaylist(
        playlistId: Long,
        onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit = {}
    ) {
        deleteLocalPlaylists(listOf(playlistId), onResult)
    }

    fun deleteLocalPlaylists(
        playlistIds: List<Long>,
        onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(
                runLocalPlaylistMutationSafely("deleteLocalPlaylists") {
                    localRepo.deletePlaylistsWithResult(playlistIds)
                }
            )
        }
    }

    fun reorderLocalPlaylists(order: List<Long>) {
        launchPlaylistMutation("reorderLocalPlaylists") { localRepo.reorderPlaylists(order) }
    }

    private fun launchPlaylistMutation(
        operation: String,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely(operation, mutation)
        }
    }

    private fun moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle.hasYouTubeMusicCookieContext(): Boolean {
        return hasSavedAuthMaterial()
    }

    private fun mapYouTubeMusicPlaylist(
        playlist: YouTubeMusicLibraryPlaylist
    ): YouTubeMusicPlaylist {
        return YouTubeMusicPlaylist(
            browseId = playlist.browseId,
            playlistId = playlist.playlistId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            coverUrl = playlist.coverUrl,
            trackCount = playlist.trackCount ?: 0
        )
    }
}

internal class LibraryNeteaseRefreshOwner(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LibraryUiState>,
    private val loadUserId: suspend () -> Long,
    private val loadPlaylists: suspend (Long) -> List<PlaylistSummary>,
    private val loadAlbums: suspend (Long) -> List<AlbumSummary>
) {
    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true || !scope.isActive) return
        state.update { it.copy(neteaseRefreshing = true) }
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val userId = loadUserId()
                coroutineScope {
                    val playlists = async { loadResult { loadPlaylists(userId) } }
                    val albums = async { loadResult { loadAlbums(userId) } }
                    val playlistResult = playlists.await()
                    val albumResult = albums.await()
                    state.update {
                        it.copy(
                            neteasePlaylists = playlistResult.getOrDefault(it.neteasePlaylists),
                            neteaseAlbums = albumResult.getOrDefault(it.neteaseAlbums),
                            neteaseError = playlistResult.exceptionOrNull()?.message
                                ?: albumResult.exceptionOrNull()?.message
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(neteaseError = error.message) }
            } finally {
                if (refreshJob === coroutineContext[Job]) {
                    refreshJob = null
                    state.update { it.copy(neteaseRefreshing = false) }
                }
            }
        }
        refreshJob = nextJob
        nextJob.start()
    }

    fun cancel() {
        val previousJob = refreshJob
        refreshJob = null
        previousJob?.cancel()
        state.update { it.copy(neteaseRefreshing = false) }
    }

    private suspend fun <T> loadResult(load: suspend () -> T): Result<T> = try {
        Result.success(load())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

/** 合集不需要再请求收藏夹详情, 详情请求失败时退回列表里的摘要 */
internal suspend fun mapLibraryBiliFolder(
    folder: FavFolder,
    kind: BiliPlaylistKind,
    currentMid: Long,
    onDetailFailure: (Throwable) -> Unit,
    loadDetail: suspend (mediaId: Long) -> FavFolder?
): BiliPlaylist? {
    val resolvedKind = if (folder.itemType == BILI_RESOURCE_TYPE_COLLECTION) BiliPlaylistKind.COLLECTION else kind
    val detail = if (resolvedKind == BiliPlaylistKind.COLLECTION) {
        null
    } else {
        loadLibraryBiliFolderDetail(folder.mediaId, onDetailFailure, loadDetail)
    }
    val source = detail ?: folder
    val title = source.title.takeIf { it.isNotBlank() } ?: return null
    return BiliPlaylist(
        mediaId = source.mediaId,
        fid = source.fid,
        mid = source.mid,
        title = title,
        count = source.count,
        coverUrl = source.coverUrl.replace("http://", "https://"),
        kind = resolvedKind,
        subtitle = source.libraryOwnerLabel(currentMid)
    )
}

/** 详情只用于补全列表字段, 失败时记录日志并回退到列表数据 */
private suspend fun loadLibraryBiliFolderDetail(
    mediaId: Long,
    onFailure: (Throwable) -> Unit,
    loadDetail: suspend (mediaId: Long) -> FavFolder?
): FavFolder? {
    return runCatching { loadDetail(mediaId) }
        .onFailure(onFailure)
        .getOrNull()
}

private fun FavFolder.libraryOwnerLabel(currentMid: Long): String {
    return upperName.ifBlank {
        if (mid != 0L && mid != currentMid) mid.toString() else ""
    }
}

internal fun parseNeteaseLibraryPlaylists(raw: String): List<PlaylistSummary> {
    val items = neteaseLibraryItems(raw) ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        items.optJSONObject(index)?.toNeteaseLibraryPlaylist()
    }
}

internal fun parseNeteaseLibraryAlbums(raw: String): List<AlbumSummary> {
    val items = neteaseLibraryItems(raw) ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        items.optJSONObject(index)?.toNeteaseLibraryAlbum()
    }
}

private fun neteaseLibraryItems(raw: String): JSONArray? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    return root.optJSONArray("playlist")
}

private fun JSONObject.toNeteaseLibraryPlaylist(): PlaylistSummary? {
    val id = optLong("id", 0L)
    val name = optString("name", "")
    if (id == 0L || name.isBlank()) return null
    return PlaylistSummary(
        id,
        name,
        optString("coverImgUrl", "").replaceFirst("http://", "https://"),
        optLong("playCount", 0L),
        optInt("trackCount", 0)
    )
}

private fun JSONObject.toNeteaseLibraryAlbum(): AlbumSummary? {
    val dataInfo = optJSONObject("dataInfo") ?: return null
    val album = dataInfo.optJSONObject("data") ?: return null
    val id = album.optLong("id", 0L)
    val name = album.optString("name", "")
    if (id == 0L || name.isBlank()) return null
    return AlbumSummary(
        id,
        name,
        dataInfo.optString("picUrl", "").replaceFirst("http://", "https://"),
        album.optInt("size", 0)
    )
}
