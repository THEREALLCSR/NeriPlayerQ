# 下一阶段设计：下载音质、QQ 评论互动、首页雷达/推荐接入 QQ

> 目标：先厘清现状与接口边界，再按"小步可验证"的顺序实现。
> 相关模块：`download/runtime`、`platform/qqmusic`、`ui/screen/tab/settings`、`HomeHostScreen`。

## 1. 下载音质选择（优先级最高）

### 现状
- 下载音质由 `DownloadAudioQualitySelection(neteaseQuality, youtubeQuality, biliQuality)` 表示；
- 设置页 `SettingsDownloadSection` 为三个平台各提供一个音质项 + 选项弹窗；
- 持久化走 `AutoSettingsSchema.download.download{Netease,YouTube,Bili}AudioQuality`；
- QQ 下载解析 `AudioDownloadSourceResolver.resolveQq(song)` 目前**写死** `qualityKey = "lossless"`（候选链自动降级），不读设置。

### 方案
1. 模型：`DownloadAudioQualitySelection` 增加 `qqQuality: String = "lossless"`（带默认值，避免破坏现有构造）。
2. 设置项：`AutoSettingsSchema.download.downloadQqAudioQuality`
   - key: `download_qq_audio_quality`，默认 `lossless`，order 45，icon 用 QQ 图标；
   - 新增 `DEFAULT_DOWNLOAD_QQ_AUDIO_QUALITY`、`normalizeDownloadQqAudioQuality()`（合法值：`lossless` / `exhigh` / `standard`）。
3. 设置 UI：`DownloadAudioQualityPlatform` 增加 `QQ`；
   - 列表项：标题"QQ 音乐下载音质"，当前值标签：无损 / 高品质 / 标准；
   - 弹窗选项与 QQ 播放音质一致（三档）。
4. 下载解析：`resolveQq(song, preferredQuality)`，用 `downloadAudioQuality.qqQuality` 作为 `qualityKey`；
   - `resolveDownloadSourceForAttempt` 的 QQ 分支传入该值（函数内已有 `downloadAudioQuality`）。
5. "跟随播放音质"开关：QQ 播放音质存在独立的 `QQMusicQualityStore`（SharedPreferences），不在 DataStore，首版**不参与**"跟随播放音质"，下载始终用下载音质设置；文档标注。

### 涉及文件
- `modules/model/.../DownloadAudioQualitySelection.kt`
- `modules/local/.../AutoSettingsSchema.kt`、`DownloadAudioQualitySettings.kt`
- `app/.../settings/component/download/SettingsDownloadSection.kt`
- `modules/download/runtime/.../AudioDownloadSourceResolver.kt`、`AudioDownloadManagerAttempt.kt`
- `modules/common/res/values*/strings_settings_general.xml`（新增 1 条文案）

### 风险
- AutoSettingsSchema 改动会触发 KSP 生成与设置元数据迁移；保持默认值可向后兼容。
- 旧版本没有该 key 时按默认值处理即可。

## 2. QQ 评论：点赞 / 回复 / 发表

### 已探明结论
| 路径 | 结果 |
|---|---|
| 网页 `fcg_comment_praise.fcg` | 404 |
| `music.globalComment.CommentWrite / PraiseComment` | code=10000 |
| `SetPraise` / `LikeComment` | code=40000 |
| `AddComment`（空内容探测） | code=10000 |
| `DelComment` | code=10000 |

- 网页身份（ct=24）与 App 身份（ct=11 + authst）都试过；
- snake_case / camelCase 参数都试过；
- 点赞数未变化，确认无副作用。

结论：**QQ 评论写操作当前被风控/参数校验拒绝**，没有可复现的公开协议。

### 后续测试计划（用你的设备）
1. 在 App 内对一条 QQ 评论点"点赞"（目前被禁用）→ 临时打开开关，记录 `adb logcat` 与响应码；
2. 对照官方 QQ 音乐 App 的行为（同一账号、同一条评论），观察点赞是否成功、`praisenum` 是否变化；
3. 若成功，抓取官方 App 的请求（需 root/抓包工具），比对 `comm`、`param` 字段名与签名；
4. 找到正确形状后，在 `QQMusicCommentRepository.setLiked/sendComment` 中实现，并恢复 CommentSheet 的交互。

### 兜底
在找到可用协议前，QQ 评论区保持**只读**（已实现：隐藏点赞/回复/输入框，显示只读提示）。

## 3. 首页"私人雷达 / 每日推荐"接入 QQ

### 现状
- 首页卡片开关：`AutoSettingsSchema.homeCardRadar` / `homeCardRecommended`（布尔）；
- 数据来自网易云：`HomeHostScreen` 传入 `showRadarCard`，内容由 `HomeRadar*` / 推荐歌单接口填充；
- 文案已写死"网易云"（`settings_home_card_netease_radar` / `..._recommended`）。

### 目标交互（你提出的胶囊控件）
- 点击胶囊**开关区域**：切换功能开/关；
- 点击胶囊**其余区域**：弹出音源选择（网易云 / QQ 音乐）；
- 设置项需要从布尔扩展为"开关 + 音源"两个状态。

### 数据模型方案
```kotlin
enum class HomeFeedSource { NETEASE, QQ_MUSIC }

data class HomeFeedConfig(
    val enabled: Boolean,
    val source: HomeFeedSource,
)
```
- 持久化：
  - 保留原布尔 key（兼容旧配置）；
  - 新增 `home_card_radar_source` / `home_card_recommended_source`（String，`netease`/`qqmusic`）。
- UI：
  - 自定义 `HomeFeedSettingRow`：`Row` 左侧点击 → 打开音源选择底部弹窗；右侧 `Switch` 只切换开关；
  - 复用现有 Miuix 设置卡片样式与开关组件。

### QQ 数据源（需探针确认）
| 功能 | 候选接口 |
|---|---|
| 私人雷达（猜你喜欢） | `music.recommend.RecommendFeed / GetRecommendFeed`、`music.radio.RadioProxy / GetRadioList` |
| 每日推荐 | `music.musicasset.DailyRecommendRead / GetDailyRecommend`、`music.recommend.DailyRecommend / GetDailyList` |

- 探针通过后，在 `HomeViewModel` 增加按 source 分支加载；
- 失败时回退：QQ 源不可用 → 显示提示并保持网易云数据（或空态）。

### 分阶段
1. **P1**：设置项模型与胶囊 UI（先只支持切换开关 + 音源选择，数据仍走网易云，QS 源标记为"即将支持"）；
2. **P2**：探针确认 QQ 雷达/推荐接口，接入数据；
3. **P3**：QQ 源的空态、错误、加载与网易云对齐。

## 4. 实施顺序建议
1. 下载音质（改动可控、用户可立即感知）；
2. QQ 评论写接口的设备联合调试（需要你配合点赞/发评论并抓日志）；
3. 首页胶囊控件 + 设置模型；
4. QQ 雷达/推荐数据接入（依赖探针结果）。

## 5. 验收标准
- 下载设置页出现"QQ 音乐下载音质"，选择后下载到的文件档位符合预期（无损/320k/128k）；
- QQ 评论区在写接口可用前保持只读且无报错；可用后点赞/回复/发送与网易云一致；
- 首页雷达/推荐可切换音源，开关行为与旧版一致，旧配置升级后不丢设置。
