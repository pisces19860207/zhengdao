// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 纹状体习惯判据的测试（造 5 次相同成功序列 ⇒ 出建议；差一次不成）。
package com.example.zhengdao.baopu.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class HabitRulesTest {

    private val flow = listOf("write_file", "read_file", "list_dir")

    private fun run(atMs: Long, ok: Boolean = true, rpe: Int = RPE_PASS, tools: List<String> = flow) =
        RunSeq(runId = "r1@$atMs", tools = tools, ok = ok, rpe = rpe, atMs = atMs)

    private fun nowBase() = 1_000_000_000L

    /** 判据主例：同一序列造 5 次全成功 ⇒ 恰好一条候选，count=5，卡与许愿原话齐备。 */
    @Test
    fun 判据_同一序列五次全成才出建议() {
        val base = nowBase()
        val runs = (0 until 5).map { run(base + it * 1_000L) }
        val candidates = habitCandidatesOf(runs, emptySet(), base + 10_000L)
        assertEquals(1, candidates.size)
        val card = suggestionOf(candidates.single())
        assertEquals(5, card.count)
        assertEquals(flow.joinToString(">"), card.seqKey)
        assertTrue("卡 id 带 habit: 前缀", card.id.startsWith("habit:"))
        assertTrue("许愿原话带流程与次数", card.wishText.contains("5") && card.wishText.contains("write_file"))
    }

    /** 差一次不成：4 次 ⇒ 无建议（阈值钉在 5，不许放水）。 */
    @Test
    fun 判据_四次不出建议() {
        val base = nowBase()
        val runs = (0 until 4).map { run(base + it * 1_000L) }
        assertTrue(habitCandidatesOf(runs, emptySet(), base + 10_000L).isEmpty())
    }

    /** 5 次里有一次失败 ⇒ 整条链掐断（习惯要的是「每次都成功」）。 */
    @Test
    fun 判据_五次中夹一次失败不出建议() {
        val base = nowBase()
        val runs = (0 until 5).map { run(base + it * 1_000L, ok = false, rpe = -1) }
        assertTrue(habitCandidatesOf(runs, emptySet(), base + 10_000L).isEmpty())
    }

    /** 一次被纠正也不算（差读数 0 档——成了但被打回，不是一次过）。 */
    @Test
    fun 判据_五次中夹一次被纠正不出建议() {
        val base = nowBase()
        val runs = (0 until 4).map { run(base + it * 1_000L) } + run(base + 9_000L, rpe = 0)
        assertTrue(habitCandidatesOf(runs, emptySet(), base + 10_000L).isEmpty())
    }

    /** 已建议过的键不再建议（判据③去重；不催第二遍）。 */
    @Test
    fun 判据_已建议过的序列不再出() {
        val base = nowBase()
        val runs = (0 until 5).map { run(base + it * 1_000L) }
        val key = flow.joinToString(">")
        assertTrue(habitCandidatesOf(runs, setOf(key), base + 10_000L).isEmpty())
    }

    /** 一轮最多 [HabitLimits.SUGGEST_MAX] 张（习惯化是轻声提醒，不是清单）。 */
    @Test
    fun 判据_一轮建议数有上限() {
        val base = nowBase()
        val pools = (0 until 5).map { p ->
            (0 until 5).map { run(base + p * 100_000L + it * 1_000L, tools = listOf("t$p", "u$p")) }
        }.flatten()
        val candidates = habitCandidatesOf(pools, emptySet(), base + 1_000_000L)
        assertEquals(HabitLimits.SUGGEST_MAX, candidates.size)
    }

    /** 序列太短（单工具）不算流程；太长（>12 步）按杂事丢弃。 */
    @Test
    fun 判据_序列长度封顶封底() {
        assertNull(sequenceKeyOf(listOf("write_file")))
        assertNull(sequenceKeyOf((1..13).map { "t$it" }))
        assertNotNull(sequenceKeyOf(listOf("write_file", "read_file")))
        assertNull(sequenceKeyOf(listOf("write_file", " ")))
    }

    /** 窗外（30 天前）的运行不计数——老黄历不算习惯。 */
    @Test
    fun 判据_窗外运行不计入() {
        val base = nowBase()
        val runs = (0 until 5).map { run(base - HabitLimits.WINDOW_MS - it * 1_000L) }
        assertTrue(habitCandidatesOf(runs, emptySet(), base).isEmpty())
    }

    /** 去重键按序列不按次数：不同 runId 的同序列归并成一条候选。 */
    @Test
    fun 判据_同序列不同运行归并() {
        val base = nowBase()
        val runs = (0 until 6).map { run(base + it * 1_000L) }
        val candidates = habitCandidatesOf(runs, emptySet(), base + 10_000L)
        assertEquals(1, candidates.size)
        assertEquals(6, candidates.single().count)
    }
}
