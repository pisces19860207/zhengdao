// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百八十批（2026-10-24）§1 判据与负例：
//   「10 条同因失败 ⇒ 页面只显示『同类失败 ×10』＋一句摘要；点开有完整错误；
//     负例：仍铺一长串、摘要丢信息、红字刷屏 ⇒ 各红」。
//
// 分工与 105 批门禁同款：算得出的事（聚合、详情完整性）用纯函数**当场断言**；
// 算不了的（页面结构）钉源码（剥注释后断言，变异体在断言信息里写明长什么样会红）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillFailureUiGateTest {

    // ── 读源码（房型照 BaopuShellP1GateTest）─────────────────────────────

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释后的源码：判据一律读它，免得把解释判据的注释当成判据本身。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun settingsPage(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun failureFile(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillFailure.kt")

    private fun strings(lang: String): String = repoFile("src/main/res/$lang/strings.xml").readText()

    private fun row(name: String, reason: String, at: Long? = null, global: Boolean = true) =
        SkillFailureRow(name = name, global = global, reason = reason, lastFailedAt = at)

    // ── 判据 ────────────────────────────────────────────────────────────

    /**
     * J1 十条同因 ⇒ 一组「同类失败 ×10」（派工单判据原话）。
     * 红＝聚合成多条、或条数不是 10（摘要就失去了「同类 ×N」的意义）。
     */
    @Test
    fun `J1_十条同因失败聚成一组乘十`() {
        val rows = (1..10).map { row("技能$it", "超过 14 天没用过") }
        val groups = aggregateSkillFailures(rows)
        assertEquals("同因必须聚成一组", 1, groups.size)
        assertEquals("组里一条不少", 10, groups[0].count)
        assertEquals("组名就是那句判语", "超过 14 天没用过", groups[0].reason)
        assertTrue(
            "zh 徽标串必须是「同类失败 ×N」形态",
            strings("values").contains("<string name=\"skill_fail_group_badge\">同类失败 ×%1\$d</string>"),
        )
        assertTrue(
            "en 徽标串同形态（判据 T6：双语各一份）",
            strings("values-en").contains("<string name=\"skill_fail_group_badge\">Same failure ×%1\$d</string>"),
        )
    }

    /** 红＝不同原因被混进同一组（「同类」就没了；用户点开看到的判语对不上一半技能）。 */
    @Test
    fun `J2_不同原因各成一组互不混`() {
        val rows = listOf(
            row("a", "超过 14 天没用过"),
            row("b", "最近 5 次里失败≥60%"),
            row("c", "超过 14 天没用过"),
            row("d", "超过 14 天没用过，且最近 5 次里失败≥60%"),
        )
        val groups = aggregateSkillFailures(rows)
        assertEquals("三种判语 ⇒ 三组", 3, groups.size)
        assertEquals(listOf(2, 1, 1), groups.map { it.count })
        assertTrue("每组的行都只装自己那句判语", groups.all { g -> g.rows.all { it.reason == g.reason } })
    }

    /** 红＝聚合把顺序打乱（组序必须是首现序、组内保持索引原序——与设置页列表同一口径）。 */
    @Test
    fun `J3_聚合顺序稳定`() {
        val rows = listOf(
            row("b", "r2"),
            row("a", "r1"),
            row("c", "r2"),
            row("d", "r1"),
        )
        val groups = aggregateSkillFailures(rows)
        assertEquals(listOf("r2", "r1"), groups.map { it.reason })
        assertEquals(listOf("b", "c"), groups[0].rows.map { it.name })
        assertEquals(listOf("a", "d"), groups[1].rows.map { it.name })
        // 两层同名各算各的行（与列表口径一致），聚合不去重名字
        val both = aggregateSkillFailures(
            listOf(row("同名", "r", 1L), row("同名", "r", 2L, global = false)),
        )
        assertEquals(1, both.size)
        assertEquals(2, both[0].count)
    }

    /**
     * J4 详情/复制文本一条不少：判语原文、每个技能名（带层）、各自最近失败时刻（或明说没有）。
     * 红＝摘要省掉的信息详情也丢了（「摘要丢信息」负例）——排查线索断在这里。
     */
    @Test
    fun `J4_详情复制文本一条不少`() {
        val group = aggregateSkillFailures(
            listOf(
                row("做卡", "最近 5 次里失败≥60%", 1_761_300_000_000, global = true),
                row("查资料", "最近 5 次里失败≥60%", null, global = false),
            ),
        ).single()
        val text = skillFailureCopyText(
            group = group,
            badgeLine = "同类失败 ×2",
            hintLine = "待重验：最近 5 次里失败≥60%（先用起来验证，别照单全收）",
            globalLabel = "全局",
            projectLabel = "项目",
            lastFailedPrefix = "最近失败：",
            noFailureLabel = "无失败记录",
            timeTextOf = { "2025-10-24 12:00" },
        )
        assertTrue("判语原句要在", text.contains("最近 5 次里失败≥60%"))
        assertTrue("提醒整句要在（原文口径）", text.contains("先用起来验证，别照单全收"))
        assertTrue("每个名字都要在：做卡", text.contains("做卡"))
        assertTrue("每个名字都要在：查资料", text.contains("查资料"))
        assertTrue("层标签要在", text.contains("做卡（全局）") && text.contains("查资料（项目）"))
        assertTrue("失败时刻要在", text.contains("最近失败：2025-10-24 12:00"))
        assertTrue("没失败记录的要明说，不装作有", text.contains("查资料（项目）无失败记录"))
        assertTrue("×N 计数要在", text.contains("×2"))
    }

    /**
     * J5 失败列＝两层待重验表的并集，最近失败时刻逐条问数据源；**正常技能一次都不查**。
     * 红＝把全部索引行都当失败铺出来（全成功态也会冒红），或查了不该查的。
     */
    @Test
    fun `J5_只有待重验行进失败列`() {
        val queried = mutableListOf<String>()
        val rows = buildSkillFailureRows(
            globalRecheck = mapOf("a" to "r1"),
            projectRecheck = mapOf("b" to "r2"),
            lastFailedAtOf = { queried += it; 42L },
        )
        assertEquals(2, rows.size)
        assertEquals("a" to true, rows[0].name to rows[0].global)
        assertEquals("b" to false, rows[1].name to rows[1].global)
        assertEquals(42L, rows[0].lastFailedAt)
        assertEquals(listOf("a", "b"), queried)
        // 两层都没有待重验 ⇒ 空列（全成功态零失败行，页面只留读数行）
        assertTrue(buildSkillFailureRows(emptyMap(), emptyMap(), { null }).isEmpty())
    }

    /**
     * J6 源码口径（负例钩子）：
     * ① 技能索引页不许再引用待重验长句（判语原句只住详情对话框——铺回来＝仍是一长串）；
     * ② 组行只许一处调用（搬进技能行循环＝红字刷屏）；
     * ③ 行内短状态与读数行要接上；
     * ④ 详情带复制全文。
     */
    @Test
    fun `J6_页面短状态_长句只在详情`() {
        val page = settingsPage()
        assertFalse(
            "技能索引页不许引用 skill_recheck_badge（长句只住 BaopuSkillFailure 的详情里）",
            page.contains("skill_recheck_badge"),
        )
        assertEquals(
            "SkillFailureGroupRow 只许一处调用（搬进行循环＝红字刷屏）",
            1,
            Regex("SkillFailureGroupRow\\(").findAll(page).count(),
        )
        assertTrue("行内失败短状态要接上", page.contains("R.string.skill_row_failed_at"))
        assertTrue("读数行要接上（技能 N · 失败 M · 待转正 K）", page.contains("R.string.skill_fail_summary"))
        val fail = failureFile()
        assertTrue("详情要有复制全文的口", fail.contains("skillFailureCopyText("))
        assertTrue("判语原文要出现在详情（skill_recheck_badge 整句）", fail.contains("skill_recheck_badge"))
    }
}
