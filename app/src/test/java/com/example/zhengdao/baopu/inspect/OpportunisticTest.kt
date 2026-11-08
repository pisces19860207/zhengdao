// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · 机会式触发的**判据靶**（暗卫＋巡按共用一套）：三条触发（手动／负载／7 天下限）、
// 六条机会条件、各自 6h 冷却、重活互斥。判据全是纯函数，时刻与读数由用例给——假时钟钉得住。
// 第四百七十九批：总开关删了（判据①那一条用例随开关一起退役）、电量线 30%→20%、
// 新增发烫与前台会话两条机会条件——本文件跟着改口径，断言没放松。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.maintenance.Opportunity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunisticTest {

    private val now = 1_793_289_600_000L

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    /** 条件齐的机会读数（默认：非值守、电量 80%、不充电、静默 2 小时、无重活、不烫、无会话）。 */
    private fun opp(
        battery: Int = 80,
        charging: Boolean = false,
        onDuty: Boolean = false,
        quietMin: Long = 120,
        heavy: Boolean = false,
        hot: Boolean = false,
        talking: Boolean = false,
    ) = Opportunity(
        onDuty = onDuty,
        batteryPct = battery,
        charging = charging,
        quietForMs = if (quietMin == Long.MAX_VALUE) Long.MAX_VALUE else quietMin * minute,
        heavyBusy = heavy,
        hot = hot,
        talking = talking,
    )

    private fun decide(
        job: MaintJob = MaintJob.NIGHTWATCH,
        source: AskSource = AskSource.EVENT,
        last: Long? = null,
        load: Load = Load.NONE,
        o: Opportunity = opp(),
    ): AutoGate = Opportunistic.decide(job, source, now, last, load, o)

    // ── 判据②：达阈值且条件满足 ⇒ 自动跑一次 ──────────────────────────────

    @Test
    fun `判据2_负载到阈就自动跑_暗卫20任务200调用_巡按10任务100调用`() {
        assertEquals(TriggerReason.LOAD, decide(MaintJob.NIGHTWATCH, load = Load(20, 0)).trigger)
        assertEquals(TriggerReason.LOAD, decide(MaintJob.NIGHTWATCH, load = Load(0, 200)).trigger)
        assertEquals(TriggerReason.LOAD, decide(MaintJob.PATROL, load = Load(10, 0)).trigger)
        assertEquals(TriggerReason.LOAD, decide(MaintJob.PATROL, load = Load(0, 100)).trigger)
        // 差一条不跑（边界：19/199 与 9/99 都不够）
        assertFalse(decide(MaintJob.NIGHTWATCH, load = Load(19, 199)).run)
        assertFalse(decide(MaintJob.PATROL, load = Load(9, 99)).run)
        // 负载优先：够数的报"负载"，不报"下限"
        assertEquals(TriggerReason.LOAD, decide(MaintJob.PATROL, AskSource.SCHEDULED, load = Load(10, 0)).trigger)
    }

    // ── 判据③：机会条件不满足 ⇒ 不跑，等下一次机会 ────────────────────────

    @Test
    fun `判据3_六条机会条件各拦一次_边界在等号上`() {
        val heavyLoad = Load(50, 0)
        // 非值守
        val duty = decide(load = heavyLoad, o = opp(onDuty = true))
        assertFalse(duty.run)
        assertTrue("要说清是值守：${duty.why}", duty.why.contains("值守"))
        // 电量／充电：479 起 20 就够，19 不够；没电但插着电也算
        assertFalse(decide(load = heavyLoad, o = opp(battery = 19)).run)
        assertTrue("等于 20 就够", decide(load = heavyLoad, o = opp(battery = 20)).run)
        assertTrue("充电中不看电量", decide(load = heavyLoad, o = opp(battery = 10, charging = true)).run)
        assertFalse("电量读不出又不充电 ⇒ fail-closed", decide(load = heavyLoad, o = opp(battery = -1)).run)
        // 最近 30 分钟无自动动作：29 分不够，30 分够
        assertFalse(decide(load = heavyLoad, o = opp(quietMin = 29)).run)
        assertTrue("满 30 分钟就放行", decide(load = heavyLoad, o = opp(quietMin = 30)).run)
        // 没在跑重活
        assertFalse(decide(load = heavyLoad, o = opp(heavy = true)).run)
        // 479 新增两条：不发烫、前台不在会话
        val hotGate = decide(load = heavyLoad, o = opp(hot = true))
        assertFalse(hotGate.run)
        assertTrue("要说清是发烫：${hotGate.why}", hotGate.why.contains("发烫"))
        val talkGate = decide(load = heavyLoad, o = opp(talking = true))
        assertFalse(talkGate.run)
        assertTrue("要说清是前台会话：${talkGate.why}", talkGate.why.contains("会话"))
        // 条件齐 + 负载够 ⇒ 跑
        assertTrue(decide(load = heavyLoad).run)
    }

    // ── 判据④：6h 冷却（手动不受限；两件互相独立）─────────────────────────

    @Test
    fun `判据4_6h冷却拦住重复触发_手动不受限_两件各管各的`() {
        val justNow = now - hour
        val cooled = decide(MaintJob.NIGHTWATCH, last = justNow, load = Load(99, 0))
        assertFalse(cooled.run)
        assertTrue("要说清冷却：${cooled.why}", cooled.why.contains("冷却中"))
        assertFalse(decide(MaintJob.PATROL, last = justNow, load = Load(99, 0)).run)
        // 6h 边界：满 6h 放行，差 1ms 不行
        assertTrue(decide(MaintJob.NIGHTWATCH, last = now - 6 * hour, load = Load(20, 0)).run)
        assertFalse(decide(MaintJob.NIGHTWATCH, last = now - 6 * hour + 1, load = Load(20, 0)).run)
        // 手动不受冷却限制（人可以当场再点一次）
        assertTrue(decide(MaintJob.PATROL, AskSource.MANUAL, last = justNow).run)
        // 两件冷却互相独立：巡按刚跑过，暗卫该跑照样跑
        assertEquals(TriggerReason.LOAD, decide(MaintJob.NIGHTWATCH, last = null, load = Load(20, 0)).trigger)
    }

    // ── 判据⑤：7 天无负载也跑一次（假时钟）────────────────────────────────

    @Test
    fun `判据5_7天无负载_周期作业那趟各跑一次`() {
        // 事件那条只管负载：到下限也不在收工时硬塞一趟（留给周期作业）
        assertFalse(decide(MaintJob.NIGHTWATCH, AskSource.EVENT, last = now - 8 * day).run)
        // 周期作业那条：差 1ms 不到、满了就到
        assertFalse(decide(MaintJob.PATROL, AskSource.SCHEDULED, last = now - 7 * day + 1).run)
        assertEquals(TriggerReason.FLOOR, decide(MaintJob.PATROL, AskSource.SCHEDULED, last = now - 7 * day).trigger)
        assertEquals(TriggerReason.FLOOR, decide(MaintJob.NIGHTWATCH, AskSource.SCHEDULED, last = now - 8 * day).trigger)
        // 从没跑过 ⇒ 首趟也不欠着
        assertEquals(TriggerReason.FLOOR, decide(MaintJob.NIGHTWATCH, AskSource.SCHEDULED, last = null).trigger)
    }

    // ── 判据⑦：同一时刻只允许一件后台重活 ─────────────────────────────────

    @Test
    fun `判据7_同一时刻只许一件重活_锁与人话两条都在`() {
        // 锁：抢到一件，第二件就抢不到；放锁只认放锁的人
        assertTrue(HeavyWork.tryEnter(MaintJob.PATROL.wire))
        assertFalse("第二件重活抢不到", HeavyWork.tryEnter(MaintJob.NIGHTWATCH.wire))
        HeavyWork.exit(MaintJob.NIGHTWATCH.wire)
        assertEquals("别人的锁不许被放掉", MaintJob.PATROL.wire, HeavyWork.heldBy())
        HeavyWork.exit(MaintJob.PATROL.wire)
        assertNull("自己放得掉", HeavyWork.heldBy())
        assertTrue("放干净了就能再抢", HeavyWork.tryEnter("health"))
        HeavyWork.exit("health")
        // 判据：有重活在跑就不跑（连手动也让开——两件重活叠起来最费电）
        assertFalse(decide(load = Load(50, 0), o = opp(heavy = true)).run)
        assertFalse(decide(source = AskSource.MANUAL, o = opp(heavy = true)).run)
    }

    // ── 落账那几格 ─────────────────────────────────────────────────────────

    @Test
    fun `落账第一格是谁触发_三条都认得出来`() {
        assertEquals("manual", TriggerReason.MANUAL.wire)
        assertEquals("load", TriggerReason.LOAD.wire)
        assertEquals("floor", TriggerReason.FLOOR.wire)
        assertNotNull(decide(source = AskSource.MANUAL).trigger)
        assertNull("不跑就没有触发来源", decide(load = Load(50, 0), o = opp(hot = true)).trigger)
    }

    @Test
    fun `落账第二格是机会读数_四格都在一行里`() {
        val line = Opportunistic.readout(opp(battery = 62, quietMin = 41))
        assertTrue(line, line.contains("非值守"))
        assertTrue(line, line.contains("电量 62%"))
        assertTrue(line, line.contains("未充电"))
        assertTrue(line, line.contains("静默 41 分钟"))
        assertTrue(line, line.contains("无重活"))
        // 479：发烫／前台会话两格也进读数（"这趟为什么没跑"要能照着复现）
        assertTrue(line, line.contains("温度正常"))
        assertTrue(line, line.contains("无会话"))
        val hotLine = Opportunistic.readout(opp(hot = true, talking = true))
        assertTrue(hotLine, hotLine.contains("发烫"))
        assertTrue(hotLine, hotLine.contains("前台会话中"))
        // 没有读数也不许空着（读不出就照实说）
        val blind = Opportunistic.readout(opp(battery = -1, quietMin = Long.MAX_VALUE, onDuty = true, heavy = true))
        assertTrue(blind, blind.contains("电量读不出"))
        assertTrue(blind, blind.contains("值守中"))
        assertTrue(blind, blind.contains("从没自动跑过"))
        assertTrue(blind, blind.contains("有重活在跑"))
    }

    @Test
    fun `两件的阈值与冷却各自独立_同一条规则不写两遍`() {
        assertEquals(20, MaintJob.NIGHTWATCH.loadTasks)
        assertEquals(200, MaintJob.NIGHTWATCH.loadCalls)
        assertEquals(10, MaintJob.PATROL.loadTasks)
        assertEquals(100, MaintJob.PATROL.loadCalls)
        assertEquals(6L * hour, MaintJob.NIGHTWATCH.cooldownMs)
        assertEquals(6L * hour, MaintJob.PATROL.cooldownMs)
        assertEquals("周期作业看账的节拍只许有一个数", Opportunistic.CHECK_PERIOD_HOURS, NocturnalAuto.PERIOD_HOURS)
        assertEquals(Opportunistic.CHECK_PERIOD_HOURS, PatrolAuto.PERIOD_HOURS)
    }
}
