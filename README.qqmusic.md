# NeriPlayer · QQ 音乐增强版（本地 Fork）

> 本仓库基于 [cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer)（GPL-3.0）进行本地增强，
> 目标是在尽量复用原作者 UI / 架构的前提下，把 **QQ 音乐** 做成与网易云音乐对等的音源。
>
> 原项目 README 见 [README.md](README.md)；本文件只记录 **本 Fork 相对原版新增/修改的内容**。

---

## 0. 2026-10-31 能力复核（重要）

> 本节记录当前真实可用状态，避免把“代码已实现”误当成“线上已通过”。

| 能力 | 当前状态 | 说明 |
|---|---|---|
| 登录（扫码） | ✅ | Web 会话，保存 `uin/qm_keyst` |
| 歌单读取 | ✅ | `GetPlaylistByUin` / `CgiGetDiss` |
| 评论读取 | ✅ | `fcg_global_comment_h5.fcg` |
| 搜索 / 专辑 / 歌手 | ✅ | 只读 |
| 播放 / 下载（VKey） | ✅ | 受账号权益与音质限制 |
| 每日推荐 / 私人雷达 | ✅ | 只读 |
| **歌单写入**（新建/改名/删除/加歌/删歌） | ✅（需有效登录态） | 2026-10-31 复核：旧 Cookie 过期时返回 `code=1000`；重新扫码登录后恢复 |
| **评论写入**（点赞/发表/回复） | ❌ | Web 会话 `10000`；带 App `authst` 时点赞成功、发表返回 `500026` |
| App 会话（`authst/sid/wid`） | ⚠️ 仅验证 | 带这些字段时歌单创建/删除、点赞可成功；尚未接入产品登录流程 |
| WNS 自动登录 | ❌ 未实现 | `authst` 由 WNS 登录签发，HTTP 扫码登录拿不到 |

**复核结论：**

1. 2026-10-31 的写歌单失败，根因是 **Cookie 过期**；重新扫码登录后新建歌单、加歌、删歌均恢复，不是代码回退，也不是服务端收回了 Web 写权限；
2. 写歌单依赖**有效的 Web 登录态**；Cookie 过期时会返回 `code=1000`，重新登录即可恢复；
3. 写接口本身仍对 **App 会话（`authst`）** 开放（带 `authst` 时可写）；
4. 当前评论保持**只读**；评论发表功能已决定放弃开发，原因为网页端使用 `musics.fcg` + WASM 加密，纯 Kotlin 难以复刻；
5. 复核证据见 `tools/qq-probe/VERIFIED_CAPABILITIES.md`；能力状态由 `tools_pub/quality/qq_capabilities.py`（机器可读真值源）与 `tools_pub/quality/test_qq_capabilities.py`（一致性校验）维护，改动须三处同步。原一次性诊断脚本 `probe2.py` 未随仓库分发。

### 0.1 本次修复与发现

- **搜索 2001 / VIP 播放 1000 / 歌单写 1000**：实测均为旧 Cookie 过期；重新扫码登录后搜索、播放、歌单写入均恢复。代码侧已将搜索 2001 的提示改为“登录状态已过期，请重新登录”。
- **VKey `104009 / invalidq`**：VKey 的 `guid` 必须是稳定设备值，随机 guid 会被拒；已改为优先 `wid`、否则 `uin`，并补齐 `vecPrefix/downloadfrom/source` 参数。
- **评论发表**：网页端使用 `musics.fcg` + `encoding=ag-1` + WASM 加密（`__cgiEncrypt/__cgiDecrypt/_getSecuritySign`），纯 Kotlin 无法低成本复刻，**决定放弃开发**，保持只读。

---

## 1. 总览

| 维度 | 原版 | 本 Fork |
|---|---|---|
| 音源 | 网易云 / 哔哩哔哩 / YouTube Music | 新增 **QQ 音乐**（登录、媒体库、播放、下载、评论、搜索、首页推荐） |
| 首页推荐 | 仅网易云 | **私人雷达 / 每日推荐** 可按卡片分别选择"网易云 / QQ 音乐" |
| 调试工具 | 网易云 / B站 / YouTube 接口调试 | 新增 **QQ 音乐接口调试** |
| 歌单集合页 | 各平台独立页面 | 抽出通用 `SongCollectionScreen`，QQ/网易云共用（多选、收藏、加歌单） |

新增提交约 **37 个**，全部带有 `qqmusic` / `home` / `collection` 等语义化前缀。

---

## 2. 新增功能

### 2.1 QQ 音乐登录

- 扫码登录（`QQMusicQrLoginClient`，四步流程）；
- 登录态持久化 `QQMusicSessionStore`（uin / qm_keyst）；
- 设置页账号入口、退出登录、启动页"未连接平台"判定已接入 QQ。

### 2.2 媒体库（QQ 音乐）

- 媒体库新增 QQ 音乐页签：
  - 顶部搜索框 + 新建歌单（复用媒体库搜索框组件）；
  - 歌单行复用 `LibraryPlaylistRow`（封面、播放量/歌曲数、离线缓存、空状态）；
  - 下拉刷新、错误重试；
  - 状态统一放到 `LibraryViewModel`（与网易云一致）。
- 歌单详情页 `QqMusicPlaylistDetailScreen`：
  - 基于 total 的分页（按请求步长推进 + 跨页去重，解决"只显示 98 首"）；
  - 播放全部 / 随机 / 加入队列 / 下载全部（顶栏图标）；
  - 长按多选：下载、加入队列、添加到其它歌单、从歌单移除；
  - 歌单内搜索（复用媒体库搜索框，吸顶展开动画）；
  - 顶栏 `TopAppBar + statusBars` 内边距，返回键不再顶到状态栏；
  - 头部封面/标题/播放量副标题与网易云详情对齐；
  - 公开歌单（无 `dirId`）支持"收藏歌单"（批量加入自己的歌单）。
- 新建歌单、加歌、移除歌曲（QQ 写接口）：**代码已实现；2026-10-31 复核确认需要有效登录态，旧 Cookie 过期时会返回 `code=1000`，重新扫码登录后恢复正常**，详见第 0 节。

### 2.3 播放与下载

- QQ 播放：`music.vkey.GetVkey / UrlGetVkey`，按 无损 → 320k → 128k 候选链降级；
- 播放页音源标识、QQ 音质切换（持久化 `QQMusicQualityStore`）；
- QQ 下载：
  - 设置页新增 **"QQ 音乐下载音质"**（无损 / 320k / 128k）；
  - 下载解析按该档位请求，缺失时自动降级；
  - "跟随播放音质"通过 `DownloadQqQualityBridge` 读取 QQ 播放档位；
  - 播放页"更多"新增"下载音质：xxx"提示；
  - 已下载歌曲显示"已下载 + 文件大小"（不再静默隐藏入口）。

### 2.4 评论

- QQ 评论接入统一 `CommentSheet`（`QQMusicCommentRepository`）：
  - 支持普通评论 + 精彩评论；
  - `[em]e401203[/em]` 表情解析为 QQ 空间 CDN 图片；
  - 评论入口统一到播放页（网易云同款位置），歌单行菜单不再单独入口。
- 写操作现状（2026-10-31）：Web 会话的点赞/发表仍被拒绝；带 App `authst` 时点赞可成功，发表评论返回 `500026`；当前 QQ 评论为**只读**
  （隐藏点赞/回复/输入框，显示只读提示）。
- **评论发表功能已决定放弃开发（2026-10-31）**，原因：
  - 网页端评论/点赞走的是新的加密网关
    `https://u6.y.qq.com/cgi-bin/musics.fcg?encoding=ag-1&sign=...`，
    不是本项目使用的明文 `musicu.fcg`；
  - 网页 JS 中 `sign = _getSecuritySign(body)`、`encrypt = __cgiEncrypt(body)`、
    `decrypt = __cgiDecrypt(response)`，核心实现在 `vendor.chunk` 内嵌的 **WebAssembly** 里；
  - 纯 Kotlin 无法低成本复刻；打包/调用 WASM 需要 Android WASM 运行时，
    且属于腾讯闭源代码，体积、版权、维护性都不可接受；
  - 因此评论保持只读，不再投入写接口逆向。

### 2.5 搜索

- 探索页 QQ 音源支持四个分类：
  - **歌曲**（修复 `code=2001`：改为带 Cookie + `uin/g_tk` 的 POST 请求）；
  - **歌单**；
  - **歌手**（点击进入歌手详情页）；
  - **专辑**（新增，`search_type=2`）。
- 结果行全部复用共享组件（`LinkedCollectionRow`、`ExploreSearchTypeChips`），
  UI 与网易云一致。

### 2.6 歌手页与专辑页

- 歌手详情 `QqMusicSingerDetailScreen`：
  - 迁移到 `ui/screen/artist/`，复用 `CreatorDetailAdaptiveLayout`（平板分栏）；
  - 歌曲行复用 `ArtistSongRow`，错误/空态复用 `ErrorBlock`/`EmptyBlock`；
  - 状态迁移到 `QqSingerDetailViewModel`（加载、重试、分页）。
- 专辑详情 `QqAlbumDetailScreen`（新）：
  - 复用通用 `SongCollectionScreen`：播放全部、随机、多选、收藏、添加到歌单；
  - 接口：`music.musichallAlbum.AlbumInfoServer / GetAlbumDetail`、
    `music.musichallAlbum.AlbumSongList / GetAlbumSongList`。

### 2.7 首页推荐（私人雷达 / 每日推荐）

- QQ 接口：
  - 每日推荐：`music.srfDissInfo.aiDissInfo / uniform_get_Dissinfo`（`dirid=202`，30 首）；
  - 私人雷达：`music.radioProxy.MbTrackRadioSvr / get_radio_track`（每次 5 首，聚合并去重）。
- 设置 → 首页与启动 → 首页栏目：
  - "私人雷达与雷达歌单"、"推荐歌单"两行改为**胶囊分点击**：
    - 行主体点击 → 选择音源（网易云 / QQ 音乐）；
    - 右侧开关 → 只控制开/关；
  - 新增持久化项 `home_card_radar_source` / `home_card_recommended_source`。
- 首页行为：
  - 音源选 QQ 时隐藏对应网易云区块，显示 QQ 推荐；
  - 两个卡片音源**互相独立**（雷达 QQ + 推荐网易云可同时生效）；
  - 标题标注音源（"私人雷达 · QQ 音乐" / "私人雷达 · 网易云音乐"）；
  - 顶栏刷新联动 QQ 推荐；私人雷达支持"换一批"；未登录显示引导；
  - QQ shelf 复用网易云的 `ResponsiveSongPagerList`（布局/收藏/下载状态一致）。

### 2.8 通用歌单集合页

新增 `SongCollectionScreen`（QQ 每日推荐、私人雷达、专辑、网易云每日推荐共用）：

- `TopAppBar` 返回、音源 + 首数；
- 播放全部 / 随机 / 添加到歌单；
- 长按多选：下载所选、加入队列、**添加到歌单所选**、全选；
- 逐行收藏（本地"我的收藏"）；
- 添加歌单对话框 `AddSongsToPlaylistDialog` 自动分平台：
  - QQ → 我的 QQ 歌单（20 首/批）；
  - 网易云 → 我的网易云歌单（批量 `addSongsToPlaylist`）。
- 网易云每日推荐新增整页路由 `netease_daily_songs`。

### 2.9 调试工具

开发者模式 → 平台接口 → **QQ 音乐接口调试**：

- 登录态读取；
- 我的歌单 / 歌单详情；
- 搜索歌曲 / 歌单 / 歌手 / 专辑；
- 歌手作品 / 专辑曲目；
- VKey 播放解析；
- 评论列表；
- 每日推荐 / 私人雷达。

每次调用原始 JSON 复制到剪贴板，便于排查。

---

## 3. 架构与代码复用

- 新增 ViewModel：`QqSingerDetailViewModel`、`QqPlaylistDetailViewModel`、`QqHomeFeedViewModel`、`QqMusicApiProbeViewModel`；
- 复用共享组件：
  - `CreatorDetailAdaptiveLayout`、`ArtistSongRow`、`ErrorBlock`、`EmptyBlock`；
  - `LibraryPlaylistRow`、`LibraryInlineSearchField`、`NeteaseLibraryEmptyCard`；
  - `LinkedCollectionRow`、`ExploreSearchTypeChips`；
  - `ResponsiveSongPagerList`（QQ 首页 shelf 直接复用）；
  - `SongCollectionScreen` / `AddSongsToPlaylistDialog`（跨平台通用）。
- UI 规范：所有新页面统一 `TopAppBar + WindowInsets.statusBars`、底部 `navigationBars` 内边距。

---

## 4. 已知限制

| 项 | 说明 |
|---|---|
| QQ 评论写操作 | **已放弃开发（2026-10-31）**：网页端走 `musics.fcg` + WASM 加密（`__cgiEncrypt/__cgiDecrypt`），纯 Kotlin 无法低成本复刻；当前只读 |
| QQ 歌单写操作 | 代码已实现；2026-10-31 复核确认需要有效登录态，旧 Cookie 过期会返回 `code=1000`，重新扫码后恢复 |
| QQ App 凭据（authst） | 尚未接入登录流程；WNS 登录未实现，普通用户无法自动获得 |
| QQ "我的专辑/收藏专辑" | 未找到可用接口（`AlbumBaseRead` 等返回 500003） |
| 歌手页专辑 Tab | 缺 `GetSingerAlbumList` 类接口线索，暂未实现 |
| 私人雷达条数 | 接口每次固定 5 首，页面通过聚合 3 批 + "换一批" 缓解 |
| 国际化 | 部分新增文案为中文硬编码（QQ 相关页面） |
| 签名 | 本仓库未包含 release 签名文件，正式版需自行配置或使用 debug 签名 |

---

## 5. 构建

```bash
# Debug（applicationId 后缀 .debug）
./gradlew :app:assembleDebug

# Release（需配置 release 签名；未配置时产物为 unsigned）
./gradlew :app:assembleRelease
```

环境：JDK 17、Android SDK（compileSdk 见 `gradle/libs.versions.toml`）。

---

## 6. 许可

本项目遵循原仓库 **GPL-3.0** 许可；所有新增文件保留原版权头与许可声明。
