// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

/**
 * 底栏「双击回到顶部」的纯判据（第七十六批 · §B 80 `MainActivity` 纯逻辑抽取）。
 *
 * 搬移来源：`HomeTabs.onTabClicked` 里的 300ms 判定。原文把「同 Tab + 时间窗内」压成一个
 * `if`，与「改 Tab」写在同一个函数里，很难单独验证；抽成纯函数后，时间由调用方注入
 * （`SystemClock.uptimeMillis()`），窗口边界可以逐毫秒钉住。
 *
 * 语义（与改前逐条一致）：
 * - 只有**点同一个 Tab** 才可能是双击；不同 Tab 一律是切换；
 * - 窗口判定是**严格小于**（`now - last < 300`）：正好 300ms 不算双击；
 * - 双击后时间戳清零（防止「连点三下」被算成两次双击）；单击后时间戳取本次 now。
 */
internal object TabClickRules {

    /** 双击时间窗（毫秒）。改这个值等于改手感，须有派工单。 */
    const val DOUBLE_TAP_WINDOW_MS = 300L

    /**
     * 这次点击算不算「双击回顶」。
     *
     * @param currentTab 当前选中的 Tab（[TabIndex] 常量）
     * @param targetTab 本次点击的 Tab
     * @param nowMs 本次点击时刻（调用方注入，通常 `SystemClock.uptimeMillis()`）
     * @param lastClickAtMs 上一次点击的时刻；0 表示此前没点过
     */
    fun isDoubleTap(
        currentTab: Int,
        targetTab: Int,
        nowMs: Long,
        lastClickAtMs: Long,
        windowMs: Long = DOUBLE_TAP_WINDOW_MS,
    ): Boolean = currentTab == targetTab && nowMs - lastClickAtMs < windowMs

    /** 点击后要写回的时间戳：双击 ⇒ 0（清零），单击 ⇒ 本次时刻。 */
    fun nextStampMs(isDoubleTap: Boolean, nowMs: Long): Long = if (isDoubleTap) 0L else nowMs
}
