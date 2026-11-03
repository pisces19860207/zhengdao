// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十批 · amend 透传到守卫的**行为面**（判据①②③ 的 JVM 直测）。
//
// 治的是"改了要求但没重新过闸"：amend 原先只落到计划/军令状，起单处那张
// [TaskContract] 还挂着旧要求。本批让改约**重开一张单**（revision +1）——判据①
// 要的"必须重新过闸"就落在这里：新单自己过一遍形状闸，旧单上的批准与证据（回执按
// sha256 绑版本）绑不上新要求。
//
// 结构面（引擎起单处真的调了、真的挂回 activeRunOrder）在
// `AmendContractWiringGateTest`；本文件只打行为，不读源码。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.core.EvidenceKind
import com.example.zhengdao.core.ReceiptEvidence
import com.example.zhengdao.core.ReceiptState
import com.example.zhengdao.core.TaskReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunContractWiringAmendTest {

    private fun orderOf(text: String): RunContractWiring.RunOrder {
        val order = RunContractWiring.startRun("r360@1700000000000", text)
        assertNotNull("起单失败（契约没建成）", order)
        return order!!
    }

    private fun amendedOf(order: RunContractWiring.RunOrder, requirement: String): RunContractWiring.RunOrder {
        val next = RunContractWiring.amendRun(order, requirement)
        assertNotNull("改单失败（新要求没重开成单）", next)
        return next!!
    }

    // ── 判据①：删单文件 ⇒ amend 成删整个目录 ⇒ 必须重新过闸 ─────────────────

    @Test
    fun `判据1_删单文件amend成删整目录_单子重开一版_旧批准绑不上新要求`() {
        // 原任务：删一个文件
        val order = orderOf("把 产物/笔记.txt 删掉")
        val old = order.contract
        assertEquals("起单是第 1 版", 1, old.revision)

        // 用户中途改要求：不要那个文件了，整个目录一起删
        val next = amendedOf(order, "不要只删那个文件了，整个 产物/ 目录一起删")
        val fresh = next.contract

        // ① 版本 +1 ⇒ 这是一张**新单**，不是旧单改字
        assertEquals("改约必须重开一版（旧版批准不许顺延）", 2, fresh.revision)
        assertTrue("新目标没带上改约", fresh.goal.text.contains("整个 产物/ 目录一起删"))
        assertTrue("原话被抹掉了（事后答不出当初约的是什么）", fresh.goal.text.contains("把 产物/笔记.txt 删掉"))
        assertTrue("新单自己不合规：${fresh.violations()}", fresh.violations().isEmpty())

        // ② 指纹变了 ⇒ 旧回执/旧批准绑不上新单（这就是"必须重新过闸"的机器含义）
        assertNotEquals("新旧单指纹相同 ⇒ 旧批准能顺延到新要求上", old.sha256(), fresh.sha256())
        val oldReceipt = TaskReceipt(
            contractId = old.contractId,
            contractSha256 = old.sha256(),
            state = ReceiptState.DONE,
            atMs = 1_000L,
            evidence = listOf(ReceiptEvidence(EvidenceKind.EXISTS, "产物/笔记.txt")),
        )
        assertTrue("旧回执本该绑得住旧单", oldReceipt.bindsTo(old))
        assertFalse("旧回执绑住了新单 ⇒ 改要求没重新过闸", oldReceipt.bindsTo(fresh))
    }

    @Test
    fun `判据1_连续两次改约_版本逐次加1_不是一次覆盖`() {
        val v1 = orderOf("整理这一周的笔记")
        val v2 = amendedOf(v1, "只整理前三天")
        val v3 = amendedOf(v2, "再加一份目录")
        assertEquals(2, v2.contract.revision)
        assertEquals(3, v3.contract.revision)
        assertTrue("三次的目标各不相同", setOf(v1, v2, v3).map { it.contract.sha256() }.toSet().size == 3)
    }

    // ── 判据③：不给 amend 开新后门（授权/预算/证据/出口一格不放宽）──────────

    @Test
    fun `判据3_改约不放宽任何一格_授权预算证据出口逐字沿用`() {
        val order = orderOf("把这周的笔记整理成报告")
        val old = order.contract
        val fresh = amendedOf(order, "报告要多一节结论").contract

        assertEquals("改约顺手改了授权范围（后门）", old.authority, fresh.authority)
        assertEquals("改约顺手改了预算（后门）", old.budget, fresh.budget)
        assertEquals("改约顺手改了证据要求（后门）", old.evidence, fresh.evidence)
        assertEquals("改约顺手改了出口（后门）", old.exit, fresh.exit)
        assertEquals("契约号变了 ⇒ 账上对不回同一张单", old.contractId, fresh.contractId)
        assertFalse("外发被打开了", fresh.authority.egressAllowed)
        assertTrue("新单形状不合规：${fresh.violations()}", fresh.violations().isEmpty())
    }

    @Test
    fun `判据3_空改约与重复改约_不空转也不白涨版本`() {
        val order = orderOf("把笔记整理成报告")
        assertNull("空改约不该改单", RunContractWiring.amendRun(order, "   "))
        val once = amendedOf(order, "报告要一节结论")
        assertNull("同一条改约已在单上 ⇒ 不再开第二版", RunContractWiring.amendRun(once, "报告要一节结论"))
    }

    @Test
    fun `判据3_超长改约_截到上限且新单仍合规`() {
        val order = orderOf("把笔记整理成报告")
        val fresh = amendedOf(order, "还要".repeat(300)).contract
        assertTrue("目标超长", fresh.goal.text.length <= 200)
        assertTrue("截断后破了形状：${fresh.violations()}", fresh.violations().isEmpty())
    }

    // ── 判据②：改约后的单子自己说清楚（日志行与回灌都带版本）──────────────

    @Test
    fun `判据2_改单行与回灌都带版本号_并写明一格未动`() {
        val order = orderOf("把笔记整理成报告")
        val next = amendedOf(order, "报告要一节结论")
        val line = next.amendLine(fromRevision = 1)
        assertTrue("改单行没带日志头", line.startsWith("抱朴契约：改单"))
        assertTrue("改单行没带版本迁移", line.contains("第 1 版 → 第 2 版"))
        assertTrue("改单行没说清授权/预算没动", line.contains("授权/预算一格未动"))
        val ack = next.reissueAck(fromRevision = 1)
        assertTrue("回灌没告诉模型旧批准作废", ack.contains("旧版上的批准与证据不再覆盖新要求"))
        assertTrue("回灌没说明该问的照问", ack.contains("该问的确认卡照旧会问"))
    }
}
