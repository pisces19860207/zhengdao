// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十二批开的题，第五百一十一批改判：**触发节奏**（周位闸）搬去 `Opportunistic`，
// 本文件只剩"抽检这条口径"自己那几条——样本不足（n<5）不许报零假 PASS（432 拍板补充②）
// 与"灾异轮不当自动档的账"。触发判据的靶在 `OpportunisticTest`，不在这里重复第二套。
package com.example.zhengdao.baopu.inspect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NocturnalAutoTest {

    // ── 周位的账：灾异轮不算（触发节奏本身在 OpportunisticTest）──────────────

    @Test
    fun `灾异轮不当自动档的账_6h冷却与7天下限都看它以外的最后一轮`() {
        val root = java.io.File("build/tmp/511-night-ledger").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }
        fun round(at: Long, emergency: Boolean) = NightRound(
            id = "night:$at", atMs = at, bucket = 0L, inspected = 5, hits = 0, noData = 0, emergency = emergency,
        )
        NocturnalLedger.append(root, round(10L, false))
        NocturnalLedger.append(root, round(20L, true))
        assertEquals("最新的是灾异轮也不算：自动档看的是自己那一轮", 10L, NocturnalLedger.lastAutoAt(root))
        NocturnalLedger.append(root, round(30L, false))
        assertEquals("后来的自动档那一轮才是账", 30L, NocturnalLedger.lastAutoAt(root))
        val onlyEmergency = java.io.File("build/tmp/511-night-ledger-only").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }
        NocturnalLedger.append(onlyEmergency, round(40L, true))
        assertNull("全是灾异 ⇒ 当没跑过（首趟不欠着）", NocturnalLedger.lastAutoAt(onlyEmergency))
        assertNull(NocturnalLedger.lastAutoAt(java.io.File("build/tmp/511-night-ledger-none").absoluteFile))
    }

    // ── 拍板补充②：样本不足（n<5）不许报"零假 PASS" ─────────────────────────

    @Test
    fun `判据4_样本不足n小于5_报样本不足_不许报零假PASS`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 3),
        )
        assertTrue("要点名样本不足：$line", line.contains("样本不足"))
        assertTrue("要写明抽到几条：$line", line.contains("只抽到 3 条"))
        assertTrue(line.contains("n<5"))
        assertFalse("样本不足不许显『正常』：$line", line.contains("正常"))
        assertFalse("样本不足不许报假 PASS 0：$line", line.contains("假 PASS 0"))
        assertFalse("不许报零产出：$line", line.contains("零产出"))
    }

    @Test
    fun `判据4_样本不足但真抽到假PASS_数字照实报`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 1, lastSampled = 2),
        )
        assertTrue("报问题不受样本数拦：$line", line.contains("假 PASS 1"))
        assertTrue("但样本不足照样点名：$line", line.contains("样本不足"))
        assertFalse(line.contains("正常"))
    }

    @Test
    fun `判据4_样本够5条零假PASS_仍显正常并带抽了几条`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 5),
        )
        assertTrue("409 那句话术原样在：$line", line.contains("已抽检 1 轮（假 PASS 0，正常）"))
        assertTrue("拍板补充③：要写明抽了 N 条：$line", line.contains("抽了 5 条"))
    }

    @Test
    fun `判据4_边界_4条不足_5条才够`() {
        val four = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 4),
        )
        assertTrue("n<5 是严格小于：$four", four.contains("样本不足"))
        val five = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 5),
        )
        assertFalse("恰好 5 条就够：$five", five.contains("样本不足"))
    }

    @Test
    fun `判据4_老口径行没有lastSampled_行文与409一字不差`() {
        val line = HealthCheck.samplingLine(
            "金吾卫",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 2, lastRunMs = STAMP, problems = 3),
        )
        assertTrue(line.contains("已抽检 2 轮（假 PASS 3）"))
        assertFalse("没有样本读数就不显那一格（金吾卫报告没记抽了几条）：$line", line.contains("抽了"))
    }

    private companion object {
        const val STAMP = 1_790_000_000_000L
    }

    /** cleanup-b571（2026-11-11）：本文件在 `build/tmp/` 下建的目录跑完必须删——
     *  它们是测试暂存区（564 的水位闸按这些目录计）；此前只建不删，水位只能一路涨。 */
    @After
    fun cleanTmpDirs571() {
        java.io.File("build/tmp/511-night-ledger").deleteRecursively()
        java.io.File("build/tmp/511-night-ledger-none").deleteRecursively()
        java.io.File("build/tmp/511-night-ledger-only").deleteRecursively()
    }

}
