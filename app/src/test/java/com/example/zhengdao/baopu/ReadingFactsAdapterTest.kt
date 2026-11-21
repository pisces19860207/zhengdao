// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十八批 · app 侧**投影器**夹具：验证"只读 753 账本 ⇒ 正确的 ReadingFacts"，
// 以及"722/747 未注入 ⇒ 恒无"（不得凭空造读数）、"注入闭包抛错 ⇒ 回退无"。
// 用 TempSandbox（建了必删），不碰真产物区、不碰水位。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.loops.Loop
import com.example.zhengdao.baopu.loops.LoopReadingsLedger
import com.example.zhengdao.baopu.loops.LoopReadingRow
import com.example.zhengdao.baopu.loops.SourceKind
import com.example.zhengdao.baopu.loops.Status
import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadingFactsAdapterTest {

    private val ts = 1_700_000_000_000L

    private fun planRow(stuckAt: Int?, ref: String, at: Long) = LoopReadingRow(
        ts = at, sceneId = "s1", loop = Loop.PLAN, status = Status.OK,
        reading = mapOf(
            "stepsLeft" to 2, "stuckAt" to stuckAt, "stepRef" to 2,
            "reason" to "剩 2 步；判不了就不猜",
        ),
        source = SourceKind.TOOL, ref = ref,
    )

    private fun rpeRow(value: Int, note: String, at: Long) = LoopReadingRow(
        ts = at, sceneId = "s1", loop = Loop.RPE, status = Status.OK,
        reading = mapOf("rpe" to value, "note" to note),
        source = SourceKind.BIO, ref = "BioLog:测试",
    )

    private fun write(ws: File, vararg rows: LoopReadingRow) {
        rows.forEach { LoopReadingsLedger.append(ws, it) }
    }

    @Test
    fun `工作区为空_投影全无读数_722与747未注入视为无`() {
        TempSandbox.use("zd778-adapter-empty") { ws ->
            val f = ReadingFactsAdapter.project(ws)
            assertTrue(f.isEmpty)
            assertFalse(f.readbackPresent)
            assertFalse(f.crossedWithoutEvidence)
            assertNull(f.stuckLoop)
            assertEquals(0, f.sameParamRetries)
        }
    }

    @Test
    fun `null工作区_投影全无_不崩`() {
        val f = ReadingFactsAdapter.project(null)
        assertTrue(f.isEmpty)
    }

    @Test
    fun `753账本两条同参卡步_投影出卡步相同与重试2次`() {
        TempSandbox.use("zd778-adapter-stuck") { ws ->
            write(
                ws,
                planRow(stuckAt = 3, ref = "step:3", at = ts),
                planRow(stuckAt = 3, ref = "step:3", at = ts + 1000),
            )
            val f = ReadingFactsAdapter.project(ws)
            assertEquals("3", f.stuckLoop)
            assertEquals("3", f.previousStuckLoop)
            assertEquals(2, f.sameParamRetries)
            assertFalse("stuckAt 为空的行不当卡步", f.rpeNegativeStreak == 2)
        }
    }

    @Test
    fun `stuckAt为空的plan行_不误判成卡步_不触发规则1`() {
        TempSandbox.use("zd778-adapter-nostuck") { ws ->
            write(ws, planRow(stuckAt = null, ref = "step:2", at = ts))
            val f = ReadingFactsAdapter.project(ws)
            assertNull(f.stuckLoop)
            assertEquals(0, f.sameParamRetries)
            assertFalse(ReadingCodex.evaluate(f).hit)
        }
    }

    @Test
    fun `753账本两条连续负RPE同因_投影出连负2与原因相同`() {
        TempSandbox.use("zd778-adapter-rpe") { ws ->
            write(
                ws,
                rpeRow(-1, "错——预测落空", ts),
                rpeRow(-1, "错——预测落空", ts + 1000),
            )
            val f = ReadingFactsAdapter.project(ws)
            assertEquals(2, f.rpeNegativeStreak)
            assertTrue(f.rpeCauseSame)
            assertEquals(1, ReadingCodex.evaluate(f).proposals.size)
        }
    }

    @Test
    fun `RPE原因不同_不判同类_规则2不命中`() {
        TempSandbox.use("zd778-adapter-rpediff") { ws ->
            write(
                ws,
                rpeRow(-1, "错——检索落空", ts),
                rpeRow(-1, "错——工具报错", ts + 1000),
            )
            val f = ReadingFactsAdapter.project(ws)
            assertEquals(2, f.rpeNegativeStreak)
            assertFalse(f.rpeCauseSame)
            assertFalse(ReadingCodex.evaluate(f).hit)
        }
    }

    @Test
    fun `722与747注入位_显式打开才为真_抛错回退无`() {
        TempSandbox.use("zd778-adapter-inject") { ws ->
            val on = ReadingFactsAdapter.project(
                ws,
                readbackPresent = { true },
                crossedWithoutEvidence = { true },
            )
            assertTrue(on.readbackPresent)
            assertTrue(on.crossedWithoutEvidence)

            val safe = ReadingFactsAdapter.project(
                ws,
                readbackPresent = { throw IllegalStateException("boom") },
                crossedWithoutEvidence = { throw IllegalStateException("boom") },
            )
            assertFalse("注入位抛错必须吞成无", safe.readbackPresent)
            assertFalse(safe.crossedWithoutEvidence)
        }
    }
}
