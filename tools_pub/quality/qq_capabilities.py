"""QQ 音乐能力真值源（machine-readable single source of truth）。

`README.qqmusic.md` 第 0 节的能力表是给人看的快照；本文件是给机器校验的真值。
`test_qq_capabilities.py` 保证两者不漂移，并校验每个 ✅ 能力有真实代码入口、
README 引用的证据文件存在。

状态符号与 README 一致：✅ 可用 / ❌ 不可用 / ⚠️ 部分验证。
每个能力的 code_entries 都是相对仓库根的路径，必须真实存在。
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Capability:
    status: str
    code_entries: tuple = field(default_factory=tuple)
    note: str = ""


# 键名必须与 README 第 0 节能力表第一列完全一致（不含 ** 加粗标记）
CAPABILITIES = {
    "登录（扫码）": Capability(
        "✅",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicQrLoginClient.kt",),
        "Web 会话，保存 uin/qm_keyst",
    ),
    "歌单读取": Capability(
        "✅",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/library/QQMusicLibraryRepository.kt",),
        "GetPlaylistByUin / CgiGetDiss",
    ),
    "评论读取": Capability(
        "✅",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/comments/QQMusicCommentRepository.kt",),
        "fcg_global_comment_h5.fcg；只读",
    ),
    "搜索 / 专辑 / 歌手": Capability(
        "✅",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/search/api/client/QQMusicSearchApi.kt",),
        "只读；2001 = 登录过期（已分类）",
    ),
    "播放 / 下载（VKey）": Capability(
        "✅",
        (
            "modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/playback/QQMusicPlaybackRepository.kt",
            "modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/player/download/source/AudioDownloadSourceResolver.kt",
        ),
        "受账号权益与音质限制；104009/invalidq 需稳定 guid",
    ),
    "每日推荐 / 私人雷达": Capability(
        "✅",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/library/QQMusicLibraryRepository.kt",),
        "只读",
    ),
    "歌单写入（新建/改名/删除/加歌/删歌）": Capability(
        "✅",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/library/QQMusicLibraryRepository.kt",),
        "需有效 Web 登录态；旧 Cookie 过期返回 code=1000",
    ),
    "评论写入（点赞/发表/回复）": Capability(
        "❌",
        (),
        "Web 会话 10000；App authst 点赞可成、发表 500026；发表需 WASM 加密，已决定放弃",
    ),
    "App 会话（`authst/sid/wid`）": Capability(
        "⚠️",
        ("modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/auth/QQMusicSessionStore.kt",),
        "仅验证：歌单写/点赞可成功；未接入产品登录流程",
    ),
    "WNS 自动登录": Capability(
        "❌",
        (),
        "authst 由 WNS 签发，HTTP 扫码登录拿不到",
    ),
}

# README 第 0 节第 5 条引用的证据文件。probe2.py 是一次性诊断脚本，已不在仓库；
# 复核结论迁移到 VERIFIED_CAPABILITIES.md，由本真值源 + 测试保证不漂移。
EVIDENCE_FILES = (
    "tools/qq-probe/VERIFIED_CAPABILITIES.md",
)

# 错误码 → 语义映射的代码位置，供冒烟失败时定位（不是能力项）
ERROR_CODE_SOURCE = "modules/platform/src/main/java/moe/ouom/neriplayer/platform/qqmusic/network/QQMusicFailure.kt"
