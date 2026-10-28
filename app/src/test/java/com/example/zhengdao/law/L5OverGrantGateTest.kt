// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L5 越授权动作必被拦**（配 249 的授权对象清单）。
//
// ## 判据（可执行）
// 一次动作请求与一份已装授权比对：
// ① 对象**必须在清单里**，不在 ⇒ 不放行（只问增量，不整单重问）；
// ② 动作**必须是可授权类**（`DELETE/SEND/EXECUTE/CHANGE_PERMISSION/GIT/OTHER` 永不可授权）
//    且**在这份授权里**，否则 ⇒ 不放行（单次人批／只问增量）；
// ③ 只有「对象＋动作＋上限＋边界＋影子＋落账」六步全过才 `ALLOW`。
//
// ## 负例（本文件自证会红）
// ① 清单外对象 ⇒ 必须不放行（若放行 ⇒ 红）；
// ② 不可授权动作（删除）⇒ 必须改走单次人批（若放行 ⇒ 红）；
// ③ 授权里没有的动作 ⇒ 必须只问增量（若放行 ⇒ 红）。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.authz.AuthzFixtures
import com.example.zhengdao.baopu.authz.GateOutcome
import com.example.zhengdao.baopu.authz.GrantActionType
import com.example.zhengdao.baopu.authz.GrantStep
import com.example.zhengdao.baopu.authz.TaskGrantGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L5OverGrantGateTest {

    private val state by lazy { AuthzFixtures.state() }

    // ── 正例：清单内的对象与动作 ⇒ 六步全过 ─────────────────────────────────

    @Test
    fun `L5_正例_清单内的对象与动作六步全过`() {
        val decision = TaskGrantGate.evaluate(
            AuthzFixtures.request(objectId = "a", action = GrantActionType.CREATE_TEXT),
            state,
        )
        assertEquals("清单内的新建必须放行：${decision.steps.joinToString { it.line() }}", GateOutcome.ALLOW, decision.outcome)
        assertTrue("放行必须六步全过（实过 ${decision.passedSteps}）", decision.passedSteps >= GrantStep.entries.size)
        assertEquals("放行不许留增量问题", null, decision.delta)
    }

    // ── 负例①：清单外对象 ──────────────────────────────────────────────────

    @Test
    fun `L5_负例1_清单外的对象必须被拦`() {
        val decision = TaskGrantGate.evaluate(
            AuthzFixtures.request(objectId = "不在清单里的对象", action = GrantActionType.CREATE_TEXT),
            state,
        )
        assertFalse("清单外对象放行了＝越授权（L5 红）", decision.allow)
        assertEquals("越界只问增量", GateOutcome.ASK_DELTA, decision.outcome)
        assertEquals("结论码必须是对象越界", TaskGrantGate.CODE_OBJECT_OUT, decision.code)
        val delta = decision.delta
        assertNotNull("越界必须给出增量问题（只列这次要的那一个）", delta)
        assertEquals("增量只许一个对象", listOf("不在清单里的对象"), delta!!.deltaObjects)
        assertEquals("停在第①步", GrantStep.OBJECTS, decision.stoppedAt)
    }

    // ── 负例②：不可授权动作（删除） ────────────────────────────────────────

    @Test
    fun `L5_负例2_删除类动作永不可授权_必须改走单次人批`() {
        val decision = TaskGrantGate.evaluate(
            AuthzFixtures.request(objectId = "a", action = GrantActionType.DELETE),
            state,
        )
        assertFalse("删除被授权放行了＝越授权（L5 红）", decision.allow)
        assertEquals("不可授权类必须改走单次人批", GateOutcome.ASK_SINGLE, decision.outcome)
        assertEquals("结论码必须是动作禁用", TaskGrantGate.CODE_ACTION_BANNED, decision.code)
        assertEquals("停在第②步", GrantStep.ACTIONS, decision.stoppedAt)
        assertFalse("删除永远不可授权", GrantActionType.DELETE.grantable)
    }

    // ── 负例③：授权里没有的动作 ────────────────────────────────────────────

    @Test
    fun `L5_负例3_授权里没有的动作必须只问增量`() {
        // 夹具授权只有 CREATE_TEXT/EDIT_TEXT；这里要的是「可授权但这份授权没给」的动作，
        // 故换一份只给了 CREATE_TEXT 的授权。
        val narrow = AuthzFixtures.state(
            grant = AuthzFixtures.grant(actions = listOf(GrantActionType.CREATE_TEXT)),
        )
        val decision = TaskGrantGate.evaluate(
            AuthzFixtures.request(objectId = "a", action = GrantActionType.EDIT_TEXT),
            narrow,
        )
        assertFalse("授权没给的动作放行了＝越授权（L5 红）", decision.allow)
        assertEquals(GateOutcome.ASK_DELTA, decision.outcome)
        assertEquals(TaskGrantGate.CODE_ACTION_OUT, decision.code)
        assertEquals("增量只列这一个动作", listOf(GrantActionType.EDIT_TEXT), decision.delta?.deltaActions)
    }
}
