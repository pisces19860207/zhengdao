// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L4 留痕**（法典卷一：一切副作用必落 208）。
//
// ## 判据（可执行）
// ① **有副作用就有账**：一次成功的写动作（引擎侧留下 `run_log` Acting 痕迹）在 208 里
//    必须有一条可配对的行（`DecisionKind.AUTO_ACT`，`evidence = "tool:<名>:<目标>"`）；
// ② **漏行必须报**：`AuditGapCheck` 的集合差非空 ⇒ 勘验给 HIT，不许静默；
// ③ **落行点是单点**：`GuardPipeline` 里「!readOnly ⇒ 落 208」的块必须在（拆了它就等于
//    所有写动作集体无账）。
//
// ## 负例（本文件自证会红）
// 同样的写动作，账本里**少写一条** ⇒ 对拍必须报 gap（且勘验 HIT）；
// 落行块被拆掉（源码探针）⇒ 也红。两条都是「不落账的写入」被抓到的样子。
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

class L4LedgerGateTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun cleanup() {
        tempRoots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newRoot(): File =
        java.nio.file.Files.createTempDirectory("l4-208-").toFile().also { tempRoots += it }

    /** 一次成功的写动作（引擎侧痕迹的最小形状）。 */
    private fun writeAct(tool: String, atMs: Long) = WriteTrace(atMs, tool, sessionId = 7L, step = 3)

    /** 真账本落一行（走生产同一个 build + recordTo，不手搓 JSON）。 */
    private fun bookWrite(root: File, tool: String, target: String, atMs: Long): Boolean {
        val draft = DecisionDraft(
            kind = DecisionKind.AUTO_ACT,
            actor = DecisionActor.MODEL,
            reason = "写文件：$target",
            evidence = "tool:$tool:$target",
            before = "",
            after = target,
            atMs = atMs,
        )
        return DecisionLedger.recordTo(root, draft) != null
    }

    // ── 正例：写了就落账，对拍无 gap ─────────────────────────────────────────

    @Test
    fun `L4_正例_写动作落208_对拍零漏行`() {
        val root = newRoot()
        val act = writeAct("write_file", atMs = 1_000L)
        assertTrue("账必须先落得进去（否则后面的对拍没意义）", bookWrite(root, "write_file", "产物/a.md", 1_000L))
        val ledger = DecisionLedger.read(root)
        assertEquals("账本应有 1 行", 1, ledger.size)

        val result = AuditGapCheck.compare(listOf(act), ledger)
        assertEquals("有账的写动作不该被算成漏行", 0, result.gaps.size)
        assertEquals("窗口内应核到 1 条", 1, result.checked)
    }

    // ── 负例：不落账的写入必须被抓到 ─────────────────────────────────────────

    @Test
    fun `L4_负例_写动作不落账_对拍必须抓出漏行`() {
        val root = newRoot()
        // 另一件事落了账（保证账窗存在——没有账窗时 AuditGapCheck 会整体判「超出可见账窗」，
        // 那是另一种结论，不能拿来冒充「漏行被抓」）。
        bookWrite(root, "read_file", "产物/b.md", 900L)
        val act = writeAct("write_file", atMs = 1_000L)

        val result = AuditGapCheck.compare(listOf(act), DecisionLedger.read(root))
        assertEquals(
            "不落账的写入必须被算成漏行：${result.gaps.map { it.tool }}",
            1,
            result.gaps.size,
        )
        assertEquals("漏行必须指到那一次写动作", "write_file", result.gaps[0].tool)

        val finding = AuditGapCheck.check(listOf(act), DecisionLedger.read(root))
        assertEquals("漏行必须出结论（HIT），不许静默", InspectVerdict.HIT, finding.verdict)
        assertTrue("结论行不许空", finding.oneLine.isNotBlank())
        assertTrue("证据要指到那一条", finding.evidence.any { it.contains("write_file") })
    }

    // ── 门③：落行点是单点，块不许拆 ─────────────────────────────────────────

    @Test
    fun `L4_门_GuardPipeline的副作用落行块必须在_拆了就红`() {
        val src = LawIo.codeOnly(LawIo.read("$MAIN/baopu/agent/GuardPipeline.kt"))
        assertTrue(
            "GuardPipeline 里「!spec.readOnly ⇒ DecisionLedger.record」的块不见了：所有写动作会集体无账",
            src.contains("!spec.readOnly") && src.contains("DecisionLedger.record("),
        )
        // 探针对空源码必须不认（防恒绿）
        assertFalse(src.isEmpty())
        assertFalse("空源码不许通过", LawIo.codeOnly("").contains("!spec.readOnly"))
    }

    private companion object {
        const val MAIN = LawIo.MAIN_ROOT
    }
}
