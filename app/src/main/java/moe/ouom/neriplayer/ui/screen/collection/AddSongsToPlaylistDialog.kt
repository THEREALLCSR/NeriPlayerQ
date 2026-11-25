package moe.ouom.neriplayer.ui.screen.collection

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
 * File: moe.ouom.neriplayer.ui.screen.collection/AddSongsToPlaylistDialog
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicSources
import moe.ouom.neriplayer.platform.qqmusic.library.QQMusicPlaylist
import org.json.JSONArray
import org.json.JSONObject

private const val ITEM_CHUNK = 20

/**
 * 通用"把歌曲添加到歌单"对话框（单页 / 多选都可用）。
 *
 * - QQ 歌曲（channelId = qqmusic）：列出我的 QQ 歌单，按 20 首一批添加；
 * - 其它歌曲（网易云）：列出我的网易云歌单，调用 `addSongsToPlaylist` 批量添加。
 */
@Composable
internal fun AddSongsToPlaylistDialog(
    songs: List<SongItem>,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val isQq = songs.firstOrNull()?.let(MusicSources::isQqMusic) == true
    var loading by remember(songs) { mutableStateOf(true) }
    var adding by remember(songs) { mutableStateOf(false) }
    var message by remember(songs) { mutableStateOf<String?>(null) }
    var qqTargets by remember(songs) { mutableStateOf<List<QQMusicPlaylist>>(emptyList()) }
    var neteaseTargets by remember(songs) { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }

    LaunchedEffect(songs) {
        loading = true
        if (isQq) {
            qqTargets = runCatching {
                withContext(Dispatchers.IO) { AppContainer.qqMusicLibraryRepository.userPlaylists() }
            }.getOrDefault(emptyList())
        } else {
            neteaseTargets = runCatching {
                withContext(Dispatchers.IO) {
                    val client = AppContainer.neteaseClient
                    val userId = client.getCurrentUserId()
                    if (userId <= 0L) emptyList() else {
                        val json = client.getUserPlaylists(userId, limit = 100)
                        val array = JSONObject(json).optJSONArray("playlist") ?: JSONArray()
                        buildList {
                            for (index in 0 until array.length()) {
                                val item = array.optJSONObject(index) ?: continue
                                val id = item.optLong("id", 0L)
                                val name = item.optString("name")
                                if (id > 0L && name.isNotBlank()) add(id to name)
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("添加到歌单（${songs.size} 首）") },
        text = {
            Column {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }

                    adding -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }

                    message != null -> Text(message.orEmpty())

                    isQq && qqTargets.isEmpty() -> Text("没有可用的 QQ 音乐歌单（请先登录）")
                    !isQq && neteaseTargets.isEmpty() -> Text("没有可用的网易云歌单（请先登录）")

                    isQq -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(qqTargets, key = { it.id }) { target ->
                            Text(
                                text = "${target.title}（${target.trackCount} 首）",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !adding) {
                                        adding = true
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val mids = songs.mapNotNull { it.audioId }
                                                    var added = 0
                                                    mids.chunked(ITEM_CHUNK).forEach { chunk ->
                                                        added += AppContainer.qqMusicLibraryRepository
                                                            .addSongsToPlaylist(target, chunk)
                                                    }
                                                    added
                                                }
                                            }.onSuccess { count ->
                                                val text = "已添加 $count 首到「${target.title}」"
                                                onResult(text)
                                                onDismiss()
                                            }.onFailure { failure ->
                                                message = failure.message ?: "添加失败"
                                            }
                                            adding = false
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }

                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(neteaseTargets, key = { it.first }) { (id, name) ->
                            Text(
                                text = name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !adding) {
                                        adding = true
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    AppContainer.neteaseClient.addSongsToPlaylist(
                                                        playlistId = id,
                                                        songIds = songs.map { it.id }
                                                    )
                                                }
                                            }.onSuccess {
                                                val text = "已添加 ${songs.size} 首到「$name」"
                                                onResult(text)
                                                onDismiss()
                                            }.onFailure { failure ->
                                                message = failure.message ?: "添加失败"
                                            }
                                            adding = false
                                        }
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
