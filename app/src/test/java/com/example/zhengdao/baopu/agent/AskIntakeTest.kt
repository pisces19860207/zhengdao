// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十九批 · B 段（先自查再问）＋ 499 追加（卡面三行/内部词/同题不重问）判据测试。
// 判据编号对齐派工单：⑦文件在哪类先列目录不弹卡 ⑧真歧义弹卡且带已查/仍不确定
// ⑨缺已查行判违规记账 ⑬三要素齐全 ⑭不出现内部词 ⑮同一件事不重复问。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskIntakeTest {

    private fun fresh() {
        AskIntakeGate.resetForTest()
        AskGovLedger.resetForTest()
    }

    // ── 分类（B 段的三种可问 ＋ 不许问的那一类） ─────────────────────────

    @Test
    fun `分类_文件在哪类能认出来`() {
        assertEquals(AskClass.FILE_LOCATION, AskIntakeRules.classify("那个报表文件放在哪个目录？"))
        assertEquals(AskClass.AUTHORIZATION, AskIntakeRules.classify("可以删除旧的临时文件吗？"))
        assertEquals(AskClass.PREFERENCE, AskIntakeRules.classify("报告标题你喜欢哪种风格？"))
        assertEquals(AskClass.CONTRADICTION, AskIntakeRules.classify("两次扫描结果对不上，以哪份为准？"))
        assertEquals(AskClass.AMBIGUITY, AskIntakeRules.classify("两处都讲得通，走哪条路？"))
    }

    // ── ⑦ 文件在哪类问题：先列目录，不弹卡 ──────────────────────────────

    @Test
    fun `⑦文件位置问题_自查钩子有结果就不弹卡`() {
        fresh()
        AskIntakeGate.selfCheck = { _ -> "已查：列了工作区根：产物/、备份/、资料库/（共 3 项）" }
        val out = AskIntakeGate.beforeAsk(
            title = "报表放在哪个目录？",
            taskId = "t7",
            dayKey = 7_000,
            nowMs = 7_001L,
        )
        assertTrue("列过目录就不该再弹卡", out is AskIntakeOutcome.Answered)
        assertEquals(AskGovLedger.KIND_SELF_CHECK, (out as AskIntakeOutcome.Answered).kind)
        assertTrue(out.text.contains("已查"))
        // 账上留一行（自查也是一次"问"的替代，可回放）
        assertTrue(AskGovLedger.replay().any { it.kind == AskGovLedger.KIND_SELF_CHECK })
    }

    @Test
    fun `⑦自查钩子没接_文件位置问题回落成弹卡`() {
        fresh()
        val out = AskIntakeGate.beforeAsk(
            title = "报表放在哪个目录？",
            taskId = "t7b",
            dayKey = 7_100,
            nowMs = 7_101L,
        )
        assertTrue("没接自查钩子 ⇒ 照旧弹卡（宁问不错删）", out is AskIntakeOutcome.Proceed)
    }

    // ── ⑧⑨ 提问卡的「已查…仍不确定」行 ──────────────────────────────────

    @Test
    fun `⑨缺已查行_卡照弹但判违规记账`() {
        fresh()
        val out = AskIntakeGate.beforeAsk(
            title = "两处都讲得通，走哪条路？",
            taskId = "t9",
            dayKey = 9_000,
            nowMs = 9_001L,
        )
        assertTrue(out is AskIntakeOutcome.Proceed)
        val violations = AskGovLedger.replay().filter { it.kind == AskGovLedger.KIND_VIOLATION && it.who == "intake" }
        assertTrue("没有那行 ⇒ 违规记账", violations.any { it.basis.contains("已查") })
    }

    @Test
    fun `⑧带了已查仍不确定_不记违规`() {
        fresh()
        val out = AskIntakeGate.beforeAsk(
            title = "两处都讲得通，走哪条路？",
            detailText = "已查：两个目录都列过、账本查过。仍不确定：你想要哪种粒度。",
            taskId = "t8",
            dayKey = 8_000,
            nowMs = 8_001L,
        )
        assertTrue(out is AskIntakeOutcome.Proceed)
        assertTrue(
            "带了完整行就不算违规",
            AskGovLedger.replay().none { it.kind == AskGovLedger.KIND_VIOLATION && it.basis.contains("已查") },
        )
    }

    // ── ⑬ 卡面三要素齐全 ─────────────────────────────────────────────────

    @Test
    fun `⑬每张卡三行自解释_缺一不合格`() {
        for (cls in AskClass.entries) {
            val note = AskCardComposer.note(cls, "示例问题：这一步怎么走")
            assertTrue("类别=$cls 缺「我要做什么」", note.contains(AskCardComposer.LINE_WHAT))
            assertTrue("类别=$cls 缺「为什么问」", note.contains(AskCardComposer.LINE_WHY))
            assertTrue("类别=$cls 缺「不答会怎样」", note.contains(AskCardComposer.LINE_CONSEQUENCE))
            assertTrue("不答会怎样要说清去向", note.contains("停") || note.contains("默认"))
        }
    }

    @Test
    fun `⑬授权类的不答后果是不做_偏好类是按默认继续`() {
        assertTrue(
            AskCardComposer.consequenceOf(AskClass.AUTHORIZATION).contains("不做"),
        )
        assertTrue(
            AskCardComposer.consequenceOf(AskClass.PREFERENCE).contains("默认"),
        )
    }

    // ── ⑭ 卡面不出现内部词 ───────────────────────────────────────────────

    @Test
    fun `⑭内部词被清掉_用户看不到R代号和类名`() {
        val hits = mutableListOf<String>()
        val cleaned = AskCardComposer.sanitize("按 R2 规则和 MUST_ASK 语义处理 GuardPipeline 的结果", hits)
        assertFalse(cleaned.contains("R2"))
        assertFalse(cleaned.contains("MUST_ASK"))
        assertFalse(cleaned.contains("GuardPipeline"))
        assertTrue("命中要记下来（违规账用）", hits.contains("R2") && hits.contains("MUST_ASK"))
        for (cls in AskClass.entries) {
            val note = AskCardComposer.note(cls, "带 R3 的脏问题")
            for (bad in listOf("R0", "R1", "R2", "R3", "MUST_ASK", "CONFIRM", "GuardPipeline", "RiskMatrix")) {
                assertFalse("类别=$cls 卡面出现内部词 $bad", note.contains(bad))
            }
        }
    }

    @Test
    fun `⑭脏标题进门闸_违规记账且卡面干净`() {
        fresh()
        val out = AskIntakeGate.beforeAsk(
            title = "R2 的这个删法要不要授权",
            taskId = "t14",
            dayKey = 14_000,
            nowMs = 14_001L,
        )
        assertTrue(out is AskIntakeOutcome.Proceed)
        val clean = (out as AskIntakeOutcome.Proceed).note
        assertFalse("用户不该看到内部代号", clean.contains("R2"))
        assertTrue(
            "内部词违规要记账",
            AskGovLedger.replay().any { it.kind == AskGovLedger.KIND_VIOLATION && it.basis.contains("内部词") },
        )
    }

    // ── ⑮ 同一件事不重复问 ───────────────────────────────────────────────

    @Test
    fun `⑮同任务同主题第二问不带通行证_压掉并记账`() {
        fresh()
        val first = AskIntakeGate.beforeAsk(
            title = "清理范围要含下载目录吗",
            taskId = "t15",
            dayKey = 15_000,
            nowMs = 15_001L,
        )
        assertTrue(first is AskIntakeOutcome.Proceed)
        val second = AskIntakeGate.beforeAsk(
            title = "清理范围要含下载目录吗",
            taskId = "t15",
            dayKey = 15_000,
            nowMs = 15_002L,
        )
        assertTrue("同一件事只问一次", second is AskIntakeOutcome.Answered)
        assertEquals(AskGovLedger.KIND_REPEATED, (second as AskIntakeOutcome.Answered).kind)
        assertTrue(
            "压掉的重复问要留一行",
            AskGovLedger.replay().any { it.kind == AskGovLedger.KIND_REPEATED },
        )
    }

    @Test
    fun `⑮带上次你说过的通行证_允许再问`() {
        fresh()
        AskIntakeGate.beforeAsk(
            title = "清理范围要含下载目录吗",
            taskId = "t16",
            dayKey = 16_000,
            nowMs = 16_001L,
        )
        val again = AskIntakeGate.beforeAsk(
            title = "上次你说过先不清理下载目录；现在情况变了，要含吗",
            taskId = "t16",
            dayKey = 16_000,
            nowMs = 16_002L,
        )
        assertTrue("带了通行证 ⇒ 可以再问", again is AskIntakeOutcome.Proceed)
    }

    @Test
    fun `⑮不同任务同字面_不互压`() {
        fresh()
        AskIntakeGate.beforeAsk(
            title = "要保留哪些格式",
            taskId = "task-a",
            dayKey = 17_000,
            nowMs = 17_001L,
        )
        val otherTask = AskIntakeGate.beforeAsk(
            title = "要保留哪些格式",
            taskId = "task-b",
            dayKey = 17_000,
            nowMs = 17_002L,
        )
        assertTrue("键带 taskId：另一件事不受上一件影响", otherTask is AskIntakeOutcome.Proceed)
    }

    // ── C 段：进门闸的日预算落在真卡上 ───────────────────────────────────

    @Test
    fun `C段_第4张问卡压成汇总不弹`() {
        fresh()
        val day = 18_000
        repeat(3) {
            val out = AskIntakeGate.beforeAsk(
                title = "问题$it：两处都讲得通走哪条",
                taskId = "budget-$it",
                dayKey = day,
                nowMs = 18_100L + it,
            )
            assertTrue(out is AskIntakeOutcome.Proceed)
        }
        val fourth = AskIntakeGate.beforeAsk(
            title = "问题3：两处都讲得通走哪条",
            taskId = "budget-3",
            dayKey = day,
            nowMs = 18_200L,
        )
        assertTrue("超预算 ⇒ 不弹", fourth is AskIntakeOutcome.Answered)
        assertEquals(AskGovLedger.KIND_COMPRESS, (fourth as AskIntakeOutcome.Answered).kind)
        assertTrue(fourth.text.contains("汇总"))
        assertTrue(
            "压卡落账",
            AskGovLedger.replay().any { it.kind == AskGovLedger.KIND_COMPRESS },
        )
        // 下一张真要弹的卡把汇总捎上（"压成一条汇总"的出去那一侧）
        val nextDay = AskIntakeGate.beforeAsk(
            title = "新一天的问题：走哪条路",
            taskId = "budget-4",
            dayKey = day + 1,
            nowMs = 19_000L,
        )
        assertTrue(nextDay is AskIntakeOutcome.Proceed)
        assertTrue(
            "汇总要出现在下一张卡上",
            (nextDay as AskIntakeOutcome.Proceed).note.contains("今日汇总"),
        )
    }
}
