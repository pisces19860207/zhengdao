// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L7 四档定义 / protected 必先出卡**
// （法典卷二 L7「未分类 ⇒ R2 且必问（fail-closed）」＋ 派工单 271「protected 路径写入必须先出卡」）。
//
// ## 判据（可执行）
// ① **未分类 ⇒ R2 且必问**：工具名不在风险表、也不在豁免表 ⇒ `risk=R2`、`decision=MUST_ASK`、
//    `unclassified=true`、`auditTag=unclassified_tool`（fail-closed：没判据就不许静默放行）；
// ② **protected 路径写入必须先出卡**：授权在这件事上**一格都不覆盖** ⇒ 命中受保护对象时
//    结论必须是「单次人批」而不是放行（`code=protected`，停在第④步）。
//
// ## 负例（本文件自证会红）
// ① 未分类工具被放行（`PROCEED`/`EXEMPT`）⇒ 红；
// ② 带 `protectedHit` 的写请求直接写下去（六步全过）⇒ 红。
//    两条都是「该出卡却没出卡」的样子，去掉生产里对应的分支就立刻红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.authz.AuthzFixtures
import com.example.zhengdao.baopu.authz.BoundaryFacts
import com.example.zhengdao.baopu.authz.GateOutcome
import com.example.zhengdao.baopu.authz.GrantActionType
import com.example.zhengdao.baopu.authz.GrantStep
import com.example.zhengdao.baopu.authz.TaskGrantGate
import com.example.zhengdao.baopu.authz.boundaryFactsOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L7ProtectedWriteGateTest {

    // ── 正例：合规侧——未分类必问；授权内普通写才放行 ────────────────────────

    @Test
    fun `L7_正例_未分类工具按R2必问_授权内普通写才放行`() {
        // ① 未分类：表里没有、也没豁免 ⇒ fail-closed
        val unknown = RiskMatrix.classify(ToolCall("t1", "l7_从未登记的工具", JSONObject()))
        assertEquals("未分类必须按 R2 处理", RiskClass.R2, unknown.risk)
        assertEquals("未分类必须必问", RiskDecision.MUST_ASK, unknown.decision)
        assertTrue("未分类要留 fail-closed 标记", unknown.unclassified)
        assertEquals("208 的审计标签必须是这个稳定键", "unclassified_tool", unknown.auditTag)
        assertEquals(RiskCode.UNCLASSIFIED_TOOL, unknown.reasonCode)

        // ② 授权内的普通写（无 protected、无硬拒）⇒ 六步全过
        val ok = TaskGrantGate.evaluate(
            AuthzFixtures.request(objectId = "a", action = GrantActionType.CREATE_TEXT),
            AuthzFixtures.state(),
        )
        assertEquals("授权内普通写必须放行", GateOutcome.ALLOW, ok.outcome)
        assertTrue(ok.allow)
    }

    // ── 负例①：未分类被放行 ────────────────────────────────────────────────

    @Test
    fun `L7_负例1_未分类工具若被放行即红`() {
        val v = RiskMatrix.classify(ToolCall("t1", "l7_从未登记的工具", JSONObject()))
        assertFalse(
            "未分类工具拿到了「不需要问」的结论（${v.decision}）——这就是 fail-open",
            v.decision == RiskDecision.PROCEED || v.decision == RiskDecision.EXEMPT,
        )
        // 对照：真在表里的只读工具该放行（防「一律必问」的假绿）
        val known = RiskMatrix.classify(ToolCall("t2", "read_file", JSONObject().put("path", "产物/a.md")))
        assertEquals("表内的只读工具不该被误伤", RiskDecision.PROCEED, known.decision)
    }

    // ── 负例②：protected 路径直接写下去 ─────────────────────────────────────

    @Test
    fun `L7_负例2_protected路径直接写必须失败_必须先出卡`() {
        val state = AuthzFixtures.state()
        val base = AuthzFixtures.request(objectId = "a", action = GrantActionType.CREATE_TEXT)

        // 同一份请求，只把「碰到受保护对象」这一个事实翻过来 ⇒ 结论必须变
        val hit = TaskGrantGate.evaluate(base.copy(boundary = BoundaryFacts(protectedHit = true)), state)
        assertFalse(
            "带 protectedHit 的写请求被放行了（${hit.outcome}/${hit.code}）——原件区/密码框一个字节都不许碰",
            hit.allow,
        )
        assertEquals("必须先出卡（单次人批），不许静默写", GateOutcome.ASK_SINGLE, hit.outcome)
        assertEquals("结论码必须是 protected", TaskGrantGate.CODE_PROTECTED, hit.code)
        assertEquals("必须停在第④步（边界）", GrantStep.BOUNDARY, hit.stoppedAt)

        // 反面对照：同一个请求不带该事实 ⇒ 放行。两条结论不同，才证明这一格真的在起作用
        val clean = TaskGrantGate.evaluate(base, AuthzFixtures.state())
        assertTrue("没有 protected 事实时应当放行（否则门禁恒红）", clean.allow)

        // 矩阵侧的事实翻译口：原件区命中必须翻成 protectedHit
        val zone = RiskMatrix.classify(
            ToolCall(
                "t3",
                "write_file",
                JSONObject().put("path", "资料库/原始/账本.md"),
            ),
            com.example.zhengdao.baopu.RiskCtx(workspaceRoots = listOf("证道")),
        )
        assertTrue("写原件区必须是硬拒或 protected 命中", zone.hardDeny || boundaryFactsOf(zone).protectedHit)
        assertNotNull("原件区判定必须给理由", zone.reasons.firstOrNull())
    }
}
