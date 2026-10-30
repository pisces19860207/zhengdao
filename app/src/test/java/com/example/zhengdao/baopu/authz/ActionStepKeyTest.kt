// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零六批 · step 联键（开工令判据逐条落地）：
//   ① 同轮两次动作步号不同；② 跨轮重开（步号从 1 重记、轮号单调向前）；
//   ③ 免卡结论能引用具体 (roundId, step)；④ 影子账那一行落真步号（旧口径恒 0 的断点闭合）；
//   ⑤ 旧口径账行（step=0）照原样收、不改写（口径变更点只标在注释与读侧缺省上）。
//
// 源级那一半（RunEngine 是唯一开轮点）也钉在这里：行为用例管"钟怎么走"，
// 管不了"引擎有没有真的每轮开一轮"——239 的教训（全绿但零调用）同一条。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.inspect.ImpactShape
import com.example.zhengdao.baopu.inspect.RollbackPoint
import com.example.zhengdao.baopu.inspect.ShadowAdapter
import com.example.zhengdao.baopu.inspect.ShadowInput
import com.example.zhengdao.baopu.inspect.ShadowLedger
import com.example.zhengdao.baopu.inspect.ShadowRun
import com.example.zhengdao.baopu.inspect.ShadowSandbox
import com.example.zhengdao.baopu.inspect.ShadowVerdict
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionStepKeyTest {

    private val ws = Files.createTempDirectory("zd-306-step-").toFile()
    private val copyRoot = "${ws.absolutePath}\\.shadow\\op-step"

    @Before
    fun 开轮前先清钟() {
        ActionStepClock.resetForTest()
    }

    @After
    fun 收场() {
        ShadowAdapter.install(null)
        ActionStepClock.resetForTest()
    }

    /** 交回一份什么都对得上的实测（只喂第⑤步的翻态，判据层另有自己的用例）。 */
    private class Fake(private val run: ShadowRun) : ShadowSandbox {
        override fun rehearse(input: ShadowInput): ShadowRun = run

        override fun discard(copyRoot: String) = Unit
    }

    private fun runOf(observed: List<String>) = ShadowRun(
        built = true,
        note = "副本 1 个对象",
        copyRoot = copyRoot,
        existedBefore = listOf("a"),
        observedPaths = observed,
        observed = ImpactShape(overwriteTotal = observed.size, touchedPaths = observed),
        rollback = RollbackPoint("$copyRoot/.rollback/a.bak", exists = true, bytes = 8L, originalBytes = 8L),
        realUntouched = true,
        landed = true,
        trialOk = true,
        trialNote = "已覆盖 a",
    )

    /** 构造层交的那份输入（step 缺省 0＝旧口径的形状；盖章之后不许再是 0）。 */
    private fun inputOf() = ShadowInput(
        opId = "op-step",
        tool = "write_file",
        workspaceRoot = ws.absolutePath,
        declaredPaths = listOf("a"),
        content = "一行\n",
        overwrite = true,
        sessionId = 7L,
    )

    // ── 判据①：同轮两次动作，步号不同 ────────────────────────────────────────

    @Test
    fun `同轮两次动作_步号不同`() {
        val round = ActionStepClock.beginRound()
        val m1 = ActionStepClock.stepFor("op-a")
        val m2 = ActionStepClock.stepFor("op-b")
        assertEquals("第一个动作是第 1 步", 1, m1.step)
        assertEquals("同轮的第二个动作不许还停在 1", 2, m2.step)
        assertEquals(round.roundId, m1.roundId)
        assertEquals("同一轮里轮号不换", m1.roundId, m2.roundId)
        assertNotEquals(m1, m2)
    }

    // ── 判据②：跨轮重开——步号从 1 重记，轮号单调向前 ─────────────────────────

    @Test
    fun `跨轮重开_步号从1重记_轮号单调向前`() {
        val r1 = ActionStepClock.beginRound()
        ActionStepClock.stepFor("op-a1")
        ActionStepClock.stepFor("op-a2")
        val r2 = ActionStepClock.beginRound()
        assertEquals("轮号要记着往前走", r1.roundId + 1, r2.roundId)
        val m = ActionStepClock.stepFor("op-b1")
        assertEquals("跨轮重开：新一轮第一步又是 1", 1, m.step)
        assertEquals(r2.roundId, m.roundId)
        // 老动作名在新轮里是"另一件事"：重新领号，不继承上一轮的步数
        assertEquals(2, ActionStepClock.stepFor("op-a1").step)
    }

    @Test
    fun `同opId一辈子一个号_确认卡第二遍与偏离卡重跑都是同一个动作`() {
        ActionStepClock.beginRound()
        val m1 = ActionStepClock.stepFor("op-1")
        assertEquals(m1, ActionStepClock.stepFor("op-1"))
        assertEquals(m1.step, ActionStepClock.stepFor("op-1").step)
    }

    @Test
    fun `peek只读不领号_没开过轮时轮号为0`() {
        ActionStepClock.resetForTest()
        assertEquals("没开过轮＝不在任何轮里（roundId=0，不冒充）", 0, ActionStepClock.peek("x").roundId)
        ActionStepClock.beginRound()
        assertEquals("没领过号的动作 peek 步号 0", 0, ActionStepClock.peek("nobody").step)
        ActionStepClock.stepFor("op-1")
        assertEquals(1, ActionStepClock.peek("op-1").step)
        assertEquals("peek 不许把号发给没领过的", 0, ActionStepClock.peek("nobody").step)
    }

    // ── 判据③：免卡结论能引用具体 (roundId, step) ───────────────────────────

    @Test
    fun `免卡结论引用具体轮与步`() {
        val round = ActionStepClock.beginRound()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(opId = "op-allow"), AuthzFixtures.state())
        assertEquals("六步全过才叫免卡", GateOutcome.ALLOW, d.outcome)
        assertEquals(round.roundId, d.roundId)
        val mark = ActionStepClock.peek("op-allow")
        assertEquals(mark.step, d.step)
        assertTrue("免卡的步号必须是本轮里真领过的（≥1）", d.step >= 1)
        val record = d.steps.last()
        assertEquals(GrantStep.RECORD, record.key)
        assertTrue(
            "第⑥步读数要点名轮与步：${record.detail}",
            record.detail.contains("轮 ${d.roundId} 步 ${d.step}"),
        )
    }

    // ── 判据④：第⑤步交去适配器的输入带真 (roundId, step)，影子账落在真步号上 ──

    @Test
    fun `第⑤步交去适配器的输入盖上真步号`() {
        val round = ActionStepClock.beginRound()
        val captured = mutableListOf<ShadowInput>()
        ShadowAdapter.install(object : ShadowSandbox {
            override fun rehearse(input: ShadowInput): ShadowRun {
                captured += input
                return runOf(listOf("$copyRoot/a"))
            }

            override fun discard(copyRoot: String) = Unit
        })
        val req = AuthzFixtures.request(objectId = "a", opId = "op-step", shadow = ShadowSignal.None)
            .copy(shadowInput = inputOf())
        val d = TaskGrantGate.evaluate(req, AuthzFixtures.state())
        assertEquals(GateOutcome.ALLOW, d.outcome)
        assertEquals("第⑤步要真问一次适配器", 1, captured.size)
        val stamped = captured.single()
        assertEquals("构造层交的是旧口径 0，授权链盖章后必须换真轮号", round.roundId, stamped.roundId)
        assertNotEquals("步号不许再恒 0（306 的真断点就在这一格）", 0, stamped.step)
        assertEquals(ActionStepClock.peek("op-step").step, stamped.step)
        assertEquals("结论与影子账引用同一对 (roundId, step)", d.roundId to d.step, stamped.roundId to stamped.step)
    }

    // ── 判据⑤（A3）：旧口径行照原样收，不改写；新行带轮号 ─────────────────────

    @Test
    fun `旧口径影子账行step0原样收_新行带轮号`() {
        // 2026-10-30 之前的行：没有 round 格、step=0——读侧按 0 收，键的拼法不变。
        val old = "{\"id\":\"sh:old\",\"at\":10,\"session\":7,\"step\":0,\"risk\":\"R2\"," +
            "\"reversible\":false,\"actions\":[\"write_file:a.md\"],\"verdict\":\"consistent\"," +
            "\"model_line\":\"x\"}"
        val e = ShadowLedger.parseLine(old)!!
        assertEquals(0, e.step)
        assertEquals("旧口径行的轮号按 0 收（不冒充轮号）", 0, e.roundId)
        assertEquals("联键拼法不变：老行老读法", ShadowLedger.keyOf(7, 0), e.key)
        // 新行：round 格在，照实读。
        val fresh = ShadowLedger.entryOf(
            outcome = com.example.zhengdao.baopu.inspect.ShadowOutcome(ShadowVerdict.CONSISTENT),
            actions = listOf("write_file:a.md"),
            risk = null,
            reversible = false,
            sessionId = 7L,
            step = 3,
            atMs = 20L,
            roundId = 4,
        )
        assertEquals(4, ShadowLedger.parseLine(fresh.toJson().toString())!!.roundId)
        assertEquals(3, ShadowLedger.parseLine(fresh.toJson().toString())!!.step)
    }

    // ── 源级门禁：RunEngine 是唯一开轮点，授权链按 opId 领号 ───────────────────

    @Test
    fun `RunEngine每轮入口开一轮_全仓唯一`() {
        val engine = repoFile("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val text = codeOnly(engine.readText())
        assertEquals(
            "beginRound 的调用点全仓只许在 RunEngine 一处（别处开轮＝轮号口径漂移）",
            1,
            text.occurrences("ActionStepClock.beginRound()"),
        )
        assertTrue(
            "开轮要在 run 级复位那一格（四个开局口收敛到的每轮入口）",
            text.contains("com.example.zhengdao.baopu.authz.ActionStepClock.beginRound()"),
        )
        val gate = codeOnly(repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantGate.kt").readText())
        assertTrue("六步入口按 opId 领号", gate.contains("ActionStepClock.stepFor(request.opId)"))
        assertTrue(
            "第⑤步交去适配器前要盖章（旧口径恒 0 的断点在这一行闭合）",
            gate.contains("input.copy(roundId = mark.roundId, step = mark.step)"),
        )
    }

    // ── 复用 ShadowWiringGateTest 的源码读法 ─────────────────────────────────

    private fun repoFile(rel: String): java.io.File =
        if (java.io.File(rel).exists()) java.io.File(rel) else java.io.File("app/$rel")

    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
        .joinToString("\n")

    private fun String.occurrences(needle: String): Int =
        if (needle.isEmpty()) 0 else Regex(Regex.escape(needle)).findAll(this).count()
}
