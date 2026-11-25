package moe.ouom.neriplayer.platform.qqmusic.library

import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicSession
import moe.ouom.neriplayer.platform.qqmusic.auth.qqMusicHash33
import org.json.JSONObject

/**
 * `musicu.fcg` 请求信封的**唯一**构造点。
 *
 * 单独拆出来只为一件事：让"读接口会不会被写凭据污染"这件事可被测试。
 * 之前这段逻辑内联在 `QQMusicLibraryRepository.musicuPayload()` 里，
 * 加写凭据时没有任何用例能看见"读请求也变了"。
 *
 * 契约见 `QQMusicMusicuCommTest`：
 *  - 只读会话（无 `authst`）不携带任何 App 凭据；
 *  - 可写会话才追加 `authst/qq/musicid/wid/sid`；
 *  - `musicid` 缺失退回 `uin`；空 `sid/wid` 不下发。
 */
internal fun qqMusicComm(session: QQMusicSession): JSONObject {
    val gtk = qqMusicHash33(session.musicKey)
    val comm = JSONObject()
        .put("ct", 24)
        .put("cv", 4_747_474)
        .put("platform", "yqq.json")
        .put("chid", "0")
        .put("uin", session.uin.toLongOrNull() ?: 0L)
        .put("g_tk", gtk)
        .put("g_tk_new_20200303", gtk)
        .put("format", "json")
        .put("inCharset", "utf-8")
        .put("outCharset", "utf-8")
        .put("notice", 0)
        .put("need_new_code", 1)
    // 写接口（评论/歌单）需要 App 登录凭据；只读接口不带，避免改变读链路的请求形态。
    if (session.canWrite) {
        comm.put("authst", session.authst)
        comm.put("qq", session.uin)
        comm.put("musicid", session.musicId.ifBlank { session.uin })
        if (session.wid.isNotBlank()) comm.put("wid", session.wid)
        if (session.sid.isNotBlank()) comm.put("sid", session.sid)
    }
    return comm
}

/** 组装完整请求体：`comm` + `req_0{module, method, param}`。 */
internal fun qqMusicPayload(
    session: QQMusicSession,
    module: String,
    method: String,
    param: JSONObject
): JSONObject = JSONObject()
    .put("comm", qqMusicComm(session))
    .put(
        "req_0",
        JSONObject()
            .put("module", module)
            .put("method", method)
            .put("param", param)
    )
