// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MainRouteRules] 的判据用例（第七十六批 · §B 80 MainActivity 纯逻辑抽取）。
 *
 * 重点钉两件事：
 * ① **两份语义不许合并** —— `startRoute` 不查白名单（死值也落 home，兜底安全）；
 *    `restoreRoute` 查白名单（死值 ⇒ 不恢复，避免 `navigate` 抛 `IllegalArgumentException`）。
 * ② **恢复的两个前置条件**（白名单内 / 站在起始页）逐条短路。
 *
 * 第二百六十一批：通知栏直达终端的旧开关已删（恒 false 的死参数），
 * 「直达终端」两条用例随之退役；其余判据一字未改。
 *
 * 白名单与 NavHost 的一致性（白名单 ⊆ 实际注册路由）不在本文件，归 `ui/RouteRestoreGuardTest`
 * ——那里读的是真实源码形态，能挡住"白名单加了死路由但本文件用例没跟着改"。
 */
class MainRouteRulesTest {

    @Test
    fun `起始页_有上次页面就落home`() {
        assertEquals("home", MainRouteRules.startRoute(lastRoute = "settings"))
        // 死值也落 home：startRoute 不查白名单，home 本身是兜底，最坏只是"没恢复那页"
        assertEquals("home", MainRouteRules.startRoute(lastRoute = "plugins"))
    }

    @Test
    fun `起始页_首次安装落welcome`() {
        assertEquals("welcome", MainRouteRules.startRoute(lastRoute = null))
    }

    @Test
    fun `恢复页_白名单内的二级页叠加`() {
        assertEquals("settings", MainRouteRules.restoreRoute("settings", currentRoute = "home"))
        assertEquals("settings", MainRouteRules.restoreRoute("settings", currentRoute = "welcome"))
    }

    @Test
    fun `恢复页_死值不恢复`() {
        assertNull(MainRouteRules.restoreRoute("plugins", currentRoute = "home"))
        // 与 startRoute 的行为对照：同一个死值，起始页仍落 home（兜底），恢复页则不叠（防崩）
        assertEquals("home", MainRouteRules.startRoute(lastRoute = "plugins"))
    }

    @Test
    fun `恢复页_不在起始页不叠加`() {
        assertNull(MainRouteRules.restoreRoute("settings", currentRoute = "settings"))
        assertNull(MainRouteRules.restoreRoute("settings", currentRoute = null))
        assertNull(MainRouteRules.restoreRoute("settings", currentRoute = "permission_setup"))
    }

    @Test
    fun `恢复页_没有上次页面不恢复`() {
        assertNull(MainRouteRules.restoreRoute(null, currentRoute = "home"))
    }

    @Test
    fun `白名单_只含真正值得恢复的页且不含死值`() {
        assertTrue("settings 必须在白名单里", "settings" in MainRouteRules.RESTORABLE_ROUTES)
        assertFalse("plugins 早已不是路由，不许进白名单", "plugins" in MainRouteRules.RESTORABLE_ROUTES)
        assertFalse("welcome 由起始页决定，谈不上恢复", "welcome" in MainRouteRules.RESTORABLE_ROUTES)
        assertFalse("home 是兜底，不需要恢复", "home" in MainRouteRules.RESTORABLE_ROUTES)
    }
}
