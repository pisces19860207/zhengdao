// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十一批 · 相处模式制度面**接线**的端到端（派工单判据④：改判落一行账，可在
// 自治理中心看到）。
//
// 链路：`MediatorWiring.onUserTurn`（左相判档）→ 观察口 `turnObserver`（571 新增的只读
// 事实口）→ `CompanionLawWiring.onTurn`（本件）→ ①右相达成判定（RunLog 行）＋
// ②改判落账 `CompanionRejudgeLedger`（原判/新判/依据一行）→ `governance_state` 的
// `companion` 段 → 自治理中心读数区那一行。
//
// 达成判定的**三态夹具**在 [CompanionAchievementGateTest]（纯函数）；这里钉的是接线性质：
// 判档照旧一轮一次、观察口不改判档、改判只有用户点名才落账、出口段与屏幕行三列齐。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompanionDecision
import com.example.zhengdao.baopu.CompanionMode
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.MediatorWiring
import com.example.zhengdao.testkit.TempSandbox
import com.example.zhengdao.ui.baopu.GovernanceCenterRules
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompanionLawWiringTest {

    /** 钉死的「现在」：2026-11-12 本地 10:00（账行与出口段都以它推当日窗）。 */
    private val nowMs: Long = 1_800_000_000_000L
    private val sessionId = 11L

    private lateinit var ledgerRoot: File

    @Before
    fun setUp() {
        DecisionLedger.resetForTest()
        CompanionRejudgeLedger.resetForTest()
        MediatorWiring.resetForTest()
    }

    @After
    fun tearDown() {
        // 谁注册谁摘：进程内单例不许把观察口漏进后面的用例（resetForTest 同步清判档态）
        MediatorWiring.resetForTest()
        CompanionRejudgeLedger.resetForTest()
        DecisionLedger.resetForTest()
        if (::ledgerRoot.isInitialized) ledgerRoot.deleteRecursively()
    }

    /** 装配层同款接线（BaopuRuntime 那一行的测试版）：观察口 → 右相接线件。 */
    private fun wire() {
        MediatorWiring.turnObserver = { fact ->
            CompanionLawWiring.onTurn(
                turn = fact.turn,
                sessionId = fact.sessionId,
                prevMode = fact.prevMode,
                decided = fact.decided,
                correction = fact.correction,
                userText = fact.userText,
                nowMs = nowMs,
            )
        }
    }

    // ── 判据④：改判落一行账（原判/新判/依据）─────────────────────────────────

    @Test
    fun `判据④_用户点名改判落一行_原判新判依据三列齐`() {
        TempSandbox.use("zd571-rejudge") { root ->
            ledgerRoot = root
            DecisionLedger.install(root)
            wire()
            // 第1轮：学习档（自动信号，不是纠正）
            MediatorWiring.onUserTurn("教我为什么这表第3行会报错", "", sessionId, nowMs)
            // 第2轮：用户点名「别这么啰嗦」⇒ 立即切回工作档，这就是一次改判
            MediatorWiring.onUserTurn("别这么啰嗦，直接给结论", "", sessionId, nowMs)

            val rows = CompanionRejudgeLedger.replay()
            assertEquals("只落一行改判账", 1, rows.size)
            val row = rows.single()
            assertEquals("原判列＝上一轮的档", "study", row.from)
            assertEquals("原判人话名", "学习", row.fromCn)
            assertEquals("新判列＝本轮的档", "work", row.to)
            assertEquals("新判人话名", "工作", row.toCn)
            assertTrue("依据列＝本轮判档依据（点名命中的词必须在场）", row.basis.contains("啰嗦"))
            assertEquals("轮次", 2, row.turn)
            assertEquals("会话", sessionId, row.sessionId)
        }
    }

    @Test
    fun `判据④_自动信号换档不落改判账_第一轮也不落`() {
        TempSandbox.use("zd571-noexp") { root ->
            ledgerRoot = root
            DecisionLedger.install(root)
            wire()
            // 第1轮（prev=null）：没有可纠的判
            MediatorWiring.onUserTurn("教我为什么这表第3行会报错", "", sessionId, nowMs)
            assertEquals("第一轮不算纠正", 0, CompanionRejudgeLedger.replay().size)
            // 第2轮：任务类型自动切工作档（explicit=false）——"这一轮输入不一样"，不是误判
            MediatorWiring.onUserTurn("直接改第3行", "", sessionId, nowMs)
            assertEquals("自动信号换档不落改判账", 0, CompanionRejudgeLedger.replay().size)
        }
    }

    @Test
    fun `判据④_账本件再防一道_非显式与同档都拒`() {
        assertNull(
            "第一轮（prev=null）拒",
            CompanionRejudgeLedger.append(null, explicitWork(), 2, sessionId, nowMs),
        )
        assertNull(
            "非显式（自动信号）拒",
            CompanionRejudgeLedger.append(CompanionMode.STUDY, autoWork(), 2, sessionId, nowMs),
        )
        assertNull(
            "同档不落账（没改判）",
            CompanionRejudgeLedger.append(CompanionMode.WORK, explicitWork(), 2, sessionId, nowMs),
        )
        assertEquals("三道都拒 ⇒ 一行不落", 0, CompanionRejudgeLedger.replay().size)
    }

    // ── 判据④的可见面：governance_state 第 9 段 ＋ 自治理中心读数行 ─────────────

    @Test
    fun `自治理中心可见_出口companion段与屏幕行三列齐`() {
        TempSandbox.use("zd571-gov") { root ->
            ledgerRoot = root
            DecisionLedger.install(root)
            wire()
            MediatorWiring.onUserTurn("教我为什么这表第3行会报错", "", sessionId, nowMs)
            MediatorWiring.onUserTurn("别这么啰嗦，直接给结论", "", sessionId, nowMs)

            val out = JSONObject((GovernanceStateTool.run(root, nowMs) as ToolOutcome.Ok).text)
            val section = out.getJSONObject("companion")
            assertEquals("当日次数", 1, section.optInt("today"))
            val last = section.getJSONObject("last")
            assertEquals("原判", "study", last.optString("from"))
            assertEquals("新判", "work", last.optString("to"))
            assertTrue("依据", last.optString("basis").contains("啰嗦"))

            // 屏幕行：原样抄出口，三列齐（原判在箭头左、新判在箭头右、依据带标签）
            val row = GovernanceCenterRules.companion(out)!!
            assertEquals("rejudge", row.key)
            assertEquals("学习 → 工作", row.value.substringBefore("｜依据"))
            assertTrue("依据要原样带出来", row.value.contains("依据：") && row.value.contains("啰嗦"))
            assertEquals("今日 1 次", row.detail)
        }
    }

    @Test
    fun `自治理中心_没改判过整行不出现_读不到降级`() {
        // 没改判过（账在、账空）⇒ 出口给真零，屏幕行整行不出现（缺哪格少哪格，不补 0）
        TempSandbox.use("zd571-empty") { root ->
            ledgerRoot = root
            DecisionLedger.install(root)
            val out = JSONObject((GovernanceStateTool.run(root, nowMs) as ToolOutcome.Ok).text)
            assertEquals(0, out.getJSONObject("companion").optInt("today"))
            assertNull("没改判过 ⇒ 屏幕整行不出现", GovernanceCenterRules.companion(out))
        }
        // 账本根没装配 ⇒ 整段 unknown（不编 0 冒充读数），屏幕行同样不出现
        DecisionLedger.resetForTest()
        val out = JSONObject((GovernanceStateTool.run(File("."), nowMs) as ToolOutcome.Ok).text)
        assertTrue(out.getJSONObject("companion").has("unknown"))
        assertNull(GovernanceCenterRules.companion(out))
    }

    @Test
    fun `接线性质_观察口不改判档_达成判定有且只有一处调用`() {
        // 判档结果仍由 CompanionJudge.judge 的返回值直接决定（观察口在判档赋值之后拿到快照）
        // ——L34 门禁已钉源序；这里钉接线件的另一面：达成判定只在 onTurn 里被调一次。
        val src = File("src/main/java/com/example/zhengdao/baopu/agent/CompanionLawWiring.kt")
            .readText(Charsets.UTF_8)
        assertEquals(
            "达成判定必须在接线件里（生产链路真调用）",
            1,
            Regex("CompanionAchievementGate\\.judge\\(").findAll(src).count(),
        )
        assertEquals("改判落账也只此一处", 1, Regex("CompanionRejudgeLedger\\.append\\(").findAll(src).count())
    }
}

/** 判档决策的小夹具（与 550 同款显式/自动两份）。 */
private fun explicitWork() = CompanionDecision(
    CompanionMode.WORK,
    "显式要求简短（点名「别啰嗦」）",
    true,
)

private fun autoWork() = CompanionDecision(
    CompanionMode.WORK,
    "任务类型＝办事（TaskModeBoundary 判 TASK）",
    false,
)
