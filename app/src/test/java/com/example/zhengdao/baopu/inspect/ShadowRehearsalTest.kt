// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 §9：影子执行（I6）。四条负例逐条钉在这里：
// ①影子连真实目标 ②给 R0 也跑影子 ③影子结果写进模型上下文 ④不一致还放行。
//
// 判据是纯函数，落盘那本账单独测（临时目录）；"谁去建副本、谁去跑工具"在装配层，
// 本批不接（挂接点写在交付报告里），所以这里测的是**判定**，不是执行。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.RiskClass
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowRehearsalTest {

    private val now = 1_760_000_000_000L
    private val realRoot = "/data/user/0/com.example.zhengdao/files/抱朴/工作区"
    private val shadowRoot = "$realRoot/.shadow"

    private fun shape(newFiles: Int = 0, deleteCount: Int = 0, paths: List<String> = emptyList()) =
        ImpactShape(newFiles = newFiles, deleteCount = deleteCount, touchedPaths = paths)

    private fun okProbe(name: String = "运单状态") = SuccessProbe(name, observable = true, expected = "已发出", actual = "已发出")

    private fun goodRollback() = RollbackPoint(path = "$shadowRoot/.rollback/a.bak", exists = true, bytes = 128L, originalBytes = 128L)

    private fun cmp(
        claimed: ImpactShape,
        observed: ImpactShape?,
        shadow: String = shadowRoot,
        real: String = realRoot,
        rollback: RollbackPoint? = goodRollback(),
        probes: List<SuccessProbe> = listOf(okProbe()),
    ) = ShadowRehearsal.compare(
        claimed = claimed,
        observed = observed,
        shadowRoot = shadow,
        realRoot = real,
        rollback = rollback,
        probes = probes,
    )

    // ── 负例②：只对 R2+ 与不可逆跑影子 ──────────────────────────────────────

    @Test
    fun `影子_R0与R1 reversible不跑`() {
        assertFalse(ShadowRehearsal.shouldRehearse(RiskClass.R0, reversible = true))
        assertFalse(ShadowRehearsal.shouldRehearse(RiskClass.R1, reversible = true))
    }

    @Test
    fun `影子_R2R3与不可逆必跑_没档也跑`() {
        assertTrue(ShadowRehearsal.shouldRehearse(RiskClass.R2, reversible = true))
        assertTrue(ShadowRehearsal.shouldRehearse(RiskClass.R3, reversible = true))
        assertTrue(ShadowRehearsal.shouldRehearse(RiskClass.R0, reversible = false))
        // 没判过档 ⇒ 按最严的走（fail-closed）
        assertTrue(ShadowRehearsal.shouldRehearse(null, reversible = true))
    }

    // ── 三件事：影响一致／回滚点可用／判据成立 ─────────────────────────────

    @Test
    fun `影子_三件事全对才放行`() {
        val out = cmp(shape(newFiles = 2, paths = listOf("$shadowRoot/a", "$shadowRoot/b")), shape(2, paths = listOf("$shadowRoot/a", "$shadowRoot/b")))
        assertEquals(ShadowVerdict.CONSISTENT, out.verdict)
        assertTrue(out.allow)
        assertEquals(3, out.items.size)
        assertTrue(out.items.all { it.passed == true })
        assertEquals("影子：${ShadowVerdict.CONSISTENT.cn}", out.modelLine)
    }

    @Test
    fun `影子_负例④不一致就拦住`() {
        val out = cmp(shape(newFiles = 1), shape(newFiles = 1, deleteCount = 3, paths = listOf("$shadowRoot/x")))
        assertEquals(ShadowVerdict.MISMATCH, out.verdict)
        assertFalse("不一致却 allow=true 就是形同虚设", out.allow)
        assertTrue(out.items.first { it.key == ShadowRehearsal.ITEM_EFFECT }.passed == false)
    }

    @Test
    fun `影子_回滚点不可用不算通过`() {
        val broken = RollbackPoint(path = "$shadowRoot/.rollback/a.bak", exists = false)
        val out = cmp(shape(1), shape(1), rollback = broken)
        assertEquals(ShadowVerdict.MISMATCH, out.verdict)
        assertFalse(out.allow)
        assertFalse(RollbackPoint("x", exists = true, bytes = 0L).usable)
        assertFalse(RollbackPoint("x", exists = true, bytes = 9L, originalBytes = 8L).usable)
    }

    @Test
    fun `影子_没查回滚点只记验不了`() {
        val out = cmp(shape(1), shape(1), rollback = null)
        assertEquals(ShadowVerdict.UNVERIFIABLE, out.verdict)
        assertFalse(out.allow)
        assertEquals(ShadowVerdict.UNVERIFIABLE.cn, out.modelLine.removePrefix("影子："))
    }

    @Test
    fun `影子_没有验收判据不许冒充绿`() {
        assertEquals(ShadowVerdict.UNVERIFIABLE, cmp(shape(1), shape(1), probes = emptyList()).verdict)
        // 判据本身不可观察（空口「完成」）⇒ 同样验不了
        val hollow = SuccessProbe("完成", observable = false)
        assertFalse(hollow.satisfied)
        assertEquals(ShadowVerdict.UNVERIFIABLE, cmp(shape(1), shape(1), probes = listOf(hollow)).verdict)
    }

    @Test
    fun `影子_推演自己标了未知_验不了`() {
        assertEquals(ShadowVerdict.UNVERIFIABLE, cmp(shape(1).copy(unknown = true), shape(1)).verdict)
    }

    @Test
    fun `影子_实测没跑出来_验不了`() {
        assertEquals(ShadowVerdict.UNVERIFIABLE, cmp(shape(1), observed = null).verdict)
    }

    // ── 负例①：影子连真实目标 ───────────────────────────────────────────────

    @Test
    fun `影子_负例①副本根等于真盘根_先红`() {
        val out = cmp(shape(1, paths = listOf("$realRoot/a")), shape(1, paths = listOf("$realRoot/a")), shadow = realRoot)
        assertEquals(ShadowVerdict.MISWIRED, out.verdict)
        assertFalse(out.allow)
    }

    @Test
    fun `影子_实测路径越界到真盘_也算接错`() {
        val out = cmp(shape(1), shape(1, paths = listOf("$realRoot/资料库/原始/合同.pdf")))
        assertEquals(ShadowVerdict.MISWIRED, out.verdict)
        assertFalse(out.allow)
    }

    // ── 负例③：影子结果不许写进模型上下文 ──────────────────────────────────

    @Test
    fun `影子_负例③给模型的那一句不含明细`() {
        val leaked = cmp(
            shape(newFiles = 1),
            shape(newFiles = 1, deleteCount = 3, paths = listOf("$shadowRoot/秘密/合同.pdf")),
        )
        assertEquals(ShadowVerdict.MISMATCH, leaked.verdict)
        val line = leaked.modelLine
        assertTrue(line.length <= InspectLimits.MAX_LINE_CHARS)
        assertFalse("路径进了模型那一句：$line", line.contains("shadowRoot") || line.contains(".pdf") || line.contains("/"))
        assertFalse(line.contains("秘密"))
        // 逐项名可以给（那是定性），明细计数不给
        assertTrue(line.contains(ShadowVerdict.MISMATCH.cn))
    }

    // ── 影子台账（落盘那一本）───────────────────────────────────────────────

    private fun root(): File = Files.createTempDirectory("zd-239-shadow-").toFile()

    @Test
    fun `影子台账_写一行读一行_键是会话步`() {
        val r = root()
        val out = cmp(shape(1, paths = listOf("$shadowRoot/a")), shape(1, paths = listOf("$shadowRoot/a")))
        val entry = ShadowLedger.entryOf(out, listOf("file_write"), RiskClass.R2, true, 9L, 4, now)
        assertEquals("9:4", entry.key)
        assertTrue(ShadowLedger.append(r, entry))
        val back = ShadowLedger.read(r)
        assertEquals(1, back.size)
        assertEquals(ShadowVerdict.CONSISTENT, back.first().verdict)
        assertEquals(listOf("file_write"), back.first().actions)
        assertEquals(1, back.first().claimed.newFiles)
        assertEquals("R2", back.first().riskWire)
        assertEquals(setOf("9:4"), ShadowLedger.keysOf(back))
        assertTrue(ShadowLedger.violations(back).isEmpty())
    }

    @Test
    fun `影子台账_一次预演一行_不覆盖历史`() {
        val r = root()
        val out = cmp(shape(1), shape(1, deleteCount = 2))
        repeat(3) { i -> ShadowLedger.append(r, ShadowLedger.entryOf(out, listOf("t"), null, true, 9L, i, now + i)) }
        assertEquals(3, ShadowLedger.read(r).size)
        assertEquals(3, ShadowLedger.violations(ShadowLedger.read(r)).size)
    }

    @Test
    fun `影子台账_认不出的判定态整行丢`() {
        val r = root()
        java.io.File(r, ShadowLedger.FILE_NAME).appendText(
            """{"id":"sh:1:1:1","verdict":"nonsense"}""",
            Charsets.UTF_8,
        )
        assertTrue(ShadowLedger.read(r).isEmpty())
        java.io.File(r, ShadowLedger.FILE_NAME).appendText("这不是 JSON\n", Charsets.UTF_8)
        assertTrue(ShadowLedger.read(r).isEmpty())
    }

    // ── I6 巡按项：放行侧与缺口侧分开说 ─────────────────────────────────────

    private fun trace(sid: Long, step: Int, need: Boolean, at: Long = now) =
        ShadowTrace(atMs = at, sessionId = sid, step = step, tool = "dir_delete", requiresShadow = need)

    private fun entryOfVerdict(sid: Long, step: Int, verdict: ShadowVerdict) = ShadowEntry(
        id = "sh:$sid:$step:$now",
        atMs = now,
        sessionId = sid,
        step = step,
        riskWire = "R2",
        reversible = true,
        actions = listOf("dir_delete"),
        claimed = shape(1),
        observed = shape(1),
        items = listOf(ShadowItem(ShadowRehearsal.ITEM_EFFECT, "影响与推演一致", true)),
        verdict = verdict,
        modelLine = "影子：${verdict.cn}",
    )

    @Test
    fun `I6_不符仍执行_命中`() {
        val traces = listOf(trace(9L, 1, need = true))
        val entries = listOf(entryOfVerdict(9L, 1, ShadowVerdict.MISMATCH))
        val f = ShadowCheck.check(traces, entries)
        assertEquals(InspectVerdict.HIT, f.verdict)
        assertEquals(1, f.count)
        assertTrue(f.oneLine.contains("放行"))
    }

    @Test
    fun `I6_该预演没预演_命中`() {
        val f = ShadowCheck.check(
            listOf(trace(9L, 2, need = true)),
            listOf(entryOfVerdict(9L, 1, ShadowVerdict.CONSISTENT)),
        )
        assertEquals(InspectVerdict.HIT, f.verdict)
        assertTrue(f.oneLine.contains("没预演"))
    }

    @Test
    fun `I6_全一致_清白`() {
        val f = ShadowCheck.check(
            listOf(trace(9L, 1, need = true)),
            listOf(entryOfVerdict(9L, 1, ShadowVerdict.CONSISTENT)),
        )
        assertEquals(InspectVerdict.CLEAR, f.verdict)
    }

    @Test
    fun `I6_跑不出结论不冒充清白`() {
        val f = ShadowCheck.check(
            listOf(trace(9L, 1, need = true)),
            listOf(entryOfVerdict(9L, 1, ShadowVerdict.UNVERIFIABLE)),
        )
        assertEquals(InspectVerdict.NO_DATA, f.verdict)
        assertFalse(f.oneLine.contains("全与推演一致"))
    }

    @Test
    fun `I6_影子台账零行_说没接线而不是说清白`() {
        val f = ShadowCheck.check(listOf(trace(9L, 1, need = true)), emptyList())
        assertEquals(InspectVerdict.NO_DATA, f.verdict)
        assertTrue(f.suggestion.contains("挂接点"))
    }

    @Test
    fun `I6_窗口内没有要跑影子的动作_清白`() {
        val f = ShadowCheck.check(listOf(trace(9L, 1, need = false)), emptyList())
        assertEquals(InspectVerdict.CLEAR, f.verdict)
    }
}
