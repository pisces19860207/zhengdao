// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 顺序 33「玄览排到逍遥之后」的验收（2026-10-14 用户派工）。
 *
 * 目标顺序：抱朴 0 / 逍遥 1 / **玄览 2** / **怡然 3**（玄览与怡然交换）。
 *
 * 为什么值得钉一条测试：这次交换暴露了**20 处硬编码索引**（when(tab) 分支、底栏
 * selected/onClick/图标、animateToTop、ON_RESUME 切 Tab、抱朴页的 onExit
 * （清场前是 `BaopuScreen`；旧线清场后的占位页 `BaopuRebuilding` 已于第六十二批删除）。
 * 漏改任何一处都不会编译报错，只会**静默错位** —— 最隐蔽的是初始值
 * `mutableIntStateOf(2)`：2 原本是怡然，交换后变成玄览，启动页会无声无息地漂走。
 * 所以这里钉两半：① 顺序契约（常量值）② 源码里不再出现裸索引字面量。
 *
 * 真机读数（底栏顺序、启动落点）归蓝总管采。
 */
class TabIndexOrderTest {

    private fun mainActivitySource(): String {
        val rel = "src/main/java/com/example/zhengdao/MainActivity.kt"
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 MainActivity.kt：${f.path}", f.isFile)
        return f.readText()
    }

    // ── ① 顺序契约 ────────────────────────────────────────────────────────

    @Test
    fun `顺序契约_抱朴0_逍遥1_玄览2_怡然3`() {
        assertEquals("抱朴（真实）", 0, TabIndex.BAOPU)
        assertEquals("逍遥（自由）", 1, TabIndex.TERMINAL)
        assertEquals("玄览（期望）", 2, TabIndex.BROWSER)
        assertEquals("怡然（幸福）", 3, TabIndex.AGENTS)
    }

    @Test
    fun `底栏里玄览排在怡然之前`() {
        val src = mainActivitySource()
        val browser = src.indexOf("R.string.nav_browser")
        val agents = src.indexOf("R.string.nav_agents")
        assertTrue("两个 Tab 名在源码里都该出现（browser=$browser, agents=$agents）", browser > 0 && agents > 0)
        assertTrue("玄览（nav_browser）必须排在怡然（nav_agents）之前（§B 顺序 33）", browser < agents)
    }

    // ── ② 源码里不再有裸索引字面量 ────────────────────────────────────────

    @Test
    fun `when_tab 的分支与底栏点击不再用裸字面量索引`() {
        val src = mainActivitySource()
        // 只查**代码行**：注释里出现字面量是历史留痕（如「`tab = 1` 不能省」那段的来历），允许保留。
        val codeLines = src.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        val patterns = listOf(
            Regex("""tab\s*==\s*[0-9]"""),
            Regex("""tab\s*=\s*[0-9]"""),
            Regex("""onTabClicked\(\s*[0-9]"""),
            Regex("""mutableIntStateOf\(\s*[0-9]\s*\)"""),
        )
        val offenders = codeLines.filter { line -> patterns.any { it.containsMatchIn(line) } }
        assertTrue(
            "这些代码行还留着裸索引 —— 下次改 Tab 顺序时会静默错位，请改用 TabIndex.*：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `when_tab 的每个分支都走常量`() {
        val src = mainActivitySource()
        for (name in listOf("BAOPU", "TERMINAL", "BROWSER", "AGENTS")) {
            assertTrue(
                "when (tab) 里应有一条 `TabIndex.$name ->` 分支（缺了就会有 Tab 白屏）",
                src.contains("TabIndex.$name ->")
            )
        }
    }
}
