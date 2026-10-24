// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

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

    /**
     * 渲染进程该给哪一档优先级（第一百七十二批 S1 · L1/L2 的地基）。
     *
     * **判据只有一条：App 在不在前台。** 前台 = 全部 [RENDERER_PRIORITY_IMPORTANT]；
     * 后台 = 全部 [RENDERER_PRIORITY_WAIVED]。
     *
     * 为什么**不再按「标签是否活跃」分**（改前的行为就是按它分的，本批改掉）：
     * `WAIVED` 的语义是「渲染进程不被绑定，系统想收就收」，而
     * `setRendererPriorityPolicy(WAIVED, keepAlive=false)` 正是官方文档里「随时可被杀」那一档。
     * 玄览的 L1 承诺是「切回不重载」——把非活跃标签按 WAIVED 放着，等于一边承诺一边把页面交给
     * 系统的回收器；2026-10-24 核对时还发现回前台只升活跃那一颗（`resumeActive`），
     * 其余标签会被永久留在 WAIVED 上。⇒ 活跃度与「页面能不能活」无关，App 可见性才有关。
     *
     * @param appForeground App 是否在前台（MainActivity 的 ON_RESUME/ON_PAUSE 是唯一来源）
     * @param isActiveTab 该标签是否当前活跃。**本参数不参与判据**——保留它是为了让
     *   「前台 + 非活跃」这一格能被单测逐字钉住（它正是改前的病格），
     *   第一百七十二批 S3 的前台预算裁剪会真正用上它。
     */
    fun rendererPriority(appForeground: Boolean, isActiveTab: Boolean): Int =
        if (appForeground) RENDERER_PRIORITY_IMPORTANT else RENDERER_PRIORITY_WAIVED
}
