# NeriPlayer 媒体库：QQ 音乐 与 网易云 页面/接口差异分析

> 目的：让"媒体库 → QQ 音乐"在布局、组件、交互上与网易云模式保持一致，
> 尽量复用作者已有的组件与数据流，而不是另起一套。
>
> 分析对象：
> - 网易云：`LibraryScreenNeteaseContent.kt`（536 行）
> - QQ 音乐：`LibraryScreenFavoriteContent.kt` 中的 `QqMusicPlaylistList`（约 180 行）
> - 容器：`LibraryScreen.kt` / `LibraryHostScreen.kt`

## 1. 页面结构对比

| 区域 | 网易云 | QQ 音乐（当前） | 结论 |
|---|---|---|---|
| 顶部 | 分类 Tabs（歌单 / 专辑）+ 内联搜索框，作为 LazyColumn 的 header item | 内联搜索框 + 右侧「+新建」按钮 | 结构相近；QQ 无专辑分类，Tabs 可省；「+」是 QQ 独有功能，保留 |
| 列表行 | `NeteasePlaylistRow`：`Card + ListItem`，56dp 封面、`offlineCachedImageRequest(sizePx=192)`、副标题 `playlist_play_count_format(播放量, 歌曲数)` | 自建 `Card + ListItem`，56dp `AsyncImage`、副标题 `"N 首"` | **应对齐**：复用 `ListItem` 行样式与离线缓存图片请求 |
| 空状态 | `NeteaseLibraryEmptyCard(cardShape, title, hint, iconIsAlbum)` | 自写 Card（"还没有可显示的 QQ 音乐歌单"/"没有找到匹配的歌单"） | **应复用**：空状态卡片样式与图标统一 |
| 错误状态 | 由上层 `ui.error` 统一展示，行内无错误卡 | 自写"加载失败 + 重试"卡 | 可保留（QQ 无 VM 错误流），但样式对齐 |
| 下拉刷新 | 调用点 `LibraryPullToRefresh(refreshing = ui.neteaseRefreshing, ...)` 包裹整个列表 | 在列表内部自行包裹 `LibraryPullToRefresh(refreshing = loading, ...)` | 位置不同但组件相同；功能等价，可视为可接受差异 |
| 加载中 | 未见独立 loading 行（依赖刷新状态） | 居中 `CircularProgressIndicator` | 可保留 |

## 2. 组件对照

| 能力 | 网易云组件 | QQ 当前 | 复用建议 |
|---|---|---|---|
| 搜索框 | `LibraryInlineSearchField(query, onQueryChange, placeholderResId)` | 同左 | ✅ 已复用 |
| 分类 Tabs | `LibrarySecondaryTabs` + `NeteaseCategoryTabs` | 无 | 暂无需求（QQ 无专辑分类），保留扩展位 |
| 歌单行 | `NeteasePlaylistRow` | 自建行 | 抽 `QqMusicPlaylistRow`，样式/图片请求与网易云一致 |
| 空状态卡 | `NeteaseLibraryEmptyCard` | 自建卡 | 直接复用（组件本身与平台无关） |
| 封面加载 | `offlineCachedImageRequest(context, data, sizePx, allowHardware, offlineMode)` | `AsyncImage(model = coverUrl)` | **改为离线缓存请求**，离线模式行为一致 |
| 数量文案 | `playlist_play_count_format` + `formatPlayCount` | `"N 首"` | QQ 列表接口有 `listennum`，补齐后可复用同一文案 |

## 3. 数据接口对比

| 模型/接口 | 网易云 | QQ 音乐 |
|---|---|---|
| 列表模型 | `PlaylistSummary(id: Long, name, picUrl, playCount: Long, trackCount: Int)` | `QQMusicPlaylist(id: String(tid), title, coverUrl, trackCount, dirId)` |
| 列表接口 | 网易云 `user/playlist` + 专辑接口 | `music.musicasset.PlaylistBaseRead / GetPlaylistByUin` |
| 播放量 | 有 `playCount` | 列表项含 `listennum`（当前未解析） |
| 写句柄 | 歌单 id 即接口 id | 需额外 `dirId`（`tid` 是歌单 id） |
| 专辑 | 有独立分类与接口 | 无（QQ 侧未实现专辑） |
| 搜索 | 服务端搜索 + 本地过滤 | 仅本地过滤（搜索我的歌单） |

## 4. 交互差异

| 交互 | 网易云 | QQ |
|---|---|---|
| 点击歌单 | `onNeteasePlaylistClick → 记录打开 → 详情` | `onClick → QQ 歌单详情路由` |
| 下拉刷新 | 上层触发 `vm.refreshNeteaseLibrary` | 内部 `reloadToken++` 重新拉取 |
| 新建歌单 | 不支持（网页端创建） | 支持（`+` → `AddPlaylist`） |
| 离线模式 | 行内封面走离线缓存请求 | 未适配离线缓存 |

## 5. 对齐建议（按优先级）

### P0（本轮完成）
1. 新增 `QqMusicPlaylistRow`：
   - 与 `NeteasePlaylistRow` 相同的 `Card + ListItem` 结构、56dp 封面、8dp 圆角；
   - 封面统一走 `offlineCachedImageRequest(..., offlineMode)`；
   - 副标题复用 `playlist_play_count_format(播放量, 歌曲数)`（需补 `listennum`）。
2. `QQMusicPlaylist` 增加 `playCount: Long`，解析 `listennum`。
3. 空状态复用 `NeteaseLibraryEmptyCard`（标题/提示文案换成 QQ 语境）。

### P1（后续）
4. 头部统一为"搜索框 + 右侧操作"的 header item（已基本一致），未来若加"收藏歌单/专辑"再引入 `LibrarySecondaryTabs`。
5. 把刷新状态上移到 ViewModel（与网易云 `ui.neteaseRefreshing` 对齐），让调用点统一用 `LibraryPullToRefresh` 包裹。
6. 搜索过滤抽公共函数（与 `filterNeteasePlaylists` 对齐命名与行为）。

### P2（可选）
7. QQ 侧补"专辑/收藏歌单"分类，才能真正用上 Tabs；
8. 列表排序/筛选（按播放量、更新时间）。

## 6. 结论

- **组件层**：可复用的比例很高（搜索框、行容器、空状态、下拉刷新、图片请求都是通用的），QQ 页缺的只是"用对的组件"。
- **数据层**：需要补 `playCount` 才能完全复用网易云的副标题格式；`dirId` 属于 QQ 写接口必需字段，属于合理差异。
- **交互层**：刷新位置、新建歌单属于平台差异，不建议强行统一。
- 本轮先做 P0，把视觉与组件复用做到与网易云一致，再评估 P1。

---

## 7. 补充：歌单详情页控件差异（第二轮）

| 控件 | 网易云详情页 | QQ 详情页（改造前） | 处理 |
|---|---|---|---|
| 顶栏搜索 | `Icons.Filled.Search` 图标，点击进入歌单内搜索 | 无 | ✅ 已补：搜索图标 + 输入框，本地过滤歌名/歌手 |
| 下载 | `Icons.Outlined.Download` 图标 | 文本按钮"下载全部" | ✅ 已改为顶栏下载图标 |
| 收藏 | `Icons.Filled.Favorite` / `Outlined.FavoriteBorder` | 无（QQ 无收藏歌单接口） | 保留差异，见下 |
| 更多菜单 | `Icons.Filled.MoreVert` | 行尾 `MoreVert`（下一首/队列/加歌单/移除/评论） | ✅ 结构一致 |
| 顶部操作 | 图标按钮 + 播放全部等 | 文本按钮（播放全部/随机/加入队列/下载） | 部分对齐：下载移入顶栏；其余保留文本按钮（QQ 无收藏等动作） |
| 列表行 | `SongRow`（序号/封面/标题/副标题/更多） | 自建行（结构一致） | ✅ 视觉参数一致 |

**代码复用**：新增通用 `LibraryPlaylistRow`，网易云与 QQ 的列表行都改为调用它；
详情页的搜索/下载图标复用 Material 图标包，避免再自造控件。

## 8. 补充：关于"专辑分类"的接口探测结论

尝试为 QQ 增加"专辑"Tab 时探测了以下接口：

| 接口 | 结果 |
|---|---|
| `music.musicasset.AlbumBaseRead / GetAlbumByUin` | `code=500003`（模块/方法不可用） |
| `music.musicasset.AlbumRead / GetAlbumByUin` | `code=500003` |
| `music.musicasset.PlaylistBaseRead / GetFavPlaylist` | `code=40000`（参数/权限不支持） |

结论：**QQ 音乐没有可用的"我的专辑 / 收藏专辑"读取接口**，因此无法像网易云一样做真正的双分类 Tab。

建议：
1. 保持 QQ 单分类（我的歌单），但头部、搜索框、行样式、空状态与网易云保持一致（已完成）；
2. 待找到可用的专辑/收藏接口后，直接复用 `LibrarySecondaryTabs` + `NeteaseLibraryEmptyCard` 增加分类，不需要改布局；
3. 若产品上需要"第二分类"，可先做"最近播放"（本地播放历史按 `channelId = qqmusic` 过滤），但那与网易云的"专辑"语义不同，需产品确认。

