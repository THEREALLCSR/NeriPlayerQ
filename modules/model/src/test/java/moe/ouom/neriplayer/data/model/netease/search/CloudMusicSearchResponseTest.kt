package moe.ouom.neriplayer.data.model.netease.search

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 网易云搜索响应的容错契约。
 *
 * 背景（真机日志实测）：未登录/风控状态下网易云返回 {"code":-460,"msg":"..."} 这类
 * 不含 result 的错误体。此前 `result` 声明成"可空但必填"，kotlinx 会抛
 * MissingFieldException，把"没有搜索结果"变成异常，调用方只能靠 catch 兜底
 * （日志里表现为 `Editable lyric query failed ... Field 'result' is required`）。
 */
class CloudMusicSearchResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `error payload without result decodes to empty instead of throwing`() {
        val response = json.decodeFromString<CloudMusicSearchResponse>(
            """{"code":-460,"msg":"Cheating"}"""
        )
        assertNull(response.result)
    }

    @Test
    fun `success payload still decodes songs`() {
        val response = json.decodeFromString<CloudMusicSearchResponse>(
            """{"code":200,"result":{"songs":[{"id":42,"name":"Signal","dt":185000,
               "ar":[{"name":"A"}],"al":{"name":"Album","picUrl":null}}]}}"""
        )
        assertEquals(42L, response.result?.songs?.single()?.id)
        assertEquals("Signal", response.result?.songs?.single()?.name)
    }

    @Test
    fun `result present but songs missing decodes to no songs`() {
        val response = json.decodeFromString<CloudMusicSearchResponse>("""{"code":200,"result":{}}""")
        assertNull(response.result?.songs)
    }
}
