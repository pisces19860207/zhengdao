// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 388 判据落点：**圈内自动／圈外入队／红线停手，各有测试**（派工单原话）。
// 三个判据各占一节，边界与优先级跟在后面——判定只有一个入口（judgeUnattended），
// 谁也不许在别处再长一套"该不该自动"。
package com.example.zhengdao.unattended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedCircleTest {

    private val calm = UnattendedSituation()

    // ── 判据一：圈内自动 ────────────────────────────────────────────────

    @Test
    fun `圈内_托付范围内_非不可逆_环境正常_自动`() {
        val ruling = judgeUnattended(UnattendedAction(inScope = true), calm)
        assertEquals(UnattendedVerdict.AUTO, ruling.verdict)
        assertTrue(ruling.why.contains("圈内"))
    }

    @Test
    fun `圈内自动的账行_三样都在_裁决_理由_一行`() {
        val ruling = judgeUnattended(UnattendedAction(inScope = true), calm)
        assertEquals("圈内自动｜圈内：托付范围内、非不可逆、环境正常", ruling.line)
    }

    // ── 判据二：圈外入队 ────────────────────────────────────────────────

    @Test
    fun `圈外_不在托付范围_入队`() {
        val ruling = judgeUnattended(UnattendedAction(inScope = false), calm)
        assertEquals(UnattendedVerdict.QUEUE, ruling.verdict)
        assertTrue(ruling.why.contains("圈外"))
    }

    @Test
    fun `不可逆动作_照旧排队_哪怕在圈内`() {
        // 388 派工单原话"不可逆动作照旧排队"：离场不给不可逆任何豁免（R3 永远必问）。
        val ruling = judgeUnattended(UnattendedAction(inScope = true, irreversible = true), calm)
        assertEquals(UnattendedVerdict.QUEUE, ruling.verdict)
        assertTrue(ruling.why.contains("不可逆照旧排队"))
    }

    // ── 判据三：红线停手 ────────────────────────────────────────────────

    @Test
    fun `红线_外发_停手`() {
        val ruling = judgeUnattended(UnattendedAction(inScope = true, outbound = true), calm)
        assertEquals(UnattendedVerdict.STOP, ruling.verdict)
        assertTrue(ruling.why.contains("外发"))
    }

    @Test
    fun `红线_越权_停手`() {
        val ruling = judgeUnattended(UnattendedAction(inScope = true, overreach = true), calm)
        assertEquals(UnattendedVerdict.STOP, ruling.verdict)
        assertTrue(ruling.why.contains("越权"))
    }

    // ── 优先级（书写序即判定序，不许调） ────────────────────────────────

    @Test
    fun `外发排在圈外之前_圈外的外发也是停手不是入队`() {
        // 圈外的动作照理入队，但外发是红线：排队排一条"待她授权后外发"是给未来的自己留门。
        val ruling = judgeUnattended(UnattendedAction(inScope = false, outbound = true), calm)
        assertEquals(UnattendedVerdict.STOP, ruling.verdict)
    }

    @Test
    fun `外发排在不可逆之前_不可逆的外发也是停手`() {
        // 与 QuietMode.decide 的「外发排在可回退之前」同一条法：留着撤回按钮也不算可回退。
        val ruling = judgeUnattended(
            UnattendedAction(inScope = true, irreversible = true, outbound = true),
            calm,
        )
        assertEquals(UnattendedVerdict.STOP, ruling.verdict)
    }

    @Test
    fun `降级只收回自动_不丢事_圈内也改入队`() {
        // 388 判据"B级汇总 2h 没出 ⇒ 降级停手"：停手＝不再自动执行，排队的事一件不丢。
        val ruling = judgeUnattended(
            UnattendedAction(inScope = true),
            UnattendedSituation(degraded = true),
        )
        assertEquals(UnattendedVerdict.QUEUE, ruling.verdict)
        assertTrue(ruling.why.contains("降级"))
    }

    @Test
    fun `环境暂停只收回自动_圈内也改入队`() {
        val ruling = judgeUnattended(
            UnattendedAction(inScope = true),
            UnattendedSituation(paused = true),
        )
        assertEquals(UnattendedVerdict.QUEUE, ruling.verdict)
        assertTrue(ruling.why.contains("环境暂停"))
    }

    @Test
    fun `红线不看降级_降级中的外发仍是停手`() {
        val ruling = judgeUnattended(
            UnattendedAction(inScope = true, outbound = true),
            UnattendedSituation(degraded = true),
        )
        assertEquals(UnattendedVerdict.STOP, ruling.verdict)
    }

    @Test
    fun `裁决不许空手_每一格都带为什么`() {
        UnattendedVerdict.entries.forEach { verdict ->
            val ruling = when (verdict) {
                UnattendedVerdict.AUTO -> judgeUnattended(UnattendedAction(inScope = true), calm)
                UnattendedVerdict.QUEUE -> judgeUnattended(UnattendedAction(inScope = false), calm)
                UnattendedVerdict.STOP -> judgeUnattended(UnattendedAction(inScope = true, outbound = true), calm)
            }
            assertEquals(verdict, ruling.verdict)
            assertTrue("为什么不许空：${verdict.wire}", ruling.why.isNotBlank())
        }
    }
}
