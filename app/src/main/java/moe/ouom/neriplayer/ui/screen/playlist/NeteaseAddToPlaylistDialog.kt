package moe.ouom.neriplayer.ui.screen.playlist

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
 * File: moe.ouom.neriplayer.ui.screen.playlist/NeteaseAddToPlaylistDialog
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云"添加到歌单"弹窗。
 *
 * 与 QQ 侧的交互保持一致：列表展示我的网易云歌单，点一个即把当前歌曲加入。
 * 接口：`NeteaseClient.addSongsToPlaylist(playlistId, songIds)`
 * （内部走 `/playlist/manipulate/tracks`，需要登录态与 CSRF）。
 */
@Composable
internal fun NeteaseAddToPlaylistDialog(
    song: SongItem,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(song.id) { mutableStateOf(true) }
    var playlists by remember(song.id) { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var adding by remember(song.id) { mutableStateOf(false) }
    var message by remember(song.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(song.id) {
        loading = true
        playlists = runCatching {
            val client = AppContainer.neteaseClient
            val userId = withContext(Dispatchers.IO) { client.getCurrentUserId() }
            if (userId <= 0L) return@runCatching emptyList()
            val json = withContext(Dispatchers.IO) { client.getUserPlaylists(userId, limit = 100) }
            val array = JSONObject(json).optJSONArray("playlist") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optLong("id", 0L)
                    if (id <= 0L) continue
                    add(id to item.optString("name"))
                }
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("添加到网易云歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                message?.let { text ->
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    playlists.isEmpty() -> Text(
                        "没有可用的网易云歌单（请先登录）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(playlists, key = { it.first }) { (id, name) ->
                            ListItem(
                                headlineContent = {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                modifier = Modifier.clickable {
                                    if (adding) return@clickable
                                    adding = true
                                    message = null
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                AppContainer.neteaseClient.addSongsToPlaylist(
                                                    playlistId = id,
                                                    songIds = listOf(song.id)
                                                )
                                            }
                                        }.onSuccess {
                                            message = "已添加到「$name」"
                                        }.onFailure { error ->
                                            message = error.message ?: "添加失败"
                                        }
                                        adding = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
