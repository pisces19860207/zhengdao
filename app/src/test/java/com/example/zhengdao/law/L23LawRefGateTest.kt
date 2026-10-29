// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L23 法条的引用义务**（法典卷七 L23；270 已加 `lawRef` 字段）。
//
// ## 判据（可执行）
// ① 每条 `Opinion` **必须带 `lawRef`**（命中法条号，或显式 `NONE`＝"无法可依"，那要走 L21）；
// ② `lawRef = NONE` 且非 critical 的意见**不得进入合并**——降级为「建议」（`suggestionOnly`）；
// ③ 合并后的那一条必须**带上组内法条引用**（不许合并完把 lawRef 丢了）。
//
// 为什么这条是结构约束而不是口号：`lawRef` 是枚举、非空，编译器挡住了"忘了填"；
// 但"填 NONE 还照常进合并"是能编过的，所以要一条门禁钉住"降级"这一步。
//
// ## 负例（本文件自证会红）
// ① `lawRef = NONE` 的 MAJOR 意见若进了合并 ⇒ 红；
// ② 合并后 lawRef 被丢成 NONE ⇒ 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.OpinionMerger
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L23LawRefGateTest {

    private fun opinion(
        lawRef: LawRef,
        severity: Severity = Severity.MAJOR,
        gist: String = "审计漏行",
        evidence: String = "208#4821",
        source: Source = Source.PATROL,
        vote: Int = 0,
    ) = Opinion(
        source = source,
        severity = severity,
        gist = gist,
        action = ActionCode.REVIEW,
        lawRef = lawRef,
        evidence = listOf(evidence),
        vote = vote,
    )

    private val pickFirst: (List<Opinion>) -> Opinion = { it.first() }

    // ── 正例：带法条号的意见进合并，且合并后法条号还在 ─────────────────────

    @Test
    fun `L23_正例_带法条号的意见进合并且合并后不丢法条号`() {
        val merged = OpinionMerger.merge(
            listOf(
                opinion(LawRef.L4, evidence = "208#1"),
                opinion(LawRef.L15, severity = Severity.MINOR, evidence = "208#1"),
            ),
            pickFirst,
        )
        assertEquals("同一件事的两条意见必须合并成一条", 1, merged.merged.size)
        assertEquals("合并后必须留下法条号", LawRef.L4, merged.merged[0].lawRef)
        assertFalse("带法条号的意见不是「只是建议」", merged.merged[0].suggestionOnly)
        assertEquals("证据要合并去重", 1, merged.merged[0].evidence.size)
    }

    // ── 负例①：lawRef=NONE 且非 critical ⇒ 不许进合并 ──────────────────────

    @Test
    fun `L23_负例1_lawRef为空且非critical的意见不得进合并`() {
        val result = OpinionMerger.merge(
            listOf(
                opinion(LawRef.NONE, evidence = "208#2"),
                opinion(LawRef.L15, evidence = "208#3"),
            ),
            pickFirst,
        )
        assertEquals("只有带法条号的那一条能进合并", 1, result.merged.size)
        assertEquals("无依据的那条必须降级成建议", 1, result.suggestions.size)
        assertTrue("降级的那条 lawRef 必须是 NONE", result.suggestions[0].lawRef == LawRef.NONE)
        assertTrue("建议不进合并", result.merged.none { it.evidence.contains("208#2") })
    }

    // ── 负例②：合并不许把法条号丢了 ─────────────────────────────────────────

    @Test
    fun `L23_负例2_合并后法条号被丢成NONE即红`() {
        // 组内第一条是 NONE、第二条有法条号 ⇒ 合并结果必须取到有法条号的那个
        val result = OpinionMerger.merge(
            listOf(
                opinion(LawRef.NONE, severity = Severity.MINOR, evidence = "208#9"),
                opinion(LawRef.L14, severity = Severity.MAJOR, evidence = "208#9"),
            ),
            pickFirst,
        )
        assertEquals("同事件必须合并", 1, result.merged.size)
        assertEquals(
            "合并后法条号被丢了：${result.merged[0].lawRef}（组里有 L14，不许落成 NONE）",
            LawRef.L14,
            result.merged[0].lawRef,
        )

        // critical + NONE 仍要进合并（无法可依但很危险 ⇒ 走 L21 上报，不当成"只是建议"）
        val critical = OpinionMerger.merge(listOf(opinion(LawRef.NONE, severity = Severity.CRITICAL, evidence = "208#10")), pickFirst)
        assertEquals("critical 的 NONE 必须进合并", 1, critical.merged.size)
        assertFalse("critical 不是「只是建议」", critical.merged[0].suggestionOnly)
    }
}
