# NeriPlayer × QQ 音乐适配规划

> 目标：给 [cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer) 这个 fork 加上 QQ 音乐：**登录、读取账号歌单、下载、查看评论**。
> 本文是编码前的结构与方案分析，不含实现代码。

---

## 一、结论摘要

1. NeriPlayer **不是从零开始**——它已经有 QQ 音乐的 **搜索 + 歌词**（匿名接口），
   缺的是：**登录、播放解析、账号歌单、下载、评论**。
2. 应用的分层很清晰（model / platform / playback / download / app），
   每一项能力都有明确的接入点，不需要推翻架构。
3. 工作量估算（按一个人业余时间）：**约 10–14 个工作日**，可拆成 6 个可独立验收的阶段。
4. 我们手里已经有一份"QQ 音乐协议实现"（MeloX 项目 + `tools/qq-probe` 探针），
   登录 / 歌单 / vkey / 音质预检查都可以直接移植（两边同为 GPL-3.0，协议兼容）。

---

## 二、仓库现状

| 项 | 值 |
|---|---|
| 位置 | `D:\Backup\Documents\deepseek-harness\default-workspace\NeriPlayer` |
| 规模 | 5388 个文件 / 61 MB |
| 技术栈 | Kotlin 2.4.10、AGP 9.4.1、Gradle 9.6.1、JDK 17、Jetpack Compose |
| 许可 | **GPL-3.0**（fork 后分发必须继续开源） |
| 构建状态 | ✅ `gradlew projects` 通过（24 个模块），子模块已补齐 |

构建上做的三件事：

1. 从 codeload 拉取 4 个子模块（`NeriPlayer-LTW`、`accompanist-lyrics-core/ui`、`miuix`）；
2. `settings.gradle.kts` 增加阿里云 Maven 镜像（本机直连 Central 仅 ~0.4 MB/s）；
3. 预置 Gradle 9.6.1 到 `C:\Dev\gradle-home`，写 `local.properties` 指向本机 SDK。

---

## 三、代码结构

```
NeriPlayer/
├── app/                      ← UI + 依赖注入 + Activity（5k 文件里的大头）
│   └── src/main/java/moe/ouom/neriplayer/
│       ├── core/di/AppContainer.kt      ← 全局单例装配（所有仓库在这里 new）
│       ├── ui/screen/...                ← Compose 页面（设置/音乐库/播放器/评论）
│       ├── ui/screen/tab/settings/auth/ ← 账号登录入口
│       └── activity/auth/               ← 网易云扫码/网页登录 Activity（可作模板）
├── modules/
│   ├── model/       ← 跨层数据模型（SongItem / MusicPlatform / CommentPlatform…）
│   ├── platform/    ← 各平台实现：netease / bilibili / youtube / comments / search / lyrics
│   ├── playback/    ← 播放内核（logic + runtime），URL 解析在 runtime/player/url
│   ├── download/    ← 下载内核（logic + runtime），含存储后端
│   ├── network/     ← OkHttp 等基础网络
│   ├── database/    ← Room
│   ├── lyrics/      ← 歌词渲染
│   ├── sync/ local/ listentogether/
│   └── ...
├── build-logic/     ← Gradle 约定插件（版本、编译配置都在这里）
└── np-submodule/    ← 4 个子模块（歌词 UI、miuix 组件、一起听服务）
```

**关键设计**：能力按"平台目录"组织，而不是抽象成一个巨型接口。
例如评论是 `platform/comments/repository/CommentRepository`，
搜索是 `platform/search/api/SearchApi`，播放解析在 `playback/runtime` 里按平台分支。

---

## 四、QQ 音乐支持现状

### 已经有的

| 能力 | 位置 |
|---|---|
| 平台枚举 | `MusicPlatform { CLOUD_MUSIC, QQ_MUSIC }` |
| 搜索 + 歌曲详情 | `platform/search/api/client/QQMusicSearchApi.kt`（9.4 KB） |
| 歌词 | `platform/lyrics/repository/QQMusicLyricsRepository.kt` + `QQMusicLyricCodec` |
| 设置页账号卡片占位 | `SettingsAccountPlatform.QqMusic` 已存在 |

### 缺的（本次要补）

| 能力 | 现状 | 影响 |
|---|---|---|
| **登录** | `AuthLoginStorage` 对 QQ 直接返回 `AuthLoginStorageStatus(false,false)`<br>`loadQQMusicAccountProfile()` 返回 `null` | 无法登录，只能匿名搜索 |
| **播放解析** | `PlayerUrlResolver` 只有 Netease / Bilibili / YouTube 分支 | QQ 的歌点开不能播 |
| **账号歌单** | 没有 QQ 版音乐库/歌单仓库 | 看不到"我喜欢的音乐"、自建歌单 |
| **下载** | `modules/download` 的 `DownloadSourceServices` 没有 QQ 实现 | 不能离线 |
| **评论** | `CommentPlatform { NETEASE, BILIBILI }` | 评论区没有 QQ 来源 |

---

## 五、接入点（按能力）

| 能力 | 需要动的地方 | 说明 |
|---|---|---|
| **登录** | 新增 `platform/qqmusic/auth/`（Cookie 仓库 + 扫码客户端）<br>`activity/auth/QqMusicQrLoginActivity`（仿 `NeteaseQrLoginActivity`）<br>`AppContainer` 注册 `qqCookieRepo`<br>`AuthLoginStorage` / `SettingsAccountAuthController` / `SettingsAccountCards` 接上真实状态 | 协议见第六节 |
| **搜索增强** | `QQMusicSearchApi` 支持携带 Cookie（登录后能搜到更多/更高音质） | 现有实现是匿名 |
| **播放** | `PlayerUrlResolver` 增加 QQ 分支 + 音质选项；<br>`SongItem` 需要携带 `songmid / mediaMid` | 复用 vkey 逻辑 |
| **歌单** | 新增 `platform/qqmusic/playlist/`（`GetPlaylistByUin` + `CgiGetDiss`）<br>`LibraryScreen` 增加 QQ 内容分支（仿 `LibraryScreenNeteaseContent`）| 工作量最大的一块 |
| **下载** | `DownloadSourceServices` 增加 QQ 源解析；候选音质来自 vkey 预检查 | 存储/进度复用现成内核 |
| **评论** | `CommentPlatform` 加 `QQMUSIC`；新增 `QQMusicCommentRepository`<br>`AppContainer.commentRepositoryFor()` 增加分支<br>`CommentSource` 映射（songmid） | 接口需先用探针确认 |

> 注意：`CommentPlatform` / `MusicPlatform` 都是 **enum**，新增分支会触发 `when` 穷尽检查——
> 这是好事：编译器会列出所有必须改的地方。

---

## 六、QQ 音乐接口映射（可复用 MeloX 的实现）

| 能力 | 接口 | 备注 |
|---|---|---|
| 扫码登录 ① 取码 | `GET ssl.ptlogin2.qq.com/ptqrshow`（appid=716027609, pt_3rd_aid=100497308） | 返回 PNG + `qrsig` Cookie |
| ② 轮询 | `GET ssl.ptlogin2.qq.com/ptqrlogin`（`ptqrtoken = hash33(qrsig, seed=0)`） | 返回 `ptuiCB(...)` 文本 |
| ③ 换授权码 | `check_sig` → `graph.qq.com/oauth2.0/authorize`（`g_tk = hash33(p_skey, 5381)`） | 从 302 Location 取 `code` |
| ④ 换音乐凭据 | `musicu.fcg` `QQConnectLogin.LoginServer/QQLogin`（微信走 `music.login.LoginServer/Login`） | 得到 `musicid` + `musickey` |
| 账号资料 | `c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg` | 昵称/头像 |
| 我的歌单 | `music.musicasset.PlaylistBaseRead/GetPlaylistByUin` | `data.v_playlist` |
| 歌单详情 | `music.srfDissInfo.DissInfo/CgiGetDiss` | `dirinfo` + `songlist` |
| 播放地址 | `music.vkey.GetVkey/UrlGetVkey`（或 `vkey.GetVkeyServer/CgiGetVkey`） | 前缀 M500/M800/F000 |
| 音质预检查 | `music.trackInfo.UniformRuleCtrl/CgiGetTrackInfo` | `size_flac / size_320mp3…` |
| 评论 | **待探测**（可用 `tools/qq-probe` 逐个试 `music.*Comment*` 模块） | 计划 P6 第一阶段做 |

登录态存什么：`uin` + `qm_keyst`（musicKey）+ 完整 Cookie 串；
所有业务请求带 `comm.uin` + `g_tk = hash33(musicKey)` + Cookie。

---

## 七、分阶段计划

| 阶段 | 内容 | 验收标准 | 预估 |
|---|---|---|---|
| **P0 基线** | 克隆、子模块、镜像、Gradle 预置 | `gradlew projects` 成功（已完成） | 0.5 天 |
| **P1 登录** | Cookie 仓库、扫码 Activity、设置页账号卡片、DI | 设置里能扫码登录，显示昵称/头像，重启保持 | 2–3 天 |
| **P2 搜索增强** | 搜索携带登录 Cookie；歌曲详情带 songmid/mediaMid | 登录后搜索结果显示来源标记 | 0.5 天 |
| **P3 播放** | `PlayerUrlResolver` QQ 分支 + 音质档位 + 降级链 | 搜到的 QQ 歌能播，能切换标准/320k/无损 | 2 天 |
| **P4 歌单** | 我的歌单 / 歌单详情 / 收藏 | 音乐库能看到 QQ 歌单并播放 | 3 天 |
| **P5 下载** | 下载源接入 + 存储 | 选歌下载后能离线播放 | 2 天 |
| **P6 评论** | 探测接口 → Repository → 评论区来源 | 歌曲详情能看到 QQ 评论 | 1–2 天 |

每个阶段都能独立编译、独立回归；P1/P3 是其它阶段的前置。

---

## 八、可复用资产

| 资产 | 位置 | 用法 |
|---|---|---|
| QQ 协议实现（Kotlin） | MeloX：`core/provider/qqmusic/`（登录、vkey、歌单、评论待补） | 直接移植，注意包名/风格适配 |
| 接口探针 | `tools/qq-probe/`（Python：vkey、歌单、音质查询、下载测试） | 验证 NeriPlayer 环境下的接口可用性 |
| 抓包/逆向方法 | `MeloX-代码结构导读.md`、探针 README | 找评论接口、排查签名问题 |

> 两边都是 GPL-3.0，代码互相移植没有许可障碍；但**必须保留版权头**（NeriPlayer 每个文件都有 GPL 头）。

---

## 九、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 非官方接口不稳定 | QQ 随时改字段/风控 | 探针先行、接口层做"查不到就降级" |
| 账号风控 | 高频请求/异常设备 | 低频、复用官方 Web 身份、不做批量抓取 |
| GPL 传染 | fork 分发需开源 | 保留 LICENSE 与文件头 |
| 代码量大 | 5388 文件、24 模块 | 严格按阶段切，每阶段只碰必要文件 |
| 构建慢 | 首次配置 11 分钟 | 增量构建 + 不清理 build 缓存 |

---

## 十、下一步

从 **P1 登录** 开始写代码：

1. `modules/platform/.../platform/qqmusic/auth/QQMusicSessionStore.kt`（Cookie 持久化）
2. `.../QQMusicQrLoginClient.kt`（四步扫码协议，移植 MeloX）
3. `app/.../activity/auth/QqMusicQrLoginActivity.kt`（仿网易云扫码页）
4. `AppContainer` 注册 + `AuthLoginStorage` / `SettingsAccountCards` 接真实状态

P1 完成后先用真机验证"能登录、能显示昵称"，再进入 P3（播放）。
