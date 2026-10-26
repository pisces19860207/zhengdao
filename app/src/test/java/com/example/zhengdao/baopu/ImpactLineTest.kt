// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十批 · 卡面那一行（`ImpactLine`）＋注入（`ImpactInject`）的判据。
//
// 派工单 §2.5 的四条硬判据在这里逐条变成断言：
//   有卡场景 ≤1 行 ≤30 字；无卡场景 0 显示；不新增任何卡片；关掉开关后完全无提示。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactLineTest {

    // ── ≤30 字 ＋ 单行（最坏情况逐个喂）──────────────────────────────────

    @Test
    fun `判据_卡面一行最多30字且只有一行`() {
        val longName = "一个特别特别长的文件名用于把模板撑爆".repeat(3) + ".txt"
        val cases = listOf(
            Impact(Impact.Kind.WRITE, newFiles = 1),
            Impact(Impact.Kind.WRITE, overwrite = listOf(longName), overwriteTotal = 1),
            Impact(Impact.Kind.WRITE, overwrite = listOf(longName), overwriteTotal = 99, ledgerRows = 999),
            Impact(Impact.Kind.WRITE, originalsTouched = true),
            Impact(Impact.Kind.WRITE, refused = true),
            Impact(Impact.Kind.DELETE, deleteCount = 12_345, deleteBytes = 9_876_543_210L, ledgerRows = 4_321),
            Impact(Impact.Kind.DELETE, refused = true),
            Impact(Impact.Kind.MOVE, moved = 1, collisions = 1),
            Impact(Impact.Kind.CLICK, nodeText = longName, formKnown = true, formFilled = 12, formRequiredEmpty = 3),
            Impact(Impact.Kind.CLICK, nodeText = "提交", crossDomain = true),
            Impact(Impact.Kind.SCHEDULE, nextFireAtMs = 1_790_000_000_000L),
            Impact(Impact.Kind.SKILL_REPLAY, stepsLo = 12, stepsHi = 14, touches = 999),
            Impact(Impact.Kind.SKILL_GOVERN, retireCount = 12, promoteCount = 3, keepCount = 20),
            Impact(Impact.Kind.SKILL_GOVERN, keepCount = 20),
            Impact(Impact.Kind.DIARY_MAINTAIN, archiveCount = 1_200, cleanCount = 999),
            Impact(Impact.Kind.WRITE, unknown = true),
        )
        for (c in cases) {
            val t = ImpactLine.text(c)
            assertTrue("这类动作必须有一句话：$c", t != null && t.isNotBlank())
            assertFalse("绝不许含换行（那会变两行）：『$t』", t!!.contains('\n') || t.contains('\r'))
            assertTrue("超 30 字了（${t.length}）：『$t』", t.length <= ImpactLine.MAX_CHARS)
        }
    }

    @Test
    fun `判据_后果未知四个字`() {
        assertEquals("后果未知", ImpactLine.text(Impact(Impact.Kind.DELETE, unknown = true)))
    }

    @Test
    fun `判据_没有可说的动作返回null_界面就该一个字不显示`() {
        assertNull(ImpactLine.text(Impact()))
        assertNull(ImpactLine.text(Impact(Impact.Kind.NONE)))
    }

    @Test
    fun `判据_截断兜底自己也要守字数`() {
        val huge = "字".repeat(200)
        assertEquals(ImpactLine.MAX_CHARS, ImpactLine.fit(huge).length)
        assertEquals("带换行的会被压成一行", "a b", ImpactLine.fit("a\nb"))
    }

    // ── 正例文案（与派工单原文的例子对齐）────────────────────────────────

    @Test
    fun `正例_覆盖2个涉1条已发台账`() {
        val t = ImpactLine.text(Impact(Impact.Kind.WRITE, overwriteTotal = 2, ledgerRows = 1))
        assertEquals("覆盖 2 个、涉 1 条已发台账", t)
    }

    @Test
    fun `正例_删30个约12MB含5条已发`() {
        val t = ImpactLine.text(
            Impact(Impact.Kind.DELETE, deleteCount = 30, deleteBytes = 12L * 1024 * 1024, ledgerRows = 5),
        )
        assertEquals("删 30 个文件（12.0MB）、涉 5 条已发台账", t)
    }

    @Test
    fun `正例_技能自治与日记自治的两句话`() {
        assertEquals(
            "退役 3 条、转正 1 条",
            ImpactLine.text(Impact(Impact.Kind.SKILL_GOVERN, retireCount = 3, promoteCount = 1, keepCount = 20)),
        )
        assertEquals(
            "归档 12 条、清理最多 4 条",
            ImpactLine.text(Impact(Impact.Kind.DIARY_MAINTAIN, archiveCount = 12, cleanCount = 4)),
        )
    }

    // ── 注入限流（派工单 §3.2）──────────────────────────────────────────

    private fun tc(name: String) = ToolCall("c", name, JSONObject())

    @Test
    fun `判据_R0只读一律不注入`() {
        ImpactInject.reset()
        val impact = Impact(Impact.Kind.WRITE, newFiles = 1)
        assertNull(ImpactInject.noteFor(tc("read_file"), impact, 1000))
        assertNull(ImpactInject.noteFor(tc("list_dir"), impact, 1000))
        assertNull(ImpactInject.noteFor(tc("browser_snapshot"), impact, 1000))
    }

    @Test
    fun `判据_非只读写入才注入且不超40字`() {
        ImpactInject.reset()
        val impact = Impact(Impact.Kind.WRITE, overwriteTotal = 2, ledgerRows = 1)
        // 第二百二十三批：门槛跟着矩阵口径走——write_file 是 R1（写工作区内）而不是 220 临时表
        // 里的"R2"，所以这里断言的是"非只读就注入"，不再是"R2 才注入"。
        val note = ImpactInject.noteFor(tc("write_file"), impact, 1000)
        assertTrue("写盘（矩阵 R1）要注入", note != null)
        assertTrue("超 40 字：『$note』", note!!.length <= ImpactInject.MAX_CHARS)
        assertTrue("要带前缀，模型才认得出这是事实", note.startsWith("【影响】"))
    }

    @Test
    fun `判据_同一句8秒内不重复注入`() {
        ImpactInject.reset()
        val impact = Impact(Impact.Kind.DELETE, deleteCount = 2)
        assertTrue(ImpactInject.noteFor(tc("delete_path"), impact, 1_000) != null)
        assertNull("1 秒后又来同一句 ⇒ 不注", ImpactInject.noteFor(tc("delete_path"), impact, 2_000))
        assertTrue("过了去重窗 ⇒ 可以再注一次", ImpactInject.noteFor(tc("delete_path"), impact, 10_000) != null)
    }

    @Test
    fun `判据_未知结论不注入_没有信息量就别吵`() {
        ImpactInject.reset()
        assertNull(ImpactInject.noteFor(tc("write_file"), Impact(Impact.Kind.WRITE, unknown = true), 1000))
    }

    @Test
    fun `判据_只读类impact不注入`() {
        ImpactInject.reset()
        assertNull(ImpactInject.noteFor(tc("write_file"), Impact(), 1000))
    }

    @Test
    fun `判据_档位口径_唯一来源是风险矩阵`() {
        // 第二百二十三批：`ImpactRisk`（临时口径）已删，档位只从 `RiskMatrix` 来。
        // 三个读数与本批之前不同，是**删掉第二套边界**的必然结果（报告 §2 逐条对账）：
        //   write_file 2→1（219 口径：写工作区内是 R1）；delete_path 3→2（R2 改外面的）；
        //   browser_click 1→2（点网页会改外面状态）。
        assertEquals(0, RiskGate.tierOf(tc("read_file")))
        assertEquals(2, RiskGate.tierOf(tc("browser_click")))
        assertEquals(1, RiskGate.tierOf(tc("write_file")))
        assertEquals(2, RiskGate.tierOf(tc("delete_path")))
        // 终端不在四档里（矩阵显式豁免）⇒ 沿用本批之前的保守读数 2，不借机改行为
        assertEquals(2, RiskGate.tierOf(tc("bash")))
        // 未分类同样落 R2（fail-closed），不许掉到 0
        assertEquals(2, RiskGate.tierOf(tc("ghost_tool")))
    }
}
