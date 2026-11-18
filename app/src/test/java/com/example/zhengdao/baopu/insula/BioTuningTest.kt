// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶调节判据的测试（低电量夹具 ⇒ 并发度下调；白名单闭集恰好四个）。
package com.example.zhengdao.baopu.insula

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class BioTuningTest {

    /** 体征全健康的夹具（不触发任何动作的基线）。 */
    private fun healthy() = BioVitals(
        batteryPct = 80,
        charging = false,
        hot = false,
        heapPressure = 0.3,
        storageFreeRatio = 0.5,
        actP95Ms = 2_000L,
        budgetLeft = 3,
    )

    /** 判据主例：低电量夹具（18%、放电）⇒ 并发度下调 2→1 且动作带依据（落账行的原料）。 */
    @Test
    fun 判据_低电夹具并发度下调且动作带依据() {
        val lowBattery = healthy().copy(batteryPct = 18)
        val moves = insulaAdjust(BioTuning.DEFAULT, lowBattery)
        val concurrencyMove = moves.firstOrNull { it.param == TunableParam.CONCURRENCY }
        assertTrue("低电必须调并发度", concurrencyMove != null)
        assertEquals("并发度 2→1", "1", concurrencyMove!!.to)
        assertEquals("2", concurrencyMove.from)
        assertTrue("动作必须带依据（依据哪条读数）", concurrencyMove.basis.contains("电量 18%"))
        assertTrue("低电还要延后重活", moves.any { it.param == TunableParam.DEFER_HEAVY })
    }

    /** 充电中不算低电（与机会式触发的电源条件同一口径：充电即放行）。 */
    @Test
    fun 判据_充电中不动并发度() {
        val moves = insulaAdjust(BioTuning.DEFAULT, healthy().copy(batteryPct = 5, charging = true))
        assertTrue(moves.none { it.param == TunableParam.CONCURRENCY })
    }

    /** 发烫或预算耗尽 ⇒ 延后重活（重活等凉/等明天）。 */
    @Test
    fun 判据_发烫或预算耗尽延后重活() {
        val hotMoves = insulaAdjust(BioTuning.DEFAULT, healthy().copy(hot = true))
        assertTrue(hotMoves.any { it.param == TunableParam.DEFER_HEAVY && it.basis.contains("发烫") })
        val budgetMoves = insulaAdjust(BioTuning.DEFAULT, healthy().copy(budgetLeft = 0))
        assertTrue(budgetMoves.any { it.param == TunableParam.DEFER_HEAVY && it.basis.contains("预算") })
    }

    /** 存储告急或堆压到线 ⇒ 批量大小减到下界。 */
    @Test
    fun 判据_存储或内存告急批量下调() {
        val storageMoves = insulaAdjust(BioTuning.DEFAULT, healthy().copy(storageFreeRatio = 0.04))
        val batchMove = storageMoves.firstOrNull { it.param == TunableParam.BATCH_SIZE }
        assertEquals("批量 20→10", "10", batchMove!!.to)
        val heapMoves = insulaAdjust(BioTuning.DEFAULT, healthy().copy(heapPressure = 0.95))
        assertTrue(heapMoves.any { it.param == TunableParam.BATCH_SIZE })
    }

    /** p95 慢 ⇒ 重试冷却 ×2（封顶 2h）。 */
    @Test
    fun 判据_p95超线重试间隔加倍() {
        val moves = insulaAdjust(BioTuning.DEFAULT, healthy().copy(actP95Ms = 25_000L))
        val move = moves.firstOrNull { it.param == TunableParam.RETRY_BACKOFF }
        assertEquals("30min→60min", "${BioTuning.DEFAULT_RETRY_BACKOFF_MS * 2}ms", move!!.to)
        val capped = insulaAdjust(
            BioTuning.DEFAULT.copy(retryBackoffMs = BioTuning.MAX_RETRY_BACKOFF_MS),
            healthy().copy(actP95Ms = 25_000L),
        )
        assertTrue("已到上限不再加倍", capped.none { it.param == TunableParam.RETRY_BACKOFF })
    }

    /** 体征全健康 ⇒ 空动作表（最常见的轮次；空表不是失败）。 */
    @Test
    fun 判据_体征健康不动参数() {
        assertTrue(insulaAdjust(BioTuning.DEFAULT, healthy()).isEmpty())
    }

    /** 只收紧不放宽：已是最保守档 ⇒ 不再出新动作（防抖动）。 */
    @Test
    fun 判据_已到下界不再出动作() {
        val tightest = BioTuning(
            concurrency = BioTuning.MIN_CONCURRENCY,
            batchSize = BioTuning.MIN_BATCH_SIZE,
            retryBackoffMs = BioTuning.MAX_RETRY_BACKOFF_MS,
            deferHeavy = true,
        )
        val low = healthy().copy(batteryPct = 10, storageFreeRatio = 0.01, actP95Ms = 99_000L)
        assertTrue(insulaAdjust(tightest, low).isEmpty())
    }

    /** 白名单闭集：可调参数恰好四个，一个不多（第 N 个不存在）。 */
    @Test
    fun 判据_白名单恰好四参数() {
        assertEquals(4, TunableParam.entries.size)
        assertEquals(
            listOf("并发度", "批量大小", "重试间隔", "延后重活"),
            TunableParam.entries.map { it.cn },
        )
    }

    /** 动作套回现值（applyMoves 纯函数往返）。 */
    @Test
    fun 判据_动作套回现值一致() {
        val low = healthy().copy(batteryPct = 18, storageFreeRatio = 0.04, actP95Ms = 25_000L)
        val moves = insulaAdjust(BioTuning.DEFAULT, low)
        val next = applyMoves(BioTuning.DEFAULT, moves)
        assertEquals(BioTuning.MIN_CONCURRENCY, next.concurrency)
        assertEquals(BioTuning.MIN_BATCH_SIZE, next.batchSize)
        assertEquals(BioTuning.DEFAULT_RETRY_BACKOFF_MS * 2, next.retryBackoffMs)
        assertTrue(next.deferHeavy)
    }
}
