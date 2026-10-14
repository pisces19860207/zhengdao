package com.example.zhengdao.browser

/**
 * 多标签纯策略（第⑨刀 B · 玄览阶段 2 §4.2）。
 *
 * 设计依据：《证道-浏览器Tab技术选型与架构草案-2026-10-13》v1.1 §4.2
 * 「多标签：每标签 = 独立 WebView + 独立渲染优先级；标签数上限（建议 20），
 * 后台标签降 RENDERER_PRIORITY_WAIVED」。
 *
 * 为什么单独拆：这些判定是**纯列表/布尔逻辑**（不碰 Android），按项目房型
 * （审计类缺陷修成「纯函数 + JVM 表驱动单测」）单独成类，JVM 单测直接覆盖；
 * [com.example.zhengdao.ui.browser.BrowserTabManager] 只做 Android 侧的装配。
 *
 * 证据等级：② 常量值与 `android.webkit.WebView` 静态字段一致（javap -constants 实读
 * android-37.0/android.jar：WAIVED=0 / BOUND=1 / IMPORTANT=2；API 26 起冻结未变）。
 */
object BrowserTabPolicy {

    /** 标签数上限（§4.2「建议 20」）。 */
    const val MAX_TABS = 20

    /** 与 android.webkit.WebView.RENDERER_PRIORITY_WAIVED 同值（javap 实读）。 */
    const val RENDERER_PRIORITY_WAIVED = 0

    /** 与 android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT 同值（javap 实读；框架默认档）。 */
    const val RENDERER_PRIORITY_IMPORTANT = 2

    /** 前台标签渲染优先级（框架默认档，显式设定保证切回时确定恢复）。 */
    fun rendererPriority(isActive: Boolean): Int =
        if (isActive) RENDERER_PRIORITY_IMPORTANT else RENDERER_PRIORITY_WAIVED

    /** 还能不能开新标签（到上限即拒）。 */
    fun canOpenMore(currentCount: Int): Boolean = currentCount < MAX_TABS

    /**
     * 关掉 [closedId] 后谁接棒活跃标签。
     *
     * 规则（桌面/移动浏览器通行语义）：
     * - 关的不是活跃标签 ⇒ 活跃不变（返回 [activeId]）；
     * - 关的是活跃标签 ⇒ 优先**右邻**，无右邻取**左邻**（与关闭后焦点自然落点一致）；
     * - 只剩它一个 ⇒ null（玄览回到空态）。
     *
     * @param orderedIds 按创建顺序排的标签 id 列表（管理器用插入序容器维护）。
     */
    fun nextActiveAfterClose(orderedIds: List<Int>, closedId: Int, activeId: Int?): Int? {
        if (closedId == activeId) {
            val index = orderedIds.indexOf(closedId)
            if (index < 0) return null
            // 右邻优先；被关的是最后一个则取左邻
            return orderedIds.getOrNull(index + 1) ?: orderedIds.getOrNull(index - 1)
        }
        return activeId
    }

    /**
     * 外部打开入口（终端链接点击等）的落点策略（第⑨刀 B 定：开新标签）。
     *
     * 桌面/Chrome Android 通行语义：从 App 外打开的链接进**新标签**，不动用户当前页面
     * （与「切 Tab 不丢页面」同向）。仅在**已满 20** 时回退当前活跃标签——
     * 满员还拒掉用户的点击更糟，且此时本来就没有"不丢页面"的余地了。
     *
     * @return true = 开新标签；false = 复用当前活跃标签。
     */
    fun externalOpenShouldCreateTab(currentCount: Int): Boolean = canOpenMore(currentCount)
}
