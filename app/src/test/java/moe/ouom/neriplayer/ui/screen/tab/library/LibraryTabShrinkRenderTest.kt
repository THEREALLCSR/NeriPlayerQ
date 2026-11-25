package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * 第 033 次崩溃回归：媒体库 Tab 数量缩短后，Pager 的 currentPage 仍是旧索引。
 *
 * 真机现场（2026-11-25 21:46:08，LENOVO TB375FC）：
 * - 21:46:00 `HomeVM: home source updated: youtube=false`（YouTube 音源开关被关闭）；
 * - 8 秒后媒体库 Tab 由 6 个缩为 5 个（YTMUSIC 被移除），而 `pagerState.currentPage` 仍为 5（原 QQMUSIC）；
 * - `PrimaryScrollableTabRow(selectedTabIndex = 5)` 内部 `tabWidths[5]` 越界：
 *   `IndexOutOfBoundsException: Index 5 out of bounds for length 5`
 *   at `TabRowKt$ScrollableTabRowImpl$1$1$2$1.measure(TabRow.kt:622)`。
 *
 * 本测试用「5 个 Tab + 过期索引 5」直接渲染 [LibraryMainTabs]：
 * - 修复前：measure 阶段抛 IndexOutOfBoundsException（复现真机崩溃）；
 * - 修复后：组件内部把索引夹到 0..lastIndex，正常渲染不崩。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryTabShrinkRenderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val tabsAfterYouTubeDisabled = listOf(
        LibraryTab.LOCAL,
        LibraryTab.FAVORITE,
        LibraryTab.NETEASE,
        LibraryTab.BILI,
        LibraryTab.QQMUSIC,
    )

    @Test
    fun staleSelectedIndexAfterTabRemoval_doesNotCrash() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    LibraryMainTabs(
                        tabs = tabsAfterYouTubeDisabled,
                        selectedTabIndex = 5,
                        onTabSelected = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun selectedIndexAtLastTab_stillRenders() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    LibraryMainTabs(
                        tabs = tabsAfterYouTubeDisabled,
                        selectedTabIndex = tabsAfterYouTubeDisabled.lastIndex,
                        onTabSelected = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
