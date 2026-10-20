// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

/**
 * `MainActivity` 的两条路由判据：**起始页**与**恢复上次页面**（第七十六批 · §B 80 纯逻辑抽取）。
 *
 * ⚠️ 这两条是**两份语义，不许合并成一个函数**：
 * - [startRoute] 只决定 NavHost 的 `startDestination`：只要不是首次安装（有 lastRoute）或
 *   通知栏直达终端，就落 `home`。它**不查白名单** —— 死值也照样落 `home`，因为 `home`
 *   本身就是兜底，最坏结果只是「没恢复到上次那页」，不会崩。
 * - [restoreRoute] 决定在起始页之上**再叠加**哪一页：必须过白名单，且只在确实站在起始页
 *   （`home` / `welcome`）时才叠加。返回 null = 不叠加。
 *
 * 为什么起始页不能直接是恢复页（真机实测，2026-10-06 / 10-08）：
 * 起始页底下没有返回栈，`popBackStack` 无处可退 ⇒ 返回键与右滑全失效（用户实测「纯白」）。
 * 所以永远落 `home`，恢复页叠在其上，返回键能退回首页。
 *
 * 零 Android 依赖；判据用例见 `MainRouteRulesTest`，白名单与 NavHost 的一致性由
 * `ui/RouteRestoreGuardTest` 钉住（本文件是它的读取目标之一）。
 */
internal object MainRouteRules {

    /** NavHost 的首页路由字面量（`composable("home")`）。 */
    const val ROUTE_HOME = "home"

    /** 首次安装的欢迎页路由字面量（`composable("welcome")`）。 */
    const val ROUTE_WELCOME = "welcome"

    /**
     * 冷启动可恢复的 Compose 路由白名单（B-补-15 复核，2026-10-16；第七十六批随判定主体搬入本文件）。
     *
     * NavHost 只有四个目的地：`welcome` / `home` / `permission_setup` / `settings`。
     *  - `welcome`：首次安装的入口，由 [startRoute] 决定，谈不上「恢复」；
     *  - `home`：默认落点，本身就是兜底；
     *  - `permission_setup`：权限门槛，由 `guidePermissions` 那个效果按需重新叠加（见 `MainActivity.onCreate` 里的注释）；
     *  - `settings`：用户会主动离开、又期望回来还在原处的二级页 ⇒ **唯一值得恢复的**。
     *
     * ⚠️ 故意写成「白名单 + 兜底判空」，而不是 `nav.navigate(lastRoute)`：prefs 里可能残留
     * **老版本**的路由字符串 —— 例：插件页 2026-10-08 曾搬进抱朴抽屉（该抽屉的插件面板
     * 复用 `ui/PluginsScreen.kt`；抽屉本身 2026-10-16 随旧抱朴线清场删除），`plugins` 路由随之删除，但老装机的
     * `last_route` 里仍可能留着 "plugins"。直接 navigate 会抛
     * `IllegalArgumentException` 崩在冷启动，白名单把这类死值一律当「没有可恢复的页面」。
     */
    val RESTORABLE_ROUTES = setOf("settings")

    /**
     * NavHost 的起始页：通知栏直达终端 ⇒ `home`；有上次页面 ⇒ `home`；否则 ⇒ `welcome`。
     *
     * 恰好等价于「不是首次安装就落 home」。**不查白名单**（见类注释，两份语义不许合并）。
     */
    fun startRoute(startInTerminal: Boolean, lastRoute: String?): String =
        if (startInTerminal || lastRoute != null) ROUTE_HOME else ROUTE_WELCOME

    /**
     * 起始页之上要不要再叠加恢复页；要 ⇒ 返回路由，不要 ⇒ null。
     *
     * 三个前置条件缺一不可（顺序即短路顺序，与改前一致）：
     * 1. 不是通知栏直达终端（那趟要直接拉终端，不该再叠一页）；
     * 2. `lastRoute` 在白名单内（死值如 `plugins` ⇒ null）；
     * 3. 当前确实站在起始页（`home` / `welcome`）——中间态叠加会造出「栈里只剩恢复页」的死返回。
     */
    fun restoreRoute(lastRoute: String?, startInTerminal: Boolean, currentRoute: String?): String? {
        if (startInTerminal) return null
        if (currentRoute != ROUTE_HOME && currentRoute != ROUTE_WELCOME) return null
        return lastRoute?.takeIf { it in RESTORABLE_ROUTES }
    }
}
