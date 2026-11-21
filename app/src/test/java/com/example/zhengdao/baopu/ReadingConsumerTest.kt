// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十八批 · 读数消费的**六判据夹具**。
//
// 判据①（核心）：同一输入，接读数 vs 不接 ⇒ 决定不同——三条规则各一例。
// 判据②：provider 空 ⇒ 行为与接入前逐字节一致（含 reason / effective / line 全等）。
// 判据③：关掉消费者（ReadingPorts.detach）⇒ 回到原行为；且 provider 抛错回退原行为。
// 判据④：源码断言——只影响三种行为（ask / degrade / hint），不得写其它状态。
// 判据⑤：判定权不迁——必问铁律 / 暂停 / 熔断炙读数也压不动、放不出。
// 判据⑥：detekt / lint / 水位 / 全量单测，走命令行（不在本文件）。
//
// 全部对照都在**纯函数层**做（MediatorDecide.decideWithReadings / ReadingCodex.evaluate），
// 不依赖真机、不依赖落盘：这正是"读数提供依据、调解器做决定"的可测形态。
package com.example.zhengdao.baopu

import com.example.zhengdao.law.LawIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingConsumerTest {

    private val calm = HarmonyMediator.State()
    private val profile = PatienceProfile()

    private fun baseAsk(asked: Int = 0, state: HarmonyMediator.State = calm, mustAsk: Boolean = false) =
        MediatorDecide.shouldAsk(state, profile, asked, mustAsk)

    // ── 判据①：三条规则各一例"同一输入、两种决定" ────────────────────────────

    @Test
    fun `规则1_卡步相同同参重试2次_同一宽松输入从弹翻成压并降级`() {
        val base = baseAsk(asked = 0) // CALM、没问过：接线前照常弹
        assertTrue(base.allowed)
        assertFalse(base.effective)

        val noReading = MediatorDecide.decideWithReadings(base, ReadingCodex.empty)
        val withReading = MediatorDecide.decideWithReadings(base, stuckAdvice())

        // 无读数：原样；有读数：弹→压 + 降级 + 提示无
        assertFalse("无读数不许改决定", noReading.changed)
        assertEquals(base, noReading.advice)
        assertTrue("有读数决定必须变", withReading.changed)
        assertFalse("问不问人：弹→压", withReading.advice.allowed)
        assertTrue("调解器真下了结论", withReading.advice.effective)
        assertTrue("降不降级：必须降级", withReading.degrade)
        assertFalse("规则1不含提示", withReading.hint != null)
        assertTrue(withReading.affectLines.any { it.contains(ReadingCodex.REASON_STUCK) })
    }

    @Test
    fun `规则2_RPE连负同因_同一宽松输入从弹翻成压并只提示一次`() {
        val base = baseAsk(asked = 0)
        val withReading = MediatorDecide.decideWithReadings(base, rpeAdvice())

        assertTrue(withReading.changed)
        assertFalse("问不问人：弹→压", withReading.advice.allowed)
        assertFalse("规则2不降级", withReading.degrade)
        assertTrue("提不提示：必须提示一次", withReading.hint != null)
        assertTrue(withReading.affectLines.any { it.contains(ReadingCodex.REASON_RPE) })
    }

    @Test
    fun `规则3_越界无证据_同一耐心耗尽输入从压翻成问人`() {
        // asked=2 达到默认 askTolerance ⇒ 接线前压卡（不是必问、不是暂停、不是熔断）
        val base = baseAsk(asked = 2)
        assertFalse("基线必须先压住", base.allowed)
        assertTrue(base.effective)
        assertFalse(base.reason.startsWith("必问档"))

        val withReading = MediatorDecide.decideWithReadings(base, crossAdvice())

        assertTrue(withReading.changed)
        assertTrue("问不问人：压→问人（不放行）", withReading.advice.allowed)
        assertTrue("且带原因码", withReading.advice.reason.contains(ReadingCodex.REASON_CROSS))
        assertTrue(withReading.affectLines.any { it.contains(ReadingCodex.REASON_CROSS) })
    }

    @Test
    fun `可观测行格式_原因码斜杠来源斜杠决定变化`() {
        val lines = stuckAdvice().affectLines
        assertEquals(1, lines.size)
        val line = lines.first()
        assertTrue(line.startsWith(ReadingCodex.AFFECT_PREFIX))
        assertEquals("原因码 / 来源 / 决定变化 三段", 3, line.split(" / ").size)
        assertTrue(line.contains("弹→压"))
        assertTrue(line.contains("正常→降级"))
    }

    // ── 判据②：无读数逐字节一致 ───────────────────────────────────────────

    @Test
    fun `provider空_三行事实全无_规则零命中`() {
        assertEquals(ReadingCodex.empty, ReadingCodex.evaluate(null))
        val emptyFacts = ReadingFacts()
        assertTrue(emptyFacts.isEmpty)
        assertEquals(ReadingCodex.empty, ReadingCodex.evaluate(emptyFacts))
    }

    @Test
    fun `四种基线状态_接空读数后_决定逐字节一致`() {
        val states = listOf(
            calm to 0, // 照常弹
            calm to 2, // 预算耗尽压卡
            HarmonyMediator.State(paused = true) to 0, // 暂停压卡
            HarmonyMediator.State(mood = HarmonyMediator.Mood.LOOP) to 0, // 熔断压卡
        )
        states.forEach { (state, asked) ->
            val base = baseAsk(asked = asked, state = state)
            val d = MediatorDecide.decideWithReadings(base, ReadingCodex.empty)
            assertFalse("基线上接空读数不许改决定", d.changed)
            assertEquals("allowed 逐字等", base.allowed, d.advice.allowed)
            assertEquals("effective 逐字等", base.effective, d.advice.effective)
            assertEquals("reason 逐字等", base.reason, d.advice.reason)
            assertEquals("budget 逐字等", base.budget, d.advice.budget)
            assertEquals("line 逐字等", base.line(), d.advice.line())
            assertTrue(d.affectLines.isEmpty())
            assertFalse(d.degrade)
            assertEquals(null, d.hint)
        }
    }

    @Test
    fun `未装配端口_snapshot为null_且端口抛错也回退无读数`() {
        ReadingPorts.detach()
        assertEquals(null, ReadingPorts.snapshot())
        ReadingPorts.provider = { throw IllegalStateException("模拟取数崩溃") }
        assertEquals("provider 抛错必须吞成无读数，不许把弹卡链带崩", null, ReadingPorts.snapshot())
        ReadingPorts.detach()
    }

    // ── 判据③：关掉消费者 ⇒ 回到原行为 ────────────────────────────────────

    @Test
    fun `关掉消费者_同一输入决定回退`() {
        val base = baseAsk(asked = 0)
        ReadingPorts.provider = { stuckFacts() }
        val on = MediatorDecide.decideWithReadings(base, ReadingCodex.evaluate(ReadingPorts.snapshot()))
        assertFalse("开着：压", on.advice.allowed)

        ReadingPorts.detach()
        val off = MediatorDecide.decideWithReadings(base, ReadingCodex.evaluate(ReadingPorts.snapshot()))
        assertTrue("关掉：回弹（原行为）", off.advice.allowed)
        assertEquals(base, off.advice)
        assertFalse(off.changed)
    }

    // ── 判据④：源码断言——只影响三种行为，不得写其它状态 ────────────────────

    @Test
    fun `读数规则层零写口_不得写账本档位或上下文`() {
        val code = LawIo.codeOnly(PolicyPaths.read("ReadingCodex.kt"))
        val banned = listOf(
            "DecisionLedger", "LedgerPorts", "write(", "appendText", "writeText",
            "File(", "SharedPreferences", "context", "state =", "mood =", "note =",
            "promptNotes", "extraNotes", "record(", "save",
        )
        banned.forEach { token ->
            assertFalse("ReadingCodex 代码面出现写口/越界符号「$token」", code.contains(token))
        }
        // 合成负例自证：同一把尺塞进写口必须红
        val dirty = LawIo.codeOnly("object X { fun f() = DecisionLedger.record(d) }")
        assertTrue("源码断言尺必须会红（塞写口没抓到）", dirty.contains("DecisionLedger"))
    }

    @Test
    fun `决定合成只产出三类行为位_不新增第五种出口`() {
        val code = LawIo.codeOnly(PolicyPaths.read("MediatorDecide.kt"))
        // ReadingsDecision 仅四个字段：advice / degrade / hint / affectLines；无 state / ledger / content 出口
        assertTrue("决定对象须带降级位", code.contains("val degrade: Boolean"))
        assertTrue("决定对象须带提示位", code.contains("val hint: String?"))
        assertTrue("决定对象须带可观测行", code.contains("val affectLines: List<String>"))
        val decisionBlock = code.substringAfter("data class ReadingsDecision", "")
            .substringBefore("\n    }")
        assertFalse("决定对象不许夹带档位写入", decisionBlock.contains("state ="))
        assertFalse("决定对象不许夹带账本写入", decisionBlock.contains("DecisionLedger"))
    }

    @Test
    fun `接线层只在三处消费读数_consultAsk落行_promptNotes提示_resetForTest摘端口`() {
        val code = LawIo.codeOnly(PolicyPaths.read("MediatorWiring.kt"))
        assertTrue("consultAsk 必须落可观测行", code.contains("decision.affectLines.forEach"))
        assertTrue("提示须走 promptNotes 取走即清", code.contains("pendingReadingsHint = null"))
        assertTrue("resetForTest 必须摘端口", code.contains("ReadingPorts.detach()"))
        // 接线层不得自己判定规则（判定只在 ReadingCodex）
        assertFalse("接线层不得内联卡步阈值判定", code.contains("sameParamRetries >= 2"))
        assertFalse("接线层不得内联 RPE 阈值判定", code.contains("rpeNegativeStreak >= 2"))
    }

    // ── 判据⑤：判定权不迁 ────────────────────────────────────────────────

    @Test
    fun `必问铁律_读数一条都压不动必问档`() {
        val must = baseAsk(asked = 0, mustAsk = true)
        assertTrue(must.allowed)
        assertTrue(must.reason.startsWith("必问档"))
        listOf(stuckAdvice(), rpeAdvice()).forEach { reading ->
            val d = MediatorDecide.decideWithReadings(must, reading)
            assertTrue("必问档恒放行，读数压不动", d.advice.allowed)
            assertFalse("必问档不得被读数标记成调解器结论", d.advice.effective)
            assertEquals("必问档 reason 逐字不动", must.reason, d.advice.reason)
            assertTrue("必问档不落读数可观测行", d.affectLines.isEmpty())
        }
    }

    @Test
    fun `暂停与熔断铁律_越界读数也放不出卡`() {
        val paused = baseAsk(state = HarmonyMediator.State(paused = true))
        val loop = baseAsk(state = HarmonyMediator.State(mood = HarmonyMediator.Mood.LOOP))
        listOf(paused, loop).forEach { base ->
            assertFalse("基线必须是压卡", base.allowed)
            val d = MediatorDecide.decideWithReadings(base, crossAdvice())
            assertFalse("暂停/熔断压卡：读数的 require 不许放卡", d.advice.allowed)
            assertTrue("硬压格不落可观测行", d.affectLines.isEmpty())
        }
    }

    @Test
    fun `同一规则阈值常量_测试侧硬编码_不引用被测常量`() {
        // 阈值来自派工单（R1≥2 次逐字；R2 连续为负裁定≥2）。测试硬编码，防"测试引用被测常量"的假绿。
        val two = ReadingFacts(stuckLoop = "plan", previousStuckLoop = "plan", sameParamRetries = 2)
        assertEquals(1, ReadingCodex.evaluate(two).proposals.size)
        val one = ReadingFacts(stuckLoop = "plan", previousStuckLoop = "plan", sameParamRetries = 1)
        assertEquals(0, ReadingCodex.evaluate(one).proposals.size)
        val rpeTwo = ReadingFacts(rpeNegativeStreak = 2, rpeCauseSame = true)
        assertEquals(1, ReadingCodex.evaluate(rpeTwo).proposals.size)
        val rpeDiff = ReadingFacts(rpeNegativeStreak = 2, rpeCauseSame = false)
        assertEquals(0, ReadingCodex.evaluate(rpeDiff).proposals.size)
    }

    @Test
    fun `安全优先级_越界无证据命中时独占决定_少问建议让位`() {
        // 越界无证据 + 卡步重试同时命中：安全项独占，不降级、不提示
        val f = ReadingFacts(
            stuckLoop = "plan", previousStuckLoop = "plan", sameParamRetries = 2,
            rpeNegativeStreak = 2, rpeCauseSame = true,
            crossedWithoutEvidence = true,
        )
        val advice = ReadingCodex.evaluate(f)
        assertEquals(1, advice.proposals.size)
        assertEquals("安全项独占", ReadingCodex.Rule.CROSS_NO_EVIDENCE, advice.proposals.first().rule)
        assertTrue(advice.requireAsk)
        assertFalse(advice.suppressAsk)
        assertFalse(advice.degrade)
        assertFalse(advice.hintOnce)
        assertNotEquals(ReadingCodex.empty, advice)
    }

    // ── 夹具构造 ─────────────────────────────────────────────────────────

    private fun stuckFacts() = ReadingFacts(
        readbackPresent = true, stuckLoop = "plan", previousStuckLoop = "plan", sameParamRetries = 2,
    )

    private fun stuckAdvice() = ReadingCodex.evaluate(stuckFacts())

    private fun rpeAdvice() = ReadingCodex.evaluate(
        ReadingFacts(rpeNegativeStreak = 2, rpeCauseSame = true),
    )

    private fun crossAdvice() = ReadingCodex.evaluate(
        ReadingFacts(crossedWithoutEvidence = true),
    )
}
