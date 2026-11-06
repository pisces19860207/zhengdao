// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零八批 · 归因装配壳的判据靶：同一配对不重复入账（attribute() 是整本重判的形状，
// 收工链上直接挂会把旧账每轮重记一遍）；读数按 MisjudgeWhy.ofWire 认回机器键。
package com.example.zhengdao.baopu.agent

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttributionWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 一份最小档案：一个「待定→否决」配对（同键，行号锚稳定）。 */
    private fun plantArchive(): File {
        val products = File(tmp.root, "产物").apply { mkdirs() }
        val day = LocalDate.now()
        return File(products, "进化档案.md").apply {
            writeText(
                listOf(
                    "# 进化档案（append-only：只在状态变化时追加新行，历史行不改）",
                    "| 日期 | 类型 | 技能/落点 | 一句话主张 | 证据 | 决定 |",
                    "|---|---|---|---|---|---|",
                    "| $day | 新技能 | 技能/写手册 | 该把写手册收进技能 | 技能/写手册/SKILL.md:L1 | 待定 |",
                    "| $day | 新技能 | 技能/写手册 | 该把写手册收进技能 | 技能/写手册/SKILL.md:L1 | 否决 |",
                ).joinToString("\n") + "\n",
                Charsets.UTF_8,
            )
        }
    }

    @Test
    fun `同一配对不重复入账_读数用ofWire认回机器键`() {
        JudgmentExperienceLedger.resetForTest()
        plantArchive()
        val logs = mutableListOf<String>()
        AttributionWiring.onSettled(tmp.root, log = { logs += it })
        AttributionWiring.onSettled(tmp.root, log = { logs += it })
        val rows = JudgmentExperienceLedger.readAll().filter { it.kind == ExperienceKind.ATTRIBUTION }
        assertEquals("整本重判了两回，同一对只许记一笔", 1, rows.size)
        assertEquals("首驳（无前科）", MisjudgeWhy.FIRST_REJECT.wire, rows[0].key.removePrefix("attribution:"))
        assertTrue("每行都要带可点开的档案行锚", rows[0].evidence.all { it.contains("#L") })
        val readout = AttributionWiring.readout(JudgmentExperienceLedger.readAll())
        assertTrue("读数认回人话", readout.contains(MisjudgeWhy.FIRST_REJECT.cn))
        assertTrue("收工日志最后一句是读数", logs.last().startsWith("抱朴归因：本轮判得"))
    }

    @Test
    fun `档案不存在_空结果_账上零行`() {
        JudgmentExperienceLedger.resetForTest()
        val logs = mutableListOf<String>()
        AttributionWiring.onSettled(tmp.root, log = { logs += it })
        assertTrue(JudgmentExperienceLedger.readAll().none { it.kind == ExperienceKind.ATTRIBUTION })
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("账上 0 行"))
    }

    @Test
    fun `认不出的键单列_不冒充别的类`() {
        JudgmentExperienceLedger.resetForTest()
        JudgmentExperienceLedger.append(
            ExperienceRow(
                id = "lx:test:1",
                atMs = 1L,
                kind = ExperienceKind.ATTRIBUTION,
                cardClass = "反思:k",
                key = "attribution:made_up",
                reason = "测试行",
                evidence = listOf("x#L1", "x#L2"),
            ),
        )
        assertTrue(AttributionWiring.readout().contains("认不出 1 行"))
    }
}
