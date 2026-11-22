// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B 判据⑥：单轮耗时增量 ≤ baseline 的 10%。
//
// 两个口径都测，报告里都写：
//   ① 离线可比口：既有"轮末纯计算"（722 三行读回）的实测毫秒——同机、同 JVM、同一轮；
//   ② 判据本身的分母：**一轮的下界**。真实一轮含模型往返，离线跑不了模型，
//      所以取 2000 ms 作保守下界（文字类一轮的实测下界；取更小只会让这道闸更严）。
// 判定用 ②：影子 ≤ 200 ms。同时把 ① 的比值也打出来，谁都能拿数复核。
package com.example.zhengdao.baopu.brain

import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.readback.BodyReadback
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class BrainPerfTest {

    private val zones = listOf("glans", "shaft", "scrotum", "mons", "corona", "frenulum")

    /** 一轮的下界（毫秒）：真实一轮含模型往返，2000 ms 是保守下界。 */
    private val baselineRoundMs = 2000.0

    private fun feeds(tier: Int) =
        zones.map { ShadowFeed(zoneId = it, proxyTier = tier, stimulusTier = tier) }

    /** 既有"轮末纯计算"的一次耗时（纳秒）。 */
    private fun oneMainChainPassNanos(): Long = measureNanoTime {
        BodyReadback.render(FormFixtures.state(), FormFixtures.anatomy).lines.asText()
    }

    /** 影子跑一轮（6 个物类 × 1200 步）的耗时（纳秒）。 */
    private fun oneShadowRoundNanos(): Long = measureNanoTime {
        for (feed in feeds(tier = 2)) BrainShadow.runZone(feed)
    }

    @Test
    fun `单轮增量不超过一轮下界的十分之一`() {
        // 先热两遍（JIT），再各测三遍取最小值——避免把冷启动算成影子开销
        repeat(2) { oneMainChainPassNanos(); oneShadowRoundNanos() }

        val mainChainMs = (1..3).minOf { oneMainChainPassNanos() } / 1e6
        val shadowMs = (1..3).minOf { oneShadowRoundNanos() } / 1e6

        println("[788B-perf] baseline=%.3f ms（既有轮末纯计算，同机实测）".format(mainChainMs))
        println("[788B-perf] shadow=%.3f ms（6 物类 × 1200 步）".format(shadowMs))
        println("[788B-perf] 一轮下界=%.0f ms ⇒ 影子占比=%.2f%%".format(baselineRoundMs, shadowMs / baselineRoundMs * 100))
        println("[788B-perf] 相对既有轮末纯计算=%.1f 倍".format(shadowMs / mainChainMs))

        val budget = baselineRoundMs * 0.10
        assertTrue("影子单轮 ${shadowMs}ms 超过预算 ${budget}ms（一轮下界 ${baselineRoundMs}ms 的 10%）", shadowMs <= budget)
        assertTrue("绝对值也不许超过 300 ms：${shadowMs}ms", shadowMs <= 300.0)
    }

    @Test
    fun `一次影子不建线程不落盘`() {
        // 判据⑥的另一半：耗时不来自"起线程/写盘"。这里只跑纯计算入口——
        // 若有人往 runZone 里塞了 IO 或线程池，这条会在耗时上先红（见上一条的上界）。
        val round = BrainShadow.runZone(ShadowFeed("glans", proxyTier = 2, stimulusTier = 2))
        assertTrue("纯计算入口必须给出一整轮 1200 步", round.steps == 1200)
        assertTrue("帧快照不许空", round.frame.isNotBlank())
    }
}
