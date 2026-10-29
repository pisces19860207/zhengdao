// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L8 外发前必须脱敏 / 硬拒清单**
// （派工单 271「外发前必须脱敏（负例：含密钥/手机号的载荷 ⇒ 拒发）」＋ 法典卷二 L8 硬拒清单）。
//
// ## 判据（可执行）
// ① 对外发的工具（表里 `scanSensitiveText`）参数里出现**手机号样式**或**账号/金额/密码语义**
//    ⇒ 风险至少升到 **R3** 且**必须问**（= 不许静默发出去；这是"拒发"在矩阵层的落地形态）；
// ② 硬拒清单（密码框、原件区、内网/白名单外 URL）⇒ `REJECT`，连问都不问。
//
// ## 负例（本文件自证会红）
// ① 带手机号的外发载荷若被判成 R0/R1 或 PROCEED ⇒ 红（= 静默外发）；
// ② 密码框填写若没被判成硬拒 ⇒ 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.agent.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L8OutboundRedactionGateTest {

    private fun notify(text: String) = ToolCall(
        "t1",
        "notify",
        JSONObject().put("title", "抱朴").put("text", text),
    )

    // ── 正例：干净载荷可以发（不误伤） ──────────────────────────────────────

    @Test
    fun `L8_正例_干净载荷不升档_可以发`() {
        val v = RiskMatrix.classify(notify("账已记好，共 3 条"))
        assertEquals("干净载荷不该被升档", RiskClass.R1, v.risk)
        assertEquals("干净载荷不该被拦", RiskDecision.PROCEED, v.decision)
        assertFalse("干净载荷不是敏感语义", RiskCode.SENSITIVE_SEMANTICS in v.reasonCodes)
    }

    // ── 负例①：含手机号的载荷必须被挡在"静默外发"之外 ──────────────────────

    @Test
    fun `L8_负例1_含手机号的外发载荷必须升R3且必问`() {
        val v = RiskMatrix.classify(notify("联系人 13800138000，请回电"))
        assertFalse(
            "含手机号的载荷被静默外发了（risk=${v.risk} decision=${v.decision}）",
            v.decision == RiskDecision.PROCEED,
        )
        assertEquals("含手机号必须升到 R3", RiskClass.R3, v.risk)
        assertTrue("必须问（拒发的落地形态）", v.mustAsk)
        assertEquals("理由码必须是敏感语义", RiskCode.SENSITIVE_SEMANTICS, v.reasonCode)
        // 理由里只许写命中词/规则名，不许回显号码原文
        assertFalse(
            "理由里回显了号码原文：${v.reasons.map { it.detail }}",
            v.reasons.any { it.detail.contains("13800138000") },
        )
    }

    // ── 负例②：密码框 ⇒ 硬拒（连问都不问） ─────────────────────────────────

    @Test
    fun `L8_负例2_密码框填写必须硬拒`() {
        val fill = ToolCall(
            "t2",
            "browser_type",
            JSONObject().put("target", "input[type=password]").put("text", "hunter2"),
        )
        val v = RiskMatrix.classify(fill)
        assertTrue("填密码框必须是硬拒（${v.decision}/${v.reasonCode}）", v.hardDeny)
        assertEquals(RiskDecision.REJECT, v.decision)
        assertEquals(RiskCode.PASSWORD_FIELD, v.reasonCode)

        // 对照：普通输入框不该被硬拒（防「一律拒」的假绿）
        val plain = RiskMatrix.classify(
            ToolCall("t3", "browser_type", JSONObject().put("target", "input[name=q]").put("text", "天气")),
        )
        assertFalse("普通输入框不该被硬拒", plain.hardDeny)
    }

    // ── 附：外发工具必须声明扫敏感文本（判据的挂点不许被摘掉） ───────────────

    @Test
    fun `L8_门_外发工具必须声明scanSensitiveText`() {
        val src = LawIo.codeOnly(LawIo.read("${LawIo.MAIN_ROOT}/baopu/RiskMatrix.kt"))
        assertTrue(
            "RiskMatrix 里 scanSensitiveText 的挂点不见了：外发载荷就没人扫敏感词了",
            src.contains("scanSensitiveText = true"),
        )
        assertTrue("敏感文本判据块必须在", src.contains("sensitiveTextPatterns"))
        // 真实环境里至少要有两个工具挂了这一格
        val table = com.example.zhengdao.baopu.RiskTable.DEFAULT
        val guarded = table.tools.filterValues { it.scanSensitiveText }.keys
        assertTrue("挂了 scanSensitiveText 的工具太少（$guarded）——判据被摘了？", guarded.size >= 2)
        assertTrue("notify 必须在这一格", "notify" in guarded)
    }
}
