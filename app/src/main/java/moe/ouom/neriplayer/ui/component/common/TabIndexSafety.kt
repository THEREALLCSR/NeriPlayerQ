package moe.ouom.neriplayer.ui.component.common

/**
 * 把可能过期的 Tab/Pager 索引夹到合法范围。
 *
 * 场景：媒体库/探索页的 Tab 数量会随 YouTube 音源开关变化（6↔5、5↔4），
 * 而 Pager 的 currentPage 在同一帧内仍是旧索引；直接传给 Material3
 * ScrollableTabRow 会在其内部 `tabWidths[index]` 越界崩溃（第 033 次真机事故）。
 * 空列表返回 0，调用方仍需自行保证列表非空或跳过渲染。
 */
internal fun safeTabIndex(index: Int, tabCount: Int): Int =
    if (tabCount <= 0) 0 else index.coerceIn(0, tabCount - 1)
