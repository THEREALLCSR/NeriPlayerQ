package moe.ouom.neriplayer.platform.qqmusic.library

import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.auth.qqMusicHash33
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `musicu.fcg` 的 `comm` 信封契约。
 *
 * 为什么单独测这一段：`comm` 是**读接口（歌单/搜索/歌词）和写接口共用的**。
 * 一旦把 App 写凭据（`authst/sid/wid`）无条件塞进去，读写两条链路会同时改变请求形态；
 * 若服务端对"带 App 凭据的 Web 请求"做风控，故障面是**整条 QQ 读链路**，
 * 而且表现为"昨天还好、今天全挂"，极难归因。
 *
 * 这里锁死三条：
 *  1. 只读会话（无 `authst`）的 `comm` 必须与旧版本**逐字段一致**；
 *  2. 可写会话才追加写凭据；
 *  3. `musicid` 缺失时退回 `uin`，不能发出空 musicid。
 */
class QQMusicMusicuCommTest {

    @Test
    fun `reader session keeps the legacy envelope without write credentials`() {
        val comm = qqMusicComm(readerSession())

        assertEquals(24, comm.getInt("ct"))
        assertEquals(4_747_474, comm.getInt("cv"))
        assertEquals("yqq.json", comm.getString("platform"))
        assertEquals("0", comm.getString("chid"))
        assertEquals(2_894_378_037L, comm.getLong("uin"))
        assertEquals("json", comm.getString("format"))
        assertEquals("utf-8", comm.getString("inCharset"))
        assertEquals("utf-8", comm.getString("outCharset"))
        assertEquals(0, comm.getInt("notice"))
        assertEquals(1, comm.getInt("need_new_code"))

        // 关键断言：只读会话绝不能带出 App 凭据
        assertFalse("reader comm must not carry authst", comm.has("authst"))
        assertFalse("reader comm must not carry sid", comm.has("sid"))
        assertFalse("reader comm must not carry wid", comm.has("wid"))
        assertFalse("reader comm must not carry musicid", comm.has("musicid"))
        assertFalse("reader comm must not carry qq", comm.has("qq"))
    }

    @Test
    fun `g_tk is derived from musicKey and mirrored to the 2020 field`() {
        val session = readerSession()
        val comm = qqMusicComm(session)
        val expected = qqMusicHash33(session.musicKey)

        assertEquals(expected, comm.getLong("g_tk"))
        assertEquals(expected, comm.getLong("g_tk_new_20200303"))
    }

    @Test
    fun `writable session appends app credentials`() {
        val comm = qqMusicComm(
            readerSession().copy(
                authst = "Q_H_L_abc",
                musicId = "2894378037",
                sid = "sid-value",
                wid = "wid-value"
            )
        )

        assertEquals("Q_H_L_abc", comm.getString("authst"))
        assertEquals("2894378037", comm.getString("qq"))
        assertEquals("2894378037", comm.getString("musicid"))
        assertEquals("sid-value", comm.getString("sid"))
        assertEquals("wid-value", comm.getString("wid"))
    }

    @Test
    fun `writable session falls back to uin for musicid and skips blank optional ids`() {
        val comm = qqMusicComm(
            readerSession().copy(authst = "Q_H_L_abc", musicId = "", sid = "", wid = "")
        )

        assertTrue(comm.has("authst"))
        assertEquals("2894378037", comm.getString("musicid"))
        assertFalse("blank sid must not be sent", comm.has("sid"))
        assertFalse("blank wid must not be sent", comm.has("wid"))
    }

    @Test
    fun `anonymous session sends zero uin instead of crashing`() {
        // 未登录时 uin 可能是空串：不能再抛 NumberFormatException 把整条链路打断。
        val comm = qqMusicComm(QQMusicSession(cookie = "", uin = "", musicKey = ""))

        assertEquals(0L, comm.getLong("uin"))
        // 空 musicKey 时 hash33 返回 djb2 种子值 5381（历史行为，非零但无害），
        // 这里锁住它只是为了"哪天它变成崩溃/变 0"能被发现。
        assertEquals(5381L, comm.getLong("g_tk"))
        assertFalse(comm.has("authst"))
    }

    @Test
    fun `musicu payload wraps comm and request descriptors without leaking them across`() {
        val session = readerSession()
        val payload = qqMusicPayload(
            session = session,
            module = "music.musicasset.PlaylistBaseRead",
            method = "GetPlaylistByUin",
            param = JSONObject().put("uin", 2_894_378_037L)
        )

        assertEquals(
            qqMusicComm(session).toString(),
            payload.getJSONObject("comm").toString()
        )
        val request = payload.getJSONObject("req_0")
        assertEquals("music.musicasset.PlaylistBaseRead", request.getString("module"))
        assertEquals("GetPlaylistByUin", request.getString("method"))
        assertEquals(2_894_378_037L, request.getJSONObject("param").getLong("uin"))
    }

    private fun readerSession() = QQMusicSession(
        cookie = "uin=2894378037; qm_keyst=W_X",
        uin = "2894378037",
        musicKey = "W_X"
    )
}
