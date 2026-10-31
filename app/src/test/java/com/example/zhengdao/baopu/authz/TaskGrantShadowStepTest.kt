// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十六批 §3：249 六步的第⑤步**第一次真去问适配器**，本文件钉的是翻态那一段——
// 适配器交回什么结论，第⑤步就给出什么下场，四态各有各的下场：
//   CONSISTENT   ⇒ 六步走完、免这一张卡；
//   MISMATCH     ⇒ 停下，码 `shadow_mismatch`（第五步记"不过"）；
//   MISWIRED     ⇒ 停下，码 `shadow_miswired`；
//   UNVERIFIABLE ⇒ 停下，码 `no_data`，第五步记**未验**（不是"不过"），原因码进明细。
//
// 为什么不直接用真沙盒跑这五条：判据层与沙盒各有自己的用例（[ShadowAdapterTest]），
// 这一层要钉的是**四态到六种下场的映射**——那就得能把四态各喂一次。
// 唯一用真装配状态的一条是"没装沙盒"：那正是 276 交付时生产的形状，它的读数
// （六步停在 NO_DATA、影子三态=未验）必须逐字不变，否则 276 那批的门禁就白钉了。
//
// 负例④（`UNVERIFIABLE` 当 `CONSISTENT`）的行为层那一半钉在 `负例④_只有CONSISTENT能免卡`；
// 字面那一半（`shadowAllows` 只许等号比 CONSISTENT）在 `ShadowWiringGateTest`。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.inspect.ImpactShape
import com.example.zhengdao.baopu.inspect.RollbackPoint
import com.example.zhengdao.baopu.inspect.ShadowAdapter
import com.example.zhengdao.baopu.inspect.ShadowInput
import com.example.zhengdao.baopu.inspect.ShadowRun
import com.example.zhengdao.baopu.inspect.ShadowSandbox
import com.example.zhengdao.baopu.inspect.ShadowUnverifiable
import com.example.zhengdao.baopu.inspect.ShadowVerdict
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGrantShadowStepTest {

    private val ws = Files.createTempDirectory("zd-296-step-").toFile()
    private val copyRoot = "${ws.absolutePath}\\.shadow\\op-step"

    @After
    fun 收场把沙盒摘掉() {
        ShadowAdapter.install(null)
    }

    private class Fake(private val run: ShadowRun) : ShadowSandbox {
        var calls = 0

        override fun rehearse(input: ShadowInput): ShadowRun {
            calls++
            return run
        }

        override fun discard(copyRoot: String) = Unit
    }

    /** 构造层要交的那份输入（形状与 `TaskGrantWire.shadowInputOf` 一致；账落点空着＝只判定不落账）。 */
    private fun inputOf(tool: String = "write_file", declared: List<String> = listOf("a")) = ShadowInput(
        opId = "op-step",
        tool = tool,
        workspaceRoot = ws.absolutePath,
        declaredPaths = declared,
        content = "一行\n",
        overwrite = true,
        risk = RiskClass.R2,
        reversible = false,
        sessionId = 7L,
    )

    /** 一份什么都对得上的实测，只在要钉的那一格上被动过。 */
    private fun runOf(observed: List<String>, untouched: Boolean? = true) = ShadowRun(
        built = true,
        note = "副本 1 个对象",
        copyRoot = copyRoot,
        existedBefore = listOf("a"),
        observedPaths = observed,
        observed = ImpactShape(overwriteTotal = observed.size, touchedPaths = observed),
        rollback = RollbackPoint("$copyRoot/.rollback/a.bak", exists = true, bytes = 8L, originalBytes = 8L),
        realUntouched = untouched,
        landed = true,
        trialOk = true,
        trialNote = "已覆盖 a",
    )

    private fun requestWith(input: ShadowInput?) = AuthzFixtures
        .request(objectId = "a", opId = "op-step", shadow = ShadowSignal.None)
        .copy(shadowInput = input)

    private fun step(d: GateDecision) = d.steps.first { it.key == GrantStep.SHADOW }

    // ── 判据：四态各有各的下场 ────────────────────────────────────────────────

    @Test
    fun `第五步_CONSISTENT_六步走完免这一张卡`() {
        val fake = Fake(runOf(listOf("$copyRoot/a")))
        ShadowAdapter.install(fake)
        val d = TaskGrantGate.evaluate(requestWith(inputOf()), AuthzFixtures.state())
        assertEquals("第⑤步要真问适配器一次", 1, fake.calls)
        assertEquals(GateOutcome.ALLOW, d.outcome)
        assertEquals(TaskGrantGate.CODE_ALLOW, d.code)
        assertEquals(6, d.steps.size)
        assertTrue(step(d).passed == true)
        assertTrue(step(d).detail.contains(ShadowVerdict.CONSISTENT.wire))
    }

    @Test
    fun `第五步_MISMATCH_停下并报shadow_mismatch`() {
        val fake = Fake(runOf(listOf("$copyRoot/a", "$copyRoot/b")))
        ShadowAdapter.install(fake)
        val d = TaskGrantGate.evaluate(requestWith(inputOf()), AuthzFixtures.state())
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_SHADOW_MISMATCH, d.code)
        assertTrue("跑出来对不上才记「不过」", step(d).passed == false)
        assertTrue(step(d).detail.contains(ShadowVerdict.MISMATCH.cn))
        assertEquals("停在第五步，第六步不该记", 5, d.steps.size)
        assertFalse(d.allow)
    }

    @Test
    fun `第五步_MISWIRED_停下并报shadow_miswired`() {
        val real = ws.absolutePath.replace('\\', '/')
        val fake = Fake(runOf(listOf("$real/a")))
        ShadowAdapter.install(fake)
        val d = TaskGrantGate.evaluate(requestWith(inputOf()), AuthzFixtures.state())
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_SHADOW_MISWIRED, d.code)
        assertTrue(step(d).passed == false)
    }

    @Test
    fun `第五步_UNVERIFIABLE_停在no_data且原因码进明细`() {
        val fake = Fake(runOf(listOf("$copyRoot/a")))
        ShadowAdapter.install(fake)
        // 306 批起 delete_path 已接影子（走它自己的用例）；「没接」的例子换成 bash。
        val d = TaskGrantGate.evaluate(requestWith(inputOf(tool = "bash")), AuthzFixtures.state())
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals("没接适配器的工具既不许冒充红、也不许冒充绿", TaskGrantGate.CODE_NO_DATA, d.code)
        assertTrue("验不了记未验（与 239 同口径）", step(d).passed == null)
        assertTrue(step(d).detail.contains("reason=${ShadowUnverifiable.UNSUPPORTED}"))
        assertEquals("工具没接就一次都不许碰沙盒", 0, fake.calls)
    }

    @Test
    fun `第五步_构造层没交输入_连问都没问`() {
        val fake = Fake(runOf(listOf("$copyRoot/a")))
        ShadowAdapter.install(fake)
        val d = TaskGrantGate.evaluate(requestWith(null), AuthzFixtures.state())
        assertEquals(TaskGrantGate.CODE_NO_DATA, d.code)
        assertEquals("这个动作没跑影子预演", step(d).detail)
        assertTrue(step(d).passed == null)
        assertEquals("没有输入就不许去碰盘", 0, fake.calls)
    }

    @Test
    fun `第五步_调用方已交结论_不重跑第二次`() {
        // 影子是有代价的：同一次动作只许跑一遍（276 的 N6 注入那一格本来就直接给结论）。
        val fake = Fake(runOf(listOf("$copyRoot/a")))
        ShadowAdapter.install(fake)
        val req = AuthzFixtures.request(objectId = "a", opId = "op-step").copy(shadowInput = inputOf())
        val d = TaskGrantGate.evaluate(req, AuthzFixtures.state())
        assertEquals(GateOutcome.ALLOW, d.outcome)
        assertEquals(0, fake.calls)
    }

    // ── 276 口径不许漂：没装沙盒时，六步的读数与接线那批逐字相同 ────────────────

    @Test
    fun `第五步_没装沙盒时读数与276那一份逐字相同`() {
        ShadowAdapter.install(null)
        val d = TaskGrantGate.evaluate(requestWith(inputOf()), AuthzFixtures.state())
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_NO_DATA, d.code)
        assertEquals(
            listOf("对象=过", "动作=过", "上限=过", "protected/hardDeny=过", "影子三态=未验"),
            d.steps.map { it.line().substringBefore("（") },
        )
        val signal = TaskGrantGate.shadowSignalFor(inputOf())
        assertTrue(signal is ShadowSignal.Rehearsed)
        assertEquals(ShadowVerdict.UNVERIFIABLE, (signal as ShadowSignal.Rehearsed).verdict)
        assertEquals("装配缺陷要说得出是哪一格", ShadowUnverifiable.NO_SANDBOX, signal.reason)
    }

    @Test
    fun `shadowSignalFor_没有输入时一个字节都不碰`() {
        val fake = Fake(runOf(listOf("$copyRoot/a")))
        ShadowAdapter.install(fake)
        assertEquals(ShadowSignal.None, TaskGrantGate.shadowSignalFor(null))
        assertEquals(0, fake.calls)
    }

    // ── 负例④：免卡凭据只有 CONSISTENT 一种 ──────────────────────────────────

    @Test
    fun `负例④_只有CONSISTENT能免卡`() {
        ShadowVerdict.entries.forEach { v ->
            assertEquals(
                "$v 被当成免卡凭据了（§7：影子三态只有一种放行）",
                v == ShadowVerdict.CONSISTENT,
                TaskGrantGate.shadowAllows(v),
            )
        }
    }
}
