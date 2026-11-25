# QQ 音乐手机版接口逆向记录（20.9.5.8）

> 目标：为 NeriPlayer 补齐 QQ 音乐功能（完整评论、歌单管理、完整搜索等）。
> 方法：非 root 设备拉取 APK → apktool/JADX/androguard 静态分析 → 用自有会话探针验证。
> 状态：第一阶段（静态取证）完成；写操作尚未对真实账号发起。

## 1. 样本信息

| 项 | 值 |
|---|---|
| 包名 | `com.tencent.qqmusic` |
| 版本 | `20.9.5.8`（versionCode `7508`） |
| 文件 | `tools/qqmusic/QQMusic10000482.apk`（193.0 MB） |
| SHA-256 | `0928eff9833fddf7247e5ce73b944568e7dd040fdf3dcc8c59c725b9125989db` |
| 结构 | 26 个 DEX（235 MB）、161 个 `lib/arm64-v8a/*.so`、1231 个 assets |
| 设备 | Lenovo TB375FC，Android 16，**未 root**；仅通过 `adb pull` 取证 |

分析工具：
- apktool 3.0.3（manifest/资源）
- JADX 1.5.6（定向单类反编译）
- androguard 4.1.4（DEX 字符串 → 类/方法交叉引用）
- REA + Ghidra 12.1.4（后续 native 签名分析）

## 2. 协议总览

QQ 音乐 App 使用 `musicu.fcg` 风格的模块调用：

```
POST https://u.y.qq.com/cgi-bin/musicu.fcg
{
  "comm": { "ct": 24, "cv": 4747474, "uin": "...", "g_tk": "...", ... },
  "req_1": {
    "module": "<module>",
    "method": "<method>",
    "param": { ... }
  }
}
```

大多数写接口的 `param` 是 **JCE 结构**（二进制 JCE 编码后放进 JSON），
搜索/评论写入等则是普通 JSON 字段。

## 3. 搜索

| 项 | 值 |
|---|---|
| module | `music.search.SearchCgiService` |
| method | `DoSearchForQQMusicMobile`（另有 `DoSearchForPutong`） |
| 实现类 | `com.tencent.qqmusic.baseprotocol.search.a`（及 `w`、`i0`） |
| 联想词 | `music.smartboxCgi.SmartBoxCgi` / `GetSmartBoxResult` |

请求 JSON 字段（来自 `Search_a.java`）：

```json
{
  "query": "<关键词>",
  "highlight": 1,
  "searchid": "<本次搜索唯一 id>",
  "sub_searchid": 0,
  "search_type": 0,
  "nqc_flag": 0,
  "sin": 0,
  "ein": 30,
  "page_num": 1,
  "num_per_page": 30,
  "cat": 2,
  "grp": 1,
  "remoteplace": "...",
  "from": "...",
  "multi_zhida": 1,
  "sem": 0,
  "selectors": { ... },
  "vec_selectors": [ ... ],
  "custom_string_flags": { "MultiExternInfo": "..." }
}
```

待补：
- `search_type` 的完整枚举（综合/歌曲/歌手/专辑/歌单/MV/歌词）；
- `selectors` 与 `vec_selectors` 的取值；
- `remoteplace` 的取值规则。

## 4. 歌单管理

### 4.1 创建歌单

| 项 | 值 |
|---|---|
| module / method | `music.musicasset.PlaylistBaseWrite.AddPlaylist` |
| 参数类 | `com.tencent.jce.musicasset.PlaylistBaseAddJceReq` |

JCE 字段：

| tag | 字段 | 类型 | 说明 |
|---|---|---|---|
| 0 | `dirName` | string | 歌单名 |
| 1 | `dirShow` | int | 默认 1（公开？） |
| 2 | `dirDesc` | string | 简介 |
| 3 | `dirPicUrl` | string | 封面 |
| 4 | `taglist` | string | 标签 |
| 5 | `metaData` | string | 元数据 |
| 6 | `x_source` | string | 来源标记 |
| 7 | `plType` | long | 歌单类型 |
| 8 | `source` | string | 来源 |
| 9 | `bFmtUtf8` | boolean | UTF-8 |
| 10 | `bInteractive` | boolean | 互动歌单 |
| 11 | `bCoupleInteractive` | boolean | 情侣互动 |

### 4.2 编辑歌单

| 项 | 值 |
|---|---|
| module / method | `music.musicasset.PlaylistBaseWrite.EditPlaylist` |
| 参数类 | `com.tencent.jce.musicasset.PlaylistBaseEditJceReq` |

关键字段：`dirId`、`mask`（位掩码，决定更新哪些字段）、
`dirNewName`、`dirNewDesc`、`dirNewPicUrl`、`dirNewPicUrl2`、
`dirNewtaglist`、`dirShow`、`metaData`、`plType`、`diy`、`source`。

> `mask` 是编辑能否生效的关键，需要从调用点还原每个 bit 的含义。

### 4.3 删除歌单

| 项 | 值 |
|---|---|
| module / method | `music.musicasset.PlaylistBaseWrite.DelPlaylist` |
| 参数类 | `com.tencent.jce.musicasset.PlaylistBaseDelJceReq`（待反编译确认字段） |

### 4.4 加歌 / 删歌

| 项 | 值 |
|---|---|
| module | `music.musicasset.PlaylistDetailWrite` |
| method | `AddSonglist` / `DelSonglist` |
| 参数类 | `com.tencent.jce.musicasset.SonglistOperJceReq` |
| 歌曲项 | `com.tencent.jce.musicasset.SongInfo` |

`SonglistOperJceReq` 字段：`dirId`、`dirName`、`v_songInfo`（SongInfo 数组）、
`source`、`x_source`、`insert`、`bFmtUtf8`、`tid`、`ext`。

`SongInfo` 字段：`songType`、`songId`、`songName`、`singerName`、
`songUrl`、`trace`、`desc`、`songMid`。

应用内行为：
- `source` 默认 `"normal"`；
- `insert` 控制插入位置（0/1 语义待确认）；
- 批量加歌按 20 首分批（与现有 Web 接口一致）。

## 5. 评论

### 5.1 读取（已有可用接口）

`GET https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg`
- `biztype=1`（歌曲）
- `topid=<songId>`
- `cmd=8`（热门）/ `6`（最新）
- 分页：`pagenum` / `pagesize`

### 5.2 发表评论

| 项 | 值 |
|---|---|
| module / method | `music.globalComment.CommentWriteServer.AddComment` |
| 实现类 | `com.tencent.qqmusic.business.timeline.e0`（动态评论） |

请求 JSON（来自 `e0.l`）：

```json
{
  "Content": "<评论正文>",
  "BizType": 22,
  "BizId": "<业务 id>",
  "From": "<可选来源>",
  "MomGroupMid": "<可选>",
  "MomTopicMid": "<可选>"
}
```

- `BizType` 在动态场景下为 `22` / `30`；
- 歌曲评论的 `BizType` 推测为 `1`，`BizId` 为歌曲数字 id（需实测）；
- 回复评论可能通过 `From`/`MomGroupMid`/`MomTopicMid` 或另一方法实现，待确认。

### 5.3 点赞 / 取消点赞

| 项 | 值 |
|---|---|
| module / method | `music.globalComment.CommentWrite.PraiseComment` |
| 参数类 | `com.tencent.qqmusic.business.online.response.gson.PraiseCommentReq` |

```json
{ "CmId": "<评论 id>", "type": 1 }
```

- 应用代码：`new PraiseCommentReq(z ? 2 : 1, commentId)`
- `z` 为 true → `type=2`，false → `type=1`；
- `1/2` 分别对应点赞/取消（待实测确认）。

### 5.4 删除 / 回复

- DEX 中未发现 `DelComment` 字符串的直接引用；
- 回复字段尚未定位；
- 下一步：反编译 `CommentWriteServer` 调用链与评论 Presenter，或抓官方 App 的错误码/日志。

## 6. 其他已发现模块

| 模块 | 用途 |
|---|---|
| `music.globalComment.CommentWrite.TaskReport` | 评论任务上报（非业务） |
| `music.srfDissInfo.PlSongExtServer.getPlSongExtInfo` | 歌单歌曲扩展信息 |
| `music.smartboxCgi.SmartBoxCgi` | 搜索联想 |
| `music.musicasset.PlaylistBaseWrite` | 歌单创建/编辑/删除 |
| `music.musicasset.PlaylistDetailWrite` | 歌单加歌/删歌 |

## 7. 待验证 / 待补

1. `AddComment` 在歌曲评论下的 `BizType` 与必填字段；
2. `PraiseComment` 的 `type=1/2` 语义；
3. 删除评论、回复评论的 module/method/字段；
4. `PlaylistBaseEditJceReq.mask` 各 bit 含义；
5. 搜索 `search_type` 枚举与 `selectors` 结构；
6. 写接口是否需要 `sign` / `zzz` / `authst`（App 内由 `ModuleRequestArgs` 自动附加）；
7. 是否受设备指纹（qimei）或风控限制。

## 8. 产物位置

- 反编译源码：`C:\Dev\qqmusic\decompiled\*.java`
- DEX 字符串/类扫描：`C:\Dev\downloads\qqm_*.py`、`qqm_*_out.txt`
- JADX 日志：`C:\Dev\downloads\jadx-*.log`

## 9. 下一步计划

1. 用现有 Cookie 对 `AddComment` / `PraiseComment` 做**受控探测**（先空内容/无效 id，避免产生真实评论）；
2. 反编译 `PlaylistBaseDelJceReq` 与编辑 mask 调用点；
3. 定位评论删除/回复实现；
4. 分析 `Musicu` 的 `comm`/`sign` 生成逻辑（必要时 Ghidra 分析 native）；
5. 验证通过后，按“评论 → 歌单管理 → 搜索”顺序实现到 NeriPlayer。
