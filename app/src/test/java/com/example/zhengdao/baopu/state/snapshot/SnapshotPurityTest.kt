// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十四批 · 判据④零副作用、判据⑥性能基线（本机实测，先测后报）。
// 注：本测试类用 System.nanoTime 只为计时，快照生产代码绝不读墙钟。
package com.example.zhengdao.baopu.state.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPurityTest {

    @Test
    fun `判据四_装配前后各包状态逐字不变`() {
        val input = SnapshotExample.input()
        val before = input.toString()
        val beforeJson = SnapshotJson.render(SnapshotAssembler.assemble(input))
        repeat(50) { SnapshotAssembler.assemble(input) }
        assertEquals("输入整体不许被改", before, input.toString())
        assertEquals("导出不许随装配次数漂移", beforeJson, SnapshotJson.render(SnapshotAssembler.assemble(input)))
    }

    @Test
    fun `判据四_空输入装配也不产生副作用`() {
        val input = SnapshotInput(tick = 99)
        val before = input.copy()
        val first = SnapshotAssembler.assemble(input)
        repeat(20) { SnapshotAssembler.assemble(input) }
        assertEquals("空输入对象不变", before, input)
        assertEquals("空输入结论不变", first, SnapshotAssembler.assemble(input))
    }

    @Test
    fun `判据六_单次快照不超过五毫秒`() {
        // 先测基线：空输入（几乎只有建对象）与满输入（六段全装配）各测一轮。
        val baseline = medianMs(SnapshotInput(tick = 1), rounds = 5, perRound = 200)
        val full = medianMs(SnapshotExample.input(), rounds = 5, perRound = 200)
        println("834 性能基线：空输入 ${baseline.ms} ms/次；满输入 ${full.ms} ms/次（各 5 轮 × 200 次取中位）")
        assertTrue("空输入基线超 5 ms：${baseline.ms}", baseline.ms <= 5.0)
        assertTrue("满输入快照超 5 ms：${full.ms}", full.ms <= 5.0)
    }

    private data class Measured(val ms: Double)

    /** 预热后按轮计时取中位，避免单次 GC 抖动被当成判据。 */
    private fun medianMs(input: SnapshotInput, rounds: Int, perRound: Int): Measured {
        repeat(100) { SnapshotAssembler.assemble(input) }
        val samples = (0 until rounds).map {
            val start = System.nanoTime()
            repeat(perRound) { SnapshotAssembler.assemble(input) }
            (System.nanoTime() - start) / 1_000_000.0 / perRound
        }.sorted()
        return Measured(samples[samples.size / 2])
    }
}
