// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L15 账实不符必须报**（派工单 271）。
//
// ## 判据（可执行）
// 引擎侧动作痕迹（`run_log` Acting 行）与 208（自报账）**双向比对**后，集合差非空 ⇒
// 必须给出**结论**（`InspectVerdict.HIT` + 一条能读的结论行 + 指得到的证据），
// **不许**沉默、**不许**记成 CLEAR，也**不许**拿 NO_DATA 糊过去（法可为空 ≠ 合规）。
//
// ## 负例（本文件自证会红）
// 三条写动作只有一条有账 ⇒ 必须报 2 条差集；若判成 CLEAR/NO_DATA ⇒ 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.AuditGapCheck
import com.example.zhengdao.baopu.inspect.InspectVerdict
import com.example.zhengdao.baopu.inspect.WriteTrace
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L15AccountMismatchGateTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun cleanup() {
        tempRoots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newRoot(): File =
        java.nio.file.Files.createTempDirectory("l15-gap-").toFile().also { tempRoots += it }

    private fun book(root: File, tool: String, target: String, atMs: Long) {
        DecisionLedger.recordTo(
            root,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT,
                actor = DecisionActor.MODEL,
                reason = "写：$target",
                evidence = "tool:$tool:$target",
                before = "",
                after = target,
                atMs = atMs,
            ),
        )
    }

    // ── 正例：账实相符 ⇒ CLEAR，且不许伪造证据 ─────────────────────────────

    @Test
    fun `L15_正例_账实相符给CLEAR`() {
        val root = newRoot()
        book(root, "write_file", "产物/a.md", 1_000L)
        book(root, "write_file", "产物/b.md", 2_000L)
        val acts = listOf(
            WriteTrace(1_000L, "write_file", 7L, 1),
            WriteTrace(2_000L, "write_file", 7L, 2),
        )
        val finding = AuditGapCheck.check(acts, DecisionLedger.read(root))
        assertEquals("全对上就是 CLEAR", InspectVerdict.CLEAR, finding.verdict)
        assertEquals("没有差集就不该有命中数", 0, finding.count)
        assertTrue("CLEAR 也要给一行结论", finding.oneLine.isNotBlank())
    }

    // ── 负例：集合差非空 ⇒ 必须报 ───────────────────────────────────────────

    @Test
    fun `L15_负例_集合差非空必须出结论_不许CLEAR也不许NO_DATA`() {
        val root = newRoot()
        book(root, "write_file", "产物/a.md", 1_000L) // 三条动作，只记了一条
        val acts = listOf(
            WriteTrace(1_000L, "write_file", 7L, 1),
            WriteTrace(2_000L, "write_file", 7L, 2),
            WriteTrace(3_000L, "write_file", 7L, 3),
        )
        val result = AuditGapCheck.compare(acts, DecisionLedger.read(root))
        assertEquals("差集必须是 2 条：${result.gaps.map { it.step }}", 2, result.gaps.size)
        assertEquals("窗口内三条都要核到", 3, result.checked)

        val finding = AuditGapCheck.check(acts, DecisionLedger.read(root))
        assertFalse("差集非空却记成 CLEAR ＝ 账实不符被吞了", finding.verdict == InspectVerdict.CLEAR)
        assertFalse("差集非空不许拿 NO_DATA 糊过去（法可为空 ≠ 合规）", finding.verdict == InspectVerdict.NO_DATA)
        assertEquals("必须给 HIT", InspectVerdict.HIT, finding.verdict)
        assertEquals("命中 2 条", 2, finding.count)
        assertTrue("证据必须指得到那两条动作", finding.evidence.size >= 2)
        assertTrue("结论行要说清是什么：${finding.oneLine}", finding.oneLine.contains("审计漏"))
    }
}
