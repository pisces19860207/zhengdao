// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 388 派工单：电量<20%／过热／无网 ⇒ 暂停。本套钉三件事：
// 进得去（坏读数就停）、出得来（迟滞带，不许 20% 边缘振荡）、读不到不许装健康也不许据 null 复工。
package com.example.zhengdao.unattended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedEnvTest {

    private val empty = emptySet<UnattendedEnvReason>()

    private fun sample(
        pct: Int? = 80,
        tempTenths: Int? = 300,
        networkOk: Boolean = true,
    ) = UnattendedEnvSample(batteryPct = pct, batteryTempTenths = tempTenths, networkOk = networkOk)

    // ── 进入侧 ──────────────────────────────────────────────────────────

    @Test
    fun `电量低于20_暂停`() {
        val next = nextPauseReasons(sample(pct = 19), empty)
        assertEquals(setOf(UnattendedEnvReason.LOW_BATTERY), next)
    }

    @Test
    fun `电量字面_低于20才停_19停_20不停_派工单没有含20的口径`() {
        assertTrue(UnattendedEnvReason.LOW_BATTERY in nextPauseReasons(sample(pct = 19), empty))
        assertEquals(
            "20% 不在字面'低于20'内，进不得（已停的除外——那是迟滞带在维持）",
            empty,
            nextPauseReasons(sample(pct = 20), empty),
        )
    }

    @Test
    fun `电池温度达到43度_暂停`() {
        val next = nextPauseReasons(sample(tempTenths = 430), empty)
        assertEquals(setOf(UnattendedEnvReason.OVERHEAT), next)
    }

    @Test
    fun `网络不可用_暂停`() {
        val next = nextPauseReasons(sample(networkOk = false), empty)
        assertEquals(setOf(UnattendedEnvReason.NO_NETWORK), next)
    }

    @Test
    fun `三样一起坏_三格都进`() {
        val next = nextPauseReasons(sample(pct = 5, tempTenths = 450, networkOk = false), empty)
        assertEquals(UnattendedEnvReason.entries.toSet(), next)
    }

    // ── 迟滞带（不许 20% 边缘振荡） ─────────────────────────────────────

    @Test
    fun `已停的电量格_22不复工_25才复工`() {
        val low = setOf(UnattendedEnvReason.LOW_BATTERY)
        assertTrue(
            "22% 还在迟滞带里，不许复工",
            UnattendedEnvReason.LOW_BATTERY in nextPauseReasons(sample(pct = 22), low),
        )
        assertEquals(
            "25% 复工",
            empty,
            nextPauseReasons(sample(pct = 25), low),
        )
    }

    @Test
    fun `已停的过热格_41度不复工_40度及以下才复工`() {
        val hot = setOf(UnattendedEnvReason.OVERHEAT)
        assertTrue(
            UnattendedEnvReason.OVERHEAT in nextPauseReasons(sample(tempTenths = 401), hot),
        )
        assertEquals(
            empty,
            nextPauseReasons(sample(tempTenths = 400), hot),
        )
    }

    @Test
    fun `无网格进出同一把尺_网通即复工`() {
        val down = setOf(UnattendedEnvReason.NO_NETWORK)
        assertEquals(empty, nextPauseReasons(sample(networkOk = true), down))
    }

    // ── 读不到的数（fail-closed 的两侧） ────────────────────────────────

    @Test
    fun `电量读不到_不判低电_也不许据此复工`() {
        // 没停时：读不到不假装出事（null ⇒ 不进低电格）。
        assertEquals(empty, nextPauseReasons(sample(pct = null), empty))
        // 已停时：读不到不许复工（fail-closed 的复工侧）。
        val low = setOf(UnattendedEnvReason.LOW_BATTERY)
        assertEquals(low, nextPauseReasons(sample(pct = null), low))
    }

    @Test
    fun `温度读不到_不判过热_也不许据此复工`() {
        assertEquals(empty, nextPauseReasons(sample(tempTenths = null), empty))
        val hot = setOf(UnattendedEnvReason.OVERHEAT)
        assertEquals(hot, nextPauseReasons(sample(tempTenths = null), hot))
    }

    @Test
    fun `越界读数按读不到处理_负电量不算没电`() {
        assertEquals(empty, nextPauseReasons(sample(pct = -3), empty))
        assertEquals(empty, nextPauseReasons(sample(tempTenths = 9999), empty))
    }

    @Test
    fun `探针异常按无网处理_fail_closed`() {
        // 网络读不到 ⇒ networkOk=false 由装配层给，本层只认值：false 就停。
        assertTrue(UnattendedEnvReason.NO_NETWORK in nextPauseReasons(sample(networkOk = false), empty))
    }

    @Test
    fun `同一段读数序列回放_同一段暂停复工账`() {
        // 可回放：迟滞转移只看（当前状态，这一拍读数），没有任何时钟与随机。
        var state = empty
        val replay = listOf(19, 22, 25, 18, 26)
        val ledger = replay.map { pct ->
            state = nextPauseReasons(sample(pct = pct), state)
            state.toSet()
        }
        assertEquals(
            listOf(
                setOf(UnattendedEnvReason.LOW_BATTERY),
                setOf(UnattendedEnvReason.LOW_BATTERY),
                empty,
                setOf(UnattendedEnvReason.LOW_BATTERY),
                empty,
            ),
            ledger,
        )
    }
}
