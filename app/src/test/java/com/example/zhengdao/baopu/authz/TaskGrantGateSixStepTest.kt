// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：六步放行（§5 的**顺序**与每步的停点）。
//
// 这个类同时是「六步真实读数」的来源：`六步读数写进证据文件` 会把一次真实放行的
// 六条读数原样写到 `%TEMP%/baopu249-six-step.txt`，交付报告直接引用那个文件。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.inspect.ShadowVerdict
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskGrantGateSixStepTest {

    @Before
    fun setUp() {
        TaskGrantRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        TaskGrantRuntime.resetForTest()
    }

    @Test
    fun 六步全过才放行() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(), state)

        assertEquals(GateOutcome.ALLOW, d.outcome)
        assertEquals(TaskGrantGate.CODE_ALLOW, d.code)
        assertEquals("六步一步不少", GrantStep.entries.toList(), d.steps.map { it.key })
        assertEquals("六步全过", GrantStep.entries.size, d.passedSteps)
        assertTrue(d.steps.all { it.passed == true })
        assertNull(d.stoppedAt)
        assertEquals("op-1", d.opId)
        assertEquals("op-1:applied", d.ledgerKey)
        assertTrue(d.allow)
    }

    @Test
    fun 六步读数写进证据文件() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(), state)
        val body = buildString {
            appendLine("outcome=${d.outcome.wire} code=${d.code} opId=${d.opId} ledgerKey=${d.ledgerKey}")
            appendLine("grantSnapshot=${state.grant.snapshotHash()}")
            d.steps.forEachIndexed { i, s ->
                val mark = when (s.passed) {
                    true -> "过"
                    false -> "不过"
                    null -> "未验"
                }
                appendLine("${i + 1}. ${s.key.wire}（${s.key.cn}）: $mark — ${s.detail}")
            }
        }
        val f = File(System.getProperty("java.io.tmpdir"), "baopu249-six-step.txt")
        f.writeText(body, Charsets.UTF_8)
        assertEquals("证据文件必须写出六条读数", 6, d.steps.size)
        assertTrue("证据文件应当落盘：${f.absolutePath}", f.isFile)
        println(body)
    }

    @Test
    fun 第一步_没有授权就改走单次人批() {
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(), null)
        assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
        assertEquals(TaskGrantGate.CODE_NO_GRANT, d.code)
        assertEquals(GrantStep.OBJECTS, d.stoppedAt)
        assertEquals(0, d.passedSteps)
    }

    @Test
    fun 第一步_授权已结束不再放行() {
        val state = AuthzFixtures.state()
        state.end(GrantEndReason.USER_STOPPED, AuthzFixtures.NOW)
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(), state)
        assertEquals(TaskGrantGate.CODE_NO_GRANT, d.code)
        assertFalse(d.allow)
    }

    @Test
    fun 第一步_任务换了不认() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(taskId = "t2"), state)
        assertEquals(TaskGrantGate.CODE_FOREIGN_TASK, d.code)
        assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
    }

    @Test
    fun 第一步_到期不认() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(
            AuthzFixtures.request(now = AuthzFixtures.NOW + 30 * AuthzFixtures.MIN),
            state,
        )
        assertEquals(TaskGrantGate.CODE_EXPIRED, d.code)
        assertFalse(d.allow)
    }

    @Test
    fun 第一步_对象不在清单里只问增量() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(objectId = "c"), state)
        assertEquals(GateOutcome.ASK_DELTA, d.outcome)
        assertEquals(TaskGrantGate.CODE_OBJECT_OUT, d.code)
        assertEquals(listOf("c"), d.delta?.deltaObjects)
    }

    @Test
    fun 第一步_现状与快照对不上不认() {
        val g = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("a", state = ObjectState.Present("h1", 10))))
        val state = AuthzFixtures.state(g)
        val d = TaskGrantGate.evaluate(
            AuthzFixtures.request(before = ObjectState.Present("h2", 10)),
            state,
        )
        assertEquals(TaskGrantGate.CODE_STATE_CHANGED, d.code)
        assertFalse(d.allow)
    }

    @Test
    fun 第二步_不可授权动作直接改单次人批() {
        val state = AuthzFixtures.state()
        listOf(
            GrantActionType.DELETE,
            GrantActionType.SEND,
            GrantActionType.EXECUTE,
            GrantActionType.CHANGE_PERMISSION,
            GrantActionType.GIT,
            GrantActionType.OTHER,
        ).forEach { action ->
            val d = TaskGrantGate.evaluate(AuthzFixtures.request(action = action), state)
            assertEquals("${action.wire} 必须落在动作这一步", GrantStep.ACTIONS, d.stoppedAt)
            assertEquals(TaskGrantGate.CODE_ACTION_BANNED, d.code)
            assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
        }
    }

    @Test
    fun 第二步_授权里没有这个动作只问增量() {
        val g = AuthzFixtures.grant(actions = listOf(GrantActionType.CREATE_TEXT))
        val state = AuthzFixtures.state(g)
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(action = GrantActionType.EDIT_TEXT), state)
        assertEquals(GateOutcome.ASK_DELTA, d.outcome)
        assertEquals(TaskGrantGate.CODE_ACTION_OUT, d.code)
        assertEquals(listOf(GrantActionType.EDIT_TEXT), d.delta?.deltaActions)
    }

    @Test
    fun 第三步_单文件超限停下() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(
            AuthzFixtures.request(candidateBytes = GrantLimits.MAX_BYTES_PER_FILE + 1),
            state,
        )
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_FILE_TOO_BIG, d.code)
        assertEquals(GrantStep.LIMITS, d.stoppedAt)
    }

    @Test
    fun 第三步_动作次数超限停下() {
        val state = AuthzFixtures.state(AuthzFixtures.grant(budget = GrantBudget(maxActions = 1)))
        assertTrue(state.commit("op-0", "a", 10L, ObjectState.Absent))
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(opId = "op-2"), state)
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_ACTION_LIMIT, d.code)
    }

    @Test
    fun 第三步_总写入超限停下() {
        val state = AuthzFixtures.state(AuthzFixtures.grant(budget = GrantBudget(maxBytesTotal = 100L)))
        assertTrue(state.commit("op-0", "a", 50L, ObjectState.Absent))
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(opId = "op-2", candidateBytes = 64L), state)
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_TOTAL_TOO_BIG, d.code)
    }

    @Test
    fun 第四步_硬拒连问都不问() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(
            AuthzFixtures.request(boundary = BoundaryFacts(hardDeny = true)),
            state,
        )
        assertEquals(GateOutcome.REJECT, d.outcome)
        assertEquals(TaskGrantGate.CODE_HARD_DENY, d.code)
        assertEquals(GrantStep.BOUNDARY, d.stoppedAt)
    }

    @Test
    fun 第四步_受保护对象只改单次人批() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(
            AuthzFixtures.request(boundary = BoundaryFacts(protectedHit = true)),
            state,
        )
        assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
        assertEquals(TaskGrantGate.CODE_PROTECTED, d.code)
    }

    @Test
    fun 第四步_R3与必问都改单次人批() {
        val state = AuthzFixtures.state()
        assertEquals(
            TaskGrantGate.CODE_RISK_TOO_HIGH,
            TaskGrantGate.evaluate(AuthzFixtures.request(risk = RiskClass.R3), state).code,
        )
        assertEquals(
            TaskGrantGate.CODE_RISK_TOO_HIGH,
            TaskGrantGate.evaluate(AuthzFixtures.request(mustAsk = true), state).code,
        )
        // 授权自己的风险上限比 R2 更紧时也照此办
        val tight = AuthzFixtures.state(AuthzFixtures.grant(maxRiskOrdinal = 1))
        assertEquals(
            TaskGrantGate.CODE_RISK_TOO_HIGH,
            TaskGrantGate.evaluate(AuthzFixtures.request(risk = RiskClass.R2), tight).code,
        )
    }

    @Test
    fun 第五步_影子跑了才知道() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(shadow = ShadowSignal.None), state)
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_NO_DATA, d.code)
        assertEquals(GrantStep.SHADOW, d.stoppedAt)
        assertNull("没跑影子是「未验」，不是「不过」", d.steps.last().passed)
    }

    @Test
    fun 第六步_没有稳定opId不落账() {
        val state = AuthzFixtures.state()
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(opId = ""), state)
        assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
        assertEquals(TaskGrantGate.CODE_NO_OP_ID, d.code)
        assertEquals(GrantStep.RECORD, d.stoppedAt)
    }

    @Test
    fun 第六步_同一个opId不许再放一次() {
        val state = AuthzFixtures.state()
        assertTrue(state.commit("op-1", "a", 10L, ObjectState.Absent))
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(), state)
        assertEquals(GateOutcome.STOPPED, d.outcome)
        assertEquals(TaskGrantGate.CODE_DUPLICATE_OP, d.code)
    }

    @Test
    fun 六步顺序就是代码顺序() {
        // 每一步各有一个"只在这一步能撞上"的案例；停点必须正好是那一步。
        val state = AuthzFixtures.state()
        val stops = mapOf(
            GrantStep.OBJECTS to TaskGrantGate.evaluate(AuthzFixtures.request(objectId = "zzz"), state),
            GrantStep.ACTIONS to TaskGrantGate.evaluate(
                AuthzFixtures.request(action = GrantActionType.SEND),
                state,
            ),
            GrantStep.LIMITS to TaskGrantGate.evaluate(
                AuthzFixtures.request(candidateBytes = GrantLimits.MAX_BYTES_PER_FILE + 1),
                state,
            ),
            GrantStep.BOUNDARY to TaskGrantGate.evaluate(
                AuthzFixtures.request(boundary = BoundaryFacts(hardDeny = true)),
                state,
            ),
            GrantStep.SHADOW to TaskGrantGate.evaluate(
                AuthzFixtures.request(shadow = ShadowSignal.Rehearsed(ShadowVerdict.UNVERIFIABLE)),
                state,
            ),
            GrantStep.RECORD to TaskGrantGate.evaluate(AuthzFixtures.request(opId = ""), state),
        )
        stops.forEach { (step, decision) -> assertEquals("停点应是 $step", step, decision.stoppedAt) }
    }
}
