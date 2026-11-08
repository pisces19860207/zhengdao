// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百七十九批 · **两级刹车＋单件暂停**的判据靶（四器共用一套）。
//
// 判据②：一级刹车（自动降频）四个条件各拦一次 ⇒ 延后且**落账**（假读数：读数是参数，不是系统）；
// 判据③：二级刹车（急停，沿用 471 的软停/硬停）⇒ **四器一个都不启动**；
// 判据④：单件暂停只停那一只、其余照跑；24h 到期自动恢复（假时钟）。
// 判据⑤：落账那一行三格齐全（谁触发 · 机会读数 · 结果一行；延后那行则是谁问 · 读数 · 为什么）。
//
// 全部纯函数：时刻、停机态、停到几点都由用例给——没有一条断言依赖真实时钟或 SP。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.maintenance.MaintDevice
import com.example.zhengdao.baopu.maintenance.MaintPause
import com.example.zhengdao.baopu.maintenance.Opportunity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintBrakeTest {

    private val now = 1_793_289_600_000L
    private val minute = 60_000L
    private val hour = 3_600_000L

    /** 条件齐的读数（电量 80%、不充电、静默 2 小时、无重活、不烫、无会话、非值守）。 */
    private fun opp(
        battery: Int = 80,
        charging: Boolean = false,
        quietMin: Long = 120,
        heavy: Boolean = false,
        hot: Boolean = false,
        talking: Boolean = false,
        onDuty: Boolean = false,
    ) = Opportunity(
        onDuty = onDuty,
        batteryPct = battery,
        charging = charging,
        quietForMs = quietMin * minute,
        heavyBusy = heavy,
        hot = hot,
        talking = talking,
    )

    /** 一级刹车那一条判出来的"为什么"，加上装配侧会落的那一行账（与生产同一个函数）。 */
    private fun deferredLine(
        device: MaintDevice = MaintDevice.NIGHTWATCH,
        source: AskSource = AskSource.SCHEDULED,
        o: Opportunity,
    ): String {
        val why = Opportunistic.blockedBy(o)
        assertNotNull("这份读数该被拦下来（前提写错了？）", why)
        return MaintBrake.deferredLine(device, source, why!!, Opportunistic.readout(o))
    }

    // ── 判据②：一级刹车——四条降频条件各拦一次，且都落账 ────────────────────

    @Test
    fun `判据②电量低于20且未充电_延后并落账`() {
        val line = deferredLine(o = opp(battery = 19, charging = false))
        assertTrue(line, line.contains("自动维护"))
        assertTrue(line, line.contains("暗卫"))
        assertTrue(line, line.contains("延后"))
        assertTrue("要说清电量：$line", line.contains("电量 19%"))
        assertTrue("要说清为什么：$line", line.contains("为什么："))
        // 边界：20%（等于线）就够、插着电也不看电量
        assertNull(Opportunistic.blockedBy(opp(battery = 20)))
        assertNull(Opportunistic.blockedBy(opp(battery = 19, charging = true)))
    }

    @Test
    fun `判据②设备发烫_延后并落账`() {
        val line = deferredLine(o = opp(hot = true))
        assertTrue(line, line.contains("发烫"))
        assertTrue(line, line.contains("读数："))
    }

    @Test
    fun `判据②前台正在会话_延后并落账`() {
        val line = deferredLine(o = opp(talking = true))
        assertTrue(line, line.contains("会话"))
        assertTrue(line, line.contains("读数："))
    }

    @Test
    fun `判据②最近30分钟已有自动动作_延后并落账`() {
        val line = deferredLine(o = opp(quietMin = 29))
        assertTrue(line, line.contains("29 分钟"))
        // 满 30 分钟就放行（490 那一条不变）
        assertNull(Opportunistic.blockedBy(opp(quietMin = 30)))
    }

    @Test
    fun `判据②正在跑重活_延后并落账`() {
        val line = deferredLine(o = opp(heavy = true))
        assertTrue(line, line.contains("重活"))
        // 值守那一条（511）没被这次改动碰掉
        assertTrue(Opportunistic.blockedBy(opp(onDuty = true)).orEmpty().contains("值守"))
    }

    @Test
    fun `判据②延后那一行三格齐全_谁问的与读数与为什么`() {
        val line = deferredLine(o = opp(hot = true), source = AskSource.EVENT)
        listOf("谁问：收工事件", "读数：", "为什么：").forEach {
            assertTrue("缺「$it」：$line", line.contains(it))
        }
        // 三个"谁问"都认得出（周期那一趟＝7 天下限那一截）
        assertEquals("周期作业", MaintBrake.askCn(AskSource.SCHEDULED))
        assertEquals("收工事件", MaintBrake.askCn(AskSource.EVENT))
        assertEquals("人手", MaintBrake.askCn(AskSource.MANUAL))
    }

    // ── 判据③：二级刹车——急停 ⇒ 四器一律不启动 ─────────────────────────────

    @Test
    fun `判据③急停生效_四器都不启动`() {
        MaintDevice.entries.forEach { device ->
            val why = MaintBrake.blocked(device, halted = true, pausedUntilMs = 0L, nowMs = now)
            assertEquals("${device.cn} 急停期间还在启动？", MaintBrake.WHY_HALT, why)
        }
        // 四器名单是闭集，且没有第五只混进来
        assertEquals(4, MaintDevice.entries.size)
        assertEquals(
            listOf("jinwu", "nightwatch", "doctor", "patrol"),
            MaintDevice.entries.map { it.wire },
        )
        // 急停盖过一切：连单件暂停的理由都不许冒充成"急停已解除"
        assertEquals(
            MaintBrake.WHY_HALT,
            MaintBrake.blocked(MaintDevice.PATROL, halted = true, pausedUntilMs = now + hour, nowMs = now),
        )
        // 急停也拦"人当场点的那一下"（471：停机期间四器一律不启动）
        assertEquals(
            MaintBrake.WHY_HALT,
            MaintBrake.blocked(MaintDevice.JINWU, halted = true, pausedUntilMs = 0L, nowMs = now, manual = true),
        )
    }

    @Test
    fun `判据③没急停就放行_不冤枉它停机`() {
        MaintDevice.entries.forEach { device ->
            assertNull(
                "${device.cn} 没停却被拦",
                MaintBrake.blocked(device, halted = false, pausedUntilMs = 0L, nowMs = now),
            )
        }
    }

    // ── 判据④：单件暂停——只停那一只、24h 自动恢复（假时钟） ─────────────────

    @Test
    fun `判据④停暗卫只停暗卫_其余三只照跑`() {
        val until = MaintPause.extend(0L, now) // 默认 24h
        assertNotNull(
            "暗卫该被停住",
            MaintBrake.blocked(MaintDevice.NIGHTWATCH, halted = false, pausedUntilMs = until, nowMs = now),
        )
        listOf(MaintDevice.JINWU, MaintDevice.DOCTOR, MaintDevice.PATROL).forEach { other ->
            assertNull(
                "${other.cn} 被连坐了（单件暂停只许停一只）",
                MaintBrake.blocked(other, halted = false, pausedUntilMs = 0L, nowMs = now),
            )
        }
    }

    @Test
    fun `判据④24小时到点自动恢复_不用谁去点恢复`() {
        val until = MaintPause.extend(0L, now)
        assertEquals("默认就是 24 小时", 24 * hour, until - now)
        assertTrue("刚停：停着", MaintPause.stillPaused(until, now))
        assertTrue("差 1 毫秒还停着", MaintPause.stillPaused(until, until - 1))
        assertFalse("到点即恢复", MaintPause.stillPaused(until, until))
        assertNull(
            "到点那一趟就该放行",
            MaintBrake.blocked(MaintDevice.NIGHTWATCH, halted = false, pausedUntilMs = until, nowMs = until),
        )
    }

    @Test
    fun `判据④可续_是在剩余时间上再加一段_不是从此刻重算`() {
        val first = MaintPause.extend(0L, now)
        val later = now + 2 * hour
        assertEquals("续 24h ＝ 原终点再往后推 24h", first + 24 * hour, MaintPause.extend(first, later))
        val expiredAt = first + hour
        assertEquals("已过期 ⇒ 从此刻起算", expiredAt + 24 * hour, MaintPause.extend(first, expiredAt))
        // 剩多久要说得出来（落账那一行用的就是它）：刚停那一刻正好 24 小时
        assertTrue(MaintPause.leftLabel(first - now).contains("24 小时"))
    }

    @Test
    fun `判据④单件暂停不拦人当场点的那一下_但急停拦`() {
        val until = MaintPause.extend(0L, now)
        assertNull(
            "人点的那一下不该被单件暂停挡住（停的是自动）",
            MaintBrake.blocked(MaintDevice.JINWU, halted = false, pausedUntilMs = until, nowMs = now, manual = true),
        )
    }

    @Test
    fun `暂停键按器名走_四只各一把锁_不共用一格`() {
        val keys = MaintDevice.entries.map { MaintPause.keyOf(it) }
        assertEquals("四把锁四个键", 4, keys.toSet().size)
        MaintDevice.entries.forEach { device ->
            assertEquals(device, MaintPause.deviceOfKey(MaintPause.keyOf(device)))
        }
        assertNull("别的键认不回器", MaintPause.deviceOfKey("auto_maintenance"))
        assertEquals("SP 复用既有文件、不新表", "zhengdao-inspect", MaintPause.SP)
        assertEquals("暗卫的键沿用 432 的线名", "pause_until_nightwatch", MaintPause.keyOf(MaintDevice.NIGHTWATCH))
    }

    // ── 判据⑤：执行那一行账三格齐全 ────────────────────────────────────────

    @Test
    fun `判据⑤执行那一行_谁触发与读数与结果一行`() {
        val line = MaintBrake.runningLine(
            device = MaintDevice.PATROL,
            triggerCn = TriggerReason.LOAD.cn,
            readout = Opportunistic.readout(opp(battery = 62)),
            tail = "巡了 6 项（2 命中）",
        )
        listOf(
            "自动维护", "巡按", "执行",
            "谁触发：${TriggerReason.LOAD.cn}", "读数：非值守·电量 62%", "结果：巡了 6 项",
        ).forEach { assertTrue("缺「$it」：$line", line.contains(it)) }
        // 三条触发的名字与 511 一致（自动＝负载／7 天下限那两个，人手那条不算自动）
        assertEquals("负载", TriggerReason.LOAD.cn)
        assertEquals("下限", TriggerReason.FLOOR.cn)
    }
}
