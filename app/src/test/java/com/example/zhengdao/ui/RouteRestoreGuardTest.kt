// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-15（2026-10-16 复核）：冷启动恢复上次页面 —— 白名单必须只含「真的还在 NavHost 里」的路由。
 *
 * 台账原文（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * - **B-补-15**（P1-8）冷启动只恢复 `lastRoute == "settings"`，plugins 不恢复。
 *
 * 复核结论（2026-10-16，以代码为准）：
 * ① NavHost 只有四个目的地 —— `welcome` / `home` / `permission_setup` / `settings`
 *    （`MainActivity.kt` 里四处 `composable(...)`；`permission_setup` 走 `PermissionSetup.ROUTE` 常量）。
 * ② 当年点名的 `plugins` **已经不是路由**：插件页 2026-10-08 搬进太极抽屉
 *    （`ui/taiji/TaijiScreen.kt:585` 的 `PluginsSheet` 复用 `ui/PluginsScreen.kt`，
 *    见 `ui/taiji/TaijiSheets.kt:236-277`）⇒ 台账那句「plugins 不恢复」已失效。
 * ③ 剩下三条不吃亏：`welcome` 由 `startRoute` 决定、`home` 本身是兜底、
 *    `permission_setup` 由 `guidePermissions` 效果按需叠回 ⇒ **`settings` 是唯一值得恢复的**。
 * ④ 真正要钉住的是另一件事：白名单必须与 NavHost 保持一致 —— 否则老装机 prefs 里
 *    残留的死路由（如 "plugins"）会被 `navigate()` 抛 `IllegalArgumentException`，冷启动直接崩。
 *
 * 判据一律是**源码形态**，与 [PullToRefreshGuardTest] / [A11yBannerGuardTest] 同惯例：
 * 本模块没有 Compose UI 测试基建，因此钉「源码不能被悄悄改回裸字符串/裸 navigate」。
 */
class RouteRestoreGuardTest {

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根；两种都试。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private fun mainActivity(): String =
        repoFile("app/src/main/java/com/example/zhengdao/MainActivity.kt").readText()

    /** NavHost 真正注册的路由（字面量 + `PermissionSetup.ROUTE` 常量）。 */
    private fun registeredRoutes(): Set<String> {
        val src = mainActivity()
        val literal = Regex("""composable\(\s*"([A-Za-z0-9_\-]+)"""").findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        val viaConst = Regex("""composable\(\s*([A-Za-z0-9_.]+)\.ROUTE\s*\)""").findAll(src)
            .map { it.groupValues[1] }
            .toSet()
        val resolved = viaConst.map { prefix ->
            val leaf = prefix.substringAfterLast('.')
            val file = repoFile("app/src/main/java/com/example/zhengdao/ui/$leaf" + "Screen.kt")
            val m = Regex("""const val ROUTE\s*=\s*"([A-Za-z0-9_\-]+)"""").find(file.readText())
                ?: throw AssertionError("$leaf.ROUTE 常量解析不出来（$file）")
            m.groupValues[1]
        }.toSet()
        return literal + resolved
    }

    /** `private val RESTORABLE_ROUTES = setOf(...)` 里的条目。 */
    private fun whitelist(): Set<String> {
        val src = mainActivity()
        val decl = Regex("""private val RESTORABLE_ROUTES\s*=\s*setOf\(([^)]*)\)""").find(src)
            ?: throw AssertionError("MainActivity.kt 里找不到 RESTORABLE_ROUTES 白名单声明")
        val items = Regex(""""([A-Za-z0-9_\-]+)"""").findAll(decl.groupValues[1])
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("白名单不能是空集（否则冷启动永不恢复）", items.isNotEmpty())
        return items
    }

    @Test
    fun `门禁_B补15_白名单里的路由都必须真实存在于 NavHost`() {
        val known = registeredRoutes()
        val allowed = whitelist()
        assertTrue("NavHost 路由解析结果太可疑：$known", known.size >= 4)
        val dead = allowed - known
        assertTrue(
            "B-补-15：白名单里出现 NavHost 已不存在的路由 $dead" +
                "（老装机 prefs 残留该值时会 navigate 抛 IllegalArgumentException）",
            dead.isEmpty(),
        )
    }

    /** 恢复上次页面的那段代码（剥掉注释行，免得守卫被自己的说明文字带偏）。 */
    private fun restoreBlock(): String {
        val src = mainActivity()
        val start = src.indexOf("LaunchedEffect(lastRoute)")
        assertTrue("MainActivity.kt 里找不到 LaunchedEffect(lastRoute) 恢复块", start > 0)
        val end = src.indexOf("// 记录当前页面", start)
        val block = src.substring(start, if (end > start) end else (start + 1200).coerceAtMost(src.length))
        return block.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    @Test
    fun `门禁_B补15_恢复逻辑走白名单而不是裸字符串比对`() {
        val code = restoreBlock()
        assertTrue(
            "B-补-15：恢复判定要走 RESTORABLE_ROUTES 白名单",
            code.contains("lastRoute?.takeIf { it in RESTORABLE_ROUTES }"),
        )
        assertFalse(
            "B-补-15：不能退回只认 settings 的裸字符串比对",
            code.contains("""lastRoute == "settings""""),
        )
        assertFalse(
            "B-补-15：更不能直接 navigate(lastRoute) —— prefs 里的死值会崩冷启动",
            code.contains("nav.navigate(lastRoute"),
        )
    }

    @Test
    fun `门禁_B补15_白名单有说明为什么只有 settings`() {
        val src = mainActivity()
        val start = src.indexOf("private val RESTORABLE_ROUTES")
        assertTrue("找不到白名单声明", start > 0)
        // 往前 1600 字符内应能找到 KDoc（含四个目的地与 plugins 死值的说明）
        val doc = src.substring((start - 1600).coerceAtLeast(0), start)
        assertTrue("白名单 KDoc 要写清四个目的地", doc.contains("welcome") && doc.contains("permission_setup"))
        assertTrue("白名单 KDoc 要写清 plugins 这类死值为什么被挡", doc.contains("plugins"))
    }

    @Test
    fun `门禁_B补15_插件面板确实已改成太极抽屉里的 sheet`() {
        // 这条是「B-补-15 里 plugins 不恢复」失效的直接证据：插件页不再是路由。
        val taiji = repoFile("app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt").readText()
        assertTrue("太极页应引用 PluginsSheet（插件面板）", taiji.contains("PluginsSheet("))
        val src = mainActivity()
        assertEquals(
            "MainActivity 里不应再注册 plugins 路由",
            0,
            Regex("""composable\(\s*"plugins"""").findAll(src).count(),
        )
    }
}
