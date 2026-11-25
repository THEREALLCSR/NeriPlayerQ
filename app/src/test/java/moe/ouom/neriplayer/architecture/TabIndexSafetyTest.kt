package moe.ouom.neriplayer.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第 033 次崩溃回归：Tab 列表缩短后，Pager 索引与 TabRow 选中索引必须夹取。
 *
 * 真机现场（2026-11-25 21:46:08，LENOVO TB375FC / Android 16）：
 * - 21:46:00 设置流推送 `youtube=false`（YouTube 音源开关关闭）；
 * - 媒体库 Tab 由 6 个缩为 5 个（YTMUSIC 被移除），但 `pagerState.currentPage` 仍为 5；
 * - `PrimaryScrollableTabRow(selectedTabIndex = 5)` 在 Material3 内部 `tabWidths[5]` 越界：
 *   `IndexOutOfBoundsException: Index 5 out of bounds for length 5`
 *   at `TabRowKt$ScrollableTabRowImpl$1$1$2$1.measure(TabRow.kt:622)`。
 *
 * 同类风险：
 * - 媒体库 `HorizontalPager` 内容直接 `orderedTabs[page]`，Tab 缩短时同样会越界；
 * - 探索页音源列表随 YouTube 开关变化，`PrimaryScrollableTabRow` 与 `orderedSearchSources[page]`
 *   存在完全相同的越界路径（只是数量为 5→4）。
 *
 * 本测试钉住三件事（行为复现见 LibraryTabShrinkRenderTest）：
 * 1. 两处 TabRow 的 selectedTabIndex 必须经 `safeTabIndex(...)` 夹取；
 * 2. 两处 Pager 内容必须用 `getOrNull(page)`，不得直接下标；
 * 3. 共享夹取函数 `safeTabIndex` 必须存在。
 */
class TabIndexSafetyTest {

    private val libraryScreen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/LibraryScreen.kt"
    )
    private val libraryTabs = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/tab/library/LibraryScreenRemotePlaylistContent.kt"
    )
    private val exploreScreen = File(
        "src/main/java/moe/ouom/neriplayer/ui/screen/tab/explore/ExploreScreen.kt"
    )
    private val helper = File(
        "src/main/java/moe/ouom/neriplayer/ui/component/common/TabIndexSafety.kt"
    )

    @Test
    fun `shared safe tab index helper exists`() {
        assertTrue("缺少共享夹取函数 TabIndexSafety.kt", helper.exists())
        val source = helper.readText()
        assertTrue(
            "TabIndexSafety.kt 必须定义 safeTabIndex(index, tabCount)",
            source.contains("fun safeTabIndex(")
        )
    }

    @Test
    fun `library tab row clamps selected index`() {
        val source = libraryTabs.readText()
        assertTrue(
            "LibraryMainTabs 必须用 safeTabIndex(...) 夹取后传给 PrimaryScrollableTabRow",
            source.contains("safeTabIndex(")
        )
        assertFalse(
            "LibraryMainTabs 不得把 selectedTabIndex 原样传给 PrimaryScrollableTabRow",
            source.contains("selectedTabIndex = selectedTabIndex")
        )
    }

    @Test
    fun `library pager never indexes tabs directly`() {
        val source = libraryScreen.readText()
        assertTrue(
            "媒体库 HorizontalPager 必须用 orderedTabs.getOrNull(page)",
            source.contains("orderedTabs.getOrNull(page)")
        )
        assertFalse(
            "媒体库 HorizontalPager 不得直接 orderedTabs[page]（Tab 缩短会越界）",
            source.contains("orderedTabs[page]")
        )
        assertTrue(
            "媒体库 TabRow 的 selectedTabIndex 必须经 safeTabIndex(...) 夹取",
            source.contains("safeTabIndex(")
        )
    }

    @Test
    fun `explore tab row and pager are shrink safe`() {
        val source = exploreScreen.readText()
        assertTrue(
            "探索页 TabRow 的 selectedTabIndex 必须经 safeTabIndex(...) 夹取",
            source.contains("safeTabIndex(")
        )
        assertTrue(
            "探索页 HorizontalPager 必须用 orderedSearchSources.getOrNull(page)",
            source.contains("orderedSearchSources.getOrNull(page)")
        )
        assertFalse(
            "探索页 HorizontalPager 不得直接 orderedSearchSources[page]",
            source.contains("orderedSearchSources[page]")
        )
    }
}
