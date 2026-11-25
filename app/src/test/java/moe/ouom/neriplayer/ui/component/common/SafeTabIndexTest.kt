package moe.ouom.neriplayer.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第 033 次崩溃修复的边界行为：Tab/Pager 索引夹取。
 *
 * 真机事故是「5 个 Tab + 索引 5」传给 Material3 ScrollableTabRow 导致
 * `IndexOutOfBoundsException`；这里把 helper 的边界逐条钉死。
 */
class SafeTabIndexTest {

    @Test
    fun `stale index past last tab is clamped to last`() {
        // 真机现场：YouTube 关闭后 6 → 5 个 Tab，旧页码 5
        assertEquals(4, safeTabIndex(5, 5))
        assertEquals(5, safeTabIndex(Int.MAX_VALUE, 6))
    }

    @Test
    fun `negative index is clamped to zero`() {
        assertEquals(0, safeTabIndex(-1, 5))
        assertEquals(0, safeTabIndex(Int.MIN_VALUE, 5))
    }

    @Test
    fun `valid index is unchanged`() {
        assertEquals(0, safeTabIndex(0, 5))
        assertEquals(3, safeTabIndex(3, 5))
        assertEquals(4, safeTabIndex(4, 5))
    }

    @Test
    fun `empty tab list degrades to zero instead of throwing`() {
        assertEquals(0, safeTabIndex(0, 0))
        assertEquals(0, safeTabIndex(9, 0))
        assertEquals(0, safeTabIndex(-3, 0))
    }
}
