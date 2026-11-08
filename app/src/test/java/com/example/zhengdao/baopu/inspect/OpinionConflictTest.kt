// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批判据①/②：冲突记录只做旁证——`merged` 与改造前逐字一致，
// `conflicts` 只在「同 eventKey 且 gist 不同或 severity 反向」时出现。
package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionConflictTest {

    private fun opinion(
        source: Source,
        severity: Severity,
        gist: String,
        evidence: List<String>,
        law: LawRef = LawRef.L5,
        vote: Int = 0,
    ) = Opinion(source, severity, gist, ActionCode.REVIEW, law, evidence, vote)

    @Test
    fun `判据1_同eventKey不同gist_有1条冲突且merged与改造前一致`() {
        val patrol = opinion(Source.PATROL, Severity.CRITICAL, "漏账", listOf("event:1", "log:1"), LawRef.L5, -1)
        val audit = opinion(Source.AUDIT, Severity.MAJOR, "账实不符", listOf("event:1", "audit:1"), LawRef.L6, -2)

        val result = OpinionMerger.merge(listOf(patrol, audit)) { group ->
            group.single { it.source == Source.PATROL }
        }

        // ① 冲突：恰好一条，eventKey 按同一条证据首项
        val conflict = result.conflicts.single()
        assertEquals("ev1:event:1", conflict.eventKey)
        assertEquals(setOf("漏账", "账实不符"), setOf(conflict.a.gist, conflict.b.gist))
        assertTrue(conflict.evidence.containsAll(listOf("event:1", "log:1", "audit:1")))

        // ① 金样例：选择逻辑一个字没动——mostSpecific 选谁就是谁，票数相加、证据并集
        val merged = result.merged.single()
        assertEquals(patrol.copy(evidence = listOf("event:1", "log:1", "audit:1"), vote = -3), merged)
        assertTrue("建议级不受影响", result.suggestions.isEmpty())
    }

    @Test
    fun `判据2_同eventKey同gist_冲突为空`() {
        val a = opinion(Source.PATROL, Severity.MAJOR, "漏账", listOf("event:2", "log:2"))
        val b = opinion(Source.AUDIT, Severity.MAJOR, " 漏账 ", listOf("event:2", "audit:2"))

        val result = OpinionMerger.merge(listOf(a, b)) { group -> group.first() }

        assertTrue("同一条事实的重复观察不算冲突", result.conflicts.isEmpty())
        assertEquals(1, result.merged.size)
    }

    @Test
    fun `同gist但severity方向相反_算冲突`() {
        val a = opinion(Source.PATROL, Severity.CRITICAL, "漏账", listOf("event:3", "log:3"))
        val b = opinion(Source.AUDIT, Severity.MINOR, "漏账", listOf("event:3", "audit:3"))

        val conflicts = OpinionMerger.conflictsOf(listOf(a, b))

        assertEquals("CRITICAL 与 MINOR 是一头一尾", 1, conflicts.size)
    }

    @Test
    fun `不同eventKey的两条意见不算冲突`() {
        val a = opinion(Source.PATROL, Severity.CRITICAL, "漏账", listOf("event:4", "log:4"))
        val b = opinion(Source.AUDIT, Severity.MINOR, "账实不符", listOf("event:5", "audit:5"))

        assertTrue(OpinionMerger.conflictsOf(listOf(a, b)).isEmpty())
    }

    @Test
    fun `同一对重试只出一条冲突`() {
        val a = opinion(Source.PATROL, Severity.CRITICAL, "漏账", listOf("event:6", "log:6"))
        val b = opinion(Source.AUDIT, Severity.MAJOR, "账实不符", listOf("event:6", "audit:6"))
        val bRetry = b.copy(evidence = listOf("event:6", "audit:6", "retry:1"))

        val conflicts = OpinionMerger.conflictsOf(listOf(a, b, bRetry))

        assertEquals("重试不该把列表撑大", 1, conflicts.size)
    }
}
