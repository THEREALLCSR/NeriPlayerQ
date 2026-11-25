"""四音源能力矩阵真值源（machine-readable）。

用途：
- 对照 QQ / 网易云 / B站 / YouTube 在 platform 层对外暴露的 API 能力；
- 标出「QQ ↔ 网易云彼此缺失」的能力，并区分：
    - alignable=True  ：客户端 API 方法缺失，但服务端接口存在 / 其它音源已实现，可补齐；
    - alignable=False ：平台固有限制或已决策放弃（如 QQ 评论发表需 WASM 加密），不应强行对齐。

状态符号：
    ✅ 有完整客户端 API 且已接入使用
    ⚠️ 部分（有代码但未接入 UI / 有桩不可用 / 仅本地实现替代）
    ❌ 服务端不支持或已决策放弃
    —  客户端完全没有该能力

本文件是矩阵的真值；人读对照见 `接手/19-四音源能力矩阵与对齐清单.md`。
状态基于 2026-11-23 源码静态盘点；`—`/`⚠️` 的「可对齐」判断以其它音源已有实现或协议文档为依据，
动手前仍需真机/协议证据确认，不得仅凭矩阵就虚构实现。

本文件与 tools_pub/quality/qq_capabilities.py 并存：后者服务于 README.qqmusic.md §0 的
「QQ 能力今日是否可用」人读快照；本文件服务于跨音源能力对齐规划。二者口径不同，不互相取代。
"""

from dataclasses import dataclass, field

SOURCES = ("qqmusic", "netease", "bilibili", "youtube")


@dataclass(frozen=True)
class Cell:
    """某音源在某能力上的状态。"""
    status: str
    entry: str = ""          # 代码入口（相对仓库根），空表示无
    note: str = ""


@dataclass(frozen=True)
class Capability:
    """一个能力维度下的具体能力。"""
    dimension: str
    name: str
    cells: dict = field(default_factory=dict)   # source -> Cell
    alignable: bool = False                      # 是否可通过补客户端 API 对齐
    align_target: str = ""                       # 对齐参照（哪个源已有此能力）
    note: str = ""                               # 可对齐性判断的依据 / 平台限制说明


# 能力矩阵：QQ ↔ 网易云为主线，B站/YouTube 作为参考列（— 表示本次未详查）
MATRIX = (
    # ── 登录 ──────────────────────────────────────────────────────────────────
    Capability("登录", "扫码登录", {
        "qqmusic": Cell("✅", "platform/qqmusic/auth/QQMusicQrLoginClient.kt"),
        "netease": Cell("✅", "platform/netease/api/auth/NeteaseQrLoginClient.kt"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("登录", "登录态持久化", {
        "qqmusic": Cell("✅", "platform/qqmusic/auth/QQMusicSessionStore.kt"),
        "netease": Cell("✅", "platform/netease/auth/NeteaseCookieRepository.kt", "EncryptedSharedPreferences"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("登录", "过期检测", {
        "qqmusic": Cell("✅", "platform/qqmusic/auth/QQMusicSessionRefresher.kt", "20h 阈值"),
        "netease": Cell("⚠️", "platform/netease/auth/NeteaseCookieRepository.kt", "本地启发式，非服务端探测"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("登录", "自动续签", {
        "qqmusic": Cell("✅", "platform/qqmusic/auth/QQMusicSessionAutoRefresh.kt", "Mutex 串行+失败降级"),
        "netease": Cell("—", "", "网易云无对应编排；仅扫码确认时用 refreshToken 兜底"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="qqmusic", note="网易云用本地启发式判断过期，可参照 QQ 的 20h 续签编排补自动续签"),

    # ── 歌单读 ────────────────────────────────────────────────────────────────
    Capability("歌单读", "账号歌单列表", {
        "qqmusic": Cell("✅", "platform/qqmusic/library/QQMusicLibraryRepository.kt:100 userPlaylists"),
        "netease": Cell("✅", "platform/netease/api/client/NeteaseClient.kt:907 getUserPlaylists"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌单读", "歌单详情", {
        "qqmusic": Cell("✅", "platform/qqmusic/library/QQMusicLibraryRepository.kt:172 playlistTracksPage"),
        "netease": Cell("✅", "platform/netease/api/client/NeteaseClient.kt:1023 getPlaylistDetail"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌单读", "歌曲分页", {
        "qqmusic": Cell("✅", "playlistTracksPage(dissid,begin,limit)"),
        "netease": Cell("⚠️", "NeteaseClient.kt:1023", "一次全量拉 n=100000，无分页端点"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target="qqmusic"),
    Capability("歌单读", "歌单内搜索-服务端", {
        "qqmusic": Cell("—", "", "仅全局 searchPlaylists，无歌单内歌曲搜索端点"),
        "netease": Cell("⚠️", "app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/NeteaseLibrarySearch.kt", "本地过滤已缓存列表，非远端搜索"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target=""),

    # ── 歌单写 ────────────────────────────────────────────────────────────────
    Capability("歌单写", "新建/改名/删除歌单", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:219/386/439"),
        "netease": Cell("—", "", "无 playlist/create|update|delete 端点"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target="qqmusic", note="网易云 WeAPI 有 /playlist/create 等端点，但客户端未封装；需协议证据后才可判定 alignable"),
    Capability("歌单写", "加歌", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:265 addSongsToPlaylist"),
        "netease": Cell("✅", "NeteaseClient.kt:1055 addSongsToPlaylist"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌单写", "删歌/从歌单移除", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:336 removeSongsFromPlaylist"),
        "netease": Cell("—", "", "NeteasePlaylistTrackParams 只构造 add 参数"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="qqmusic", note="WeAPI /playlist/manipulate/tracks 同端点理论上可带 op=del，但仓库内无 del 先例、未抓包证实；动手前先写参数构造纯函数测试，网络契约需真机证据"),
    Capability("歌单写", "收藏歌单", {
        "qqmusic": Cell("—", "", "无独立方法，UI 用 create+add 组合"),
        "netease": Cell("—", "", "仅读 getUserSubscribedPlaylists，无 sub/unsub 写端点"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target=""),
    Capability("歌单写", "导出到本地歌单", {
        "qqmusic": Cell("⚠️", "", "QQ 歌单页无导出按钮"),
        "netease": Cell("✅", "app/src/main/java/moe/ouom/neriplayer/ui/screen/playlist/NeteaseCollectionDetailScreen.kt", "本地落库"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="导出是本地数据层能力，与音源无关，纯 UI 搬运"),

    # ── 歌曲 ──────────────────────────────────────────────────────────────────
    Capability("歌曲", "关键词搜索", {
        "qqmusic": Cell("✅", "platform/search/api/client/QQMusicSearchApi.kt:75"),
        "netease": Cell("✅", "NeteaseClient.kt:785 searchSongs"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌曲", "歌词-翻译", {
        "qqmusic": Cell("✅", "QQMusicSearchApi.kt:268 getNativeLyrics"),
        "netease": Cell("✅", "NeteaseClient.kt:1349 getLyricNew(tv=1)"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌曲", "歌词-音译", {
        "qqmusic": Cell("—", "", "仅普通翻译，无罗马音"),
        "netease": Cell("✅", "NeteaseClient.kt:1349 getLyricNew(ytv=1) + NeteaseRomanizedFallback"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target="netease", note="QQ 服务端是否提供罗马音字段未知，需协议证据"),
    Capability("歌曲", "播放地址解析", {
        "qqmusic": Cell("✅", "QQMusicPlaybackRepository.kt:135 resolve"),
        "netease": Cell("✅", "NeteaseClient.kt:864 getSongUrl"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌曲", "音质档位", {
        "qqmusic": Cell("✅", "QQMusicPlaybackRepository.kt:402 qqQualityCandidates"),
        "netease": Cell("✅", "NeteaseClient.kt:834 getSongDownloadUrl(level)"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌曲", "收藏/喜欢", {
        "qqmusic": Cell("⚠️", "platform/qqmusic/library/QQMusicLibraryRepository.kt likedSongs", "第013次补齐「我喜欢」读取(dirid=201)；红心写入未做"),
        "netease": Cell("✅", "NeteaseClient.kt:1290 likeSong"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="读取已补(B+证据，dirid=201 多源一致)；写入(红心 AddSonglist/DelSonglist dirId=201)仅 B 级无真机验证，按 D6 不虚构实现，待真机抓包确认签名后再做"),

    # ── 歌手 ──────────────────────────────────────────────────────────────────
    Capability("歌手", "歌手搜索/歌曲列表", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:543/566"),
        "netease": Cell("✅", "NeteaseClient.kt:989 getArtistSongs"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("歌手", "歌手详情/百科", {
        "qqmusic": Cell("✅", "platform/qqmusic/library/QQMusicLibraryRepository.kt singerDetail", "第014次补齐简介；别名仍缺(C级证据)未做"),
        "netease": Cell("✅", "NeteaseClient.kt:1538 getArtistInfoCancellable + encyclopedia/"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="第014次补齐 web_singer_info_svr/get_singer_detail_info（A级证据）；module/method 与 singer_brief 字段 A 级，param 字段名按惯例待真机；别名无字段证据仍不做"),
    Capability("歌手", "歌手专辑列表", {
        "qqmusic": Cell("✅", "platform/qqmusic/library/QQMusicLibraryRepository.kt singerAlbums", "第014次补齐 AlbumListServer/GetAlbumList"),
        "netease": Cell("✅", "NeteaseClient.kt:1008 getArtistAlbums"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="第014次补齐 musichallAlbum.AlbumListServer/GetAlbumList（A级证据）；param 字段名按惯例待真机"),
    Capability("歌手", "关注歌手", {
        "qqmusic": Cell("—"),
        "netease": Cell("⚠️", "app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/artist/NeteaseArtistDetailViewModel.kt", "本地收藏替代，无 artist/sub 写端点"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target=""),

    # ── 专辑 ──────────────────────────────────────────────────────────────────
    Capability("专辑", "专辑搜索/详情/歌曲", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:481 searchAlbums/508 albumTracksPage"),
        "netease": Cell("✅", "NeteaseClient.kt:945 getAlbumDetail"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("专辑", "收藏专辑", {
        "qqmusic": Cell("—"),
        "netease": Cell("⚠️", "NeteaseClient.kt:1185 getUserStaredAlbums", "仅读，无 album/sub 写端点"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target=""),

    # ── 评论 ──────────────────────────────────────────────────────────────────
    Capability("评论", "评论读取", {
        "qqmusic": Cell("✅", "QQMusicCommentRepository.kt:56 loadComments"),
        "netease": Cell("✅", "NeteaseClient.kt:1382 getSongCommentsCancellable"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("评论", "评论点赞", {
        "qqmusic": Cell("⚠️", "QQMusicCommentRepository.kt:155 setLiked", "App authst 可成，未接入产品流程"),
        "netease": Cell("✅", "NeteaseClient.kt:1413 setSongCommentLiked"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="QQ 有 authst 点赞能力，缺的是接入产品登录态"),
    Capability("评论", "评论发表/回复", {
        "qqmusic": Cell("❌", "", "发表需 WASM 加密，已决策放弃"),
        "netease": Cell("✅", "NeteaseClient.kt:1457 sendSongComment"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=False, align_target="netease", note="QQ 服务端需 WASM 加密，纯 Kotlin 不可低成本复刻；已决策放弃，不对齐"),

    # ── 推荐 / 其它 ───────────────────────────────────────────────────────────
    Capability("推荐", "每日推荐", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:603 dailyRecommendSongs"),
        "netease": Cell("✅", "NeteaseClient.kt:740 getDailyRecommendedSongs"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("推荐", "私人雷达", {
        "qqmusic": Cell("✅", "QQMusicLibraryRepository.kt:631 personalRadarTracks"),
        "netease": Cell("✅", "NeteaseClient.kt:1048 getRadarPlaylistMetadataCancellable"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }),
    Capability("推荐", "排行榜", {
        "qqmusic": Cell("✅", "platform/qqmusic/ranking/QQMusicRankingRepository.kt", "第012次补齐 GetAll/GetDetail，A级三源交叉证据"),
        "netease": Cell("✅", "NeteaseClient.kt:767 getTopPlaylists"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="第012次补齐 music.musicToplist.Toplist GetAll/GetDetail（A级：ylw1997+W_PlatformMusicApi+Go music-lib 三源交叉）；公开只读无需登录，真机待验证"),
    Capability("其它", "分享链接解析", {
        "qqmusic": Cell("—"),
        "netease": Cell("—"),
        "bilibili": Cell("—"), "youtube": Cell("✅", "app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/ExploreLinkRecognizer.kt"),
    }, alignable=True, align_target="youtube", note="QQ/网易云分享链接解析各音源独立实现，可参照 YouTube 的识别器补齐"),
    Capability("其它", "用户资料(头像/昵称)", {
        "qqmusic": Cell("✅", "platform/qqmusic/profile/QQMusicProfileRepository.kt", "第011次补齐；端点移植 QM-Download v0.6 已验证实现"),
        "netease": Cell("✅", "NeteaseClient.kt:1329 getCurrentUserAccount"),
        "bilibili": Cell("—"), "youtube": Cell("—"),
    }, alignable=True, align_target="netease", note="第011次已补齐 QQ 侧客户端 API；端点真机可用性仍待验证"),
)


def cells_for(source: str):
    """返回某音源的全部能力状态，供测试与文档生成复用。"""
    if source not in SOURCES:
        raise KeyError(f"unknown source: {source}")
    return [(c.dimension, c.name, c.cells.get(source, Cell("—"))) for c in MATRIX]


def gaps(source_a: str, source_b: str, alignable_only: bool = True):
    """返回 source_a 相对 source_b 缺失的能力（a 为 —/⚠️ 且 b 为 ✅）。"""
    out = []
    for c in MATRIX:
        ca = c.cells.get(source_a, Cell("—"))
        cb = c.cells.get(source_b, Cell("—"))
        a_missing = ca.status in ("—", "⚠️")
        if a_missing and cb.status == "✅":
            if alignable_only and not c.alignable:
                continue
            out.append(c)
    return out


if __name__ == "__main__":
    print(f"四音源能力矩阵：{len(MATRIX)} 项能力 × {len(SOURCES)} 音源")
    for source in SOURCES:
        states = [cell.status for _, _, cell in cells_for(source)]
        print(f"  {source}: ✅{states.count('✅')} ⚠️{states.count('⚠️')} ❌{states.count('❌')} —{states.count('—')}")
    for a, b in (("qqmusic", "netease"), ("netease", "qqmusic")):
        g = gaps(a, b)
        print(f"\n{b} 有而 {a} 缺（可对齐 {len(g)} 项）：")
        for c in g:
            print(f"  [{c.dimension}] {c.name}  ← {c.align_target}")
