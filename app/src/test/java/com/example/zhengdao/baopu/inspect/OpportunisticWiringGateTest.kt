// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · 机会式触发的**接线门禁**（两件共用那一套）。
//
// 判据里"跑得对不对"在 `OpportunisticTest`／`MaintBrakeTest`；这里钉的是**源码层**那几条——
// 它们运行时没有可观察的下场（"没有 5 分钟以内的定时"只有扫源码才验得到），删一行也不会红：
// 1. 总开关已删（479）：设置页没有那一行、属主没有 SP 读写、两条文案中英都退役；
// 2. 四器都进同一套两级刹车（急停／单件暂停），单件暂停只住既有 SP、不进设置页；
// 3. 判据⑥：没有 5 分钟以内的定时、没有常驻轮询（定时器／Handler 循环／精确闹钟）；
// 4. 判据⑦：两件都抢同一把重活锁，且成对放；
// 5. 判据⑤：落账四格（谁触发·机会读数·跑了什么·结论一行）都写进各自的账；
// 6. 事件驱动只有一个生产调用点（收工那一行），且在 IO 上跑；
// 7. 手动零回归：「巡一遍」那颗照旧走 manualGate；
// 8. 巡按自动档接上了，但六项判据一个字没改。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunisticWiringGateTest {

    private val root = File("src/main/java/com/example/zhengdao")

    private fun code(rel: String): String {
        val f = File(root, rel)
        assertTrue("找不到 $rel（被挪走/改名了？先改这条门禁）", f.isFile)
        return f.readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    private fun mainFiles(): List<Pair<String, String>> = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .map { it.relativeTo(root).path.replace('\\', '/') to it.readText(Charsets.UTF_8) }
        .toList()

    // ── ① 总开关已删（479）─────────────────────────────────────────────────

    @Test
    fun `判据①设置页不再有后台自动维护那个开关_入口净增不为正`() {
        val settings = code("ui/SettingsScreen.kt")
        assertFalse("设置页还在引用 AutoMaintenance（那一行没删干净）", settings.contains("AutoMaintenance"))
        assertFalse("设置页还在认那个开关的文案键", settings.contains("settings_auto_maintenance"))
        // 入口数：基线（511）设置页有**一个** Switch（后台自动维护那一行）；479 删掉它、不加新的。
        assertEquals(
            "设置页的开关数必须是 0（基线 1，本次净删 1、不新增任何一行）",
            0,
            Regex("""(?<![A-Za-z])Switch\(""").findAll(settings).count(),
        )
        assertEquals(
            "写口随开关一起删了（全仓零处）",
            emptyList<String>(),
            mainFiles().filter { it.second.contains("AutoMaintenance.set(") }.map { it.first },
        )
    }

    @Test
    fun `判据①属主没有SP读写_只剩机器读数`() {
        val owner = code("baopu/maintenance/AutoMaintenance.kt")
        listOf("getSharedPreferences", "putBoolean", "getBoolean", "putLong", "edit {", "DEFAULT_ON", "LEGACY_KEY")
            .forEach { assertFalse("属主里还有 $it：总开关没删干净", owner.contains(it)) }
        // 读数口一个都不许跟着丢（删的是开关，不是读数）
        listOf("fun charging(", "fun batteryPct(", "fun hot(", "fun talking(", "fun readings(")
            .forEach { assertTrue("读数口 $it 丢了", owner.contains(it)) }
        // 判据②那两条新读数要有出处（不是常量糊出来的）
        assertTrue("发烫要问系统热档", owner.contains("currentThermalStatus"))
        assertTrue("会话要看引擎状态", owner.contains("engineIfBuilt()"))
    }

    @Test
    fun `判据①那两条文案随开关一起退役_中英都删干净`() {
        listOf(
            "../../../../res/values/strings_health.xml",
            "../../../../res/values-en/strings_health.xml",
        ).forEach { rel ->
            assertFalse("$rel 还留着 settings_auto_maintenance", code(rel).contains("settings_auto_maintenance"))
        }
    }

    // ── ② 四器都进同一套刹车（判据③④）──────────────────────────────────────

    @Test
    fun `判据③四器都进同一套两级刹车_急停与单件暂停各一口`() {
        listOf(
            "baopu/inspect/NocturnalAuto.kt",
            "baopu/inspect/PatrolAuto.kt",
            "baopu/inspect/HealthScheduler.kt",
        ).forEach { rel ->
            val s = code(rel)
            assertTrue("$rel 没过刹车", s.contains("MaintBrake.blocked("))
            assertTrue("$rel 没读停机态", s.contains("HaltGate.halted()"))
            assertTrue("$rel 没读单件暂停", s.contains("MaintPause.untilMs(ctx, DEVICE)"))
            assertEquals("$rel 只许问一次刹车（多处问＝多套顺序）", 1, Regex("""MaintBrake\.blocked\(""").findAll(s).count())
        }
        // 金吾卫与「巡一遍」共用同一颗手动闸：停机期间人点的也不启动（471 的语义）
        val scheduler = code("baopu/inspect/PatrolScheduler.kt")
        assertTrue("手动闸要能收到停机态", scheduler.contains("halted: Boolean = false"))
        assertTrue("手动闸停机时说同一句话", scheduler.contains("MaintBrake.WHY_HALT"))
        val screen = code("ui/baopu/DecisionCenterScreen.kt")
        assertEquals(
            "两颗手动按钮（巡一遍／金吾卫）都要把停机态递进去",
            2,
            Regex("""halted = HaltGate\.halted\(\)""").findAll(screen).count(),
        )
    }

    @Test
    fun `判据④单件暂停只住既有SP_不新表_也不进设置页`() {
        val pause = code("baopu/maintenance/MaintPause.kt")
        assertTrue("SP 复用既有文件", pause.contains("\"zhengdao-inspect\""))
        assertEquals("只碰一本 SP（不新表）", 1, Regex("""getSharedPreferences\(""").findAll(pause).count())
        assertFalse("键拼法只许在 keyOf 里拼一次", pause.contains("\"pause_until_nightwatch\""))
        assertTrue("默认 24h", pause.contains("DEFAULT_HOURS: Long = 24L"))
        // 设置页没有"暂停这个器"这类新行（判据⑤：入口净增 ≤0）
        assertFalse("单件暂停不进设置页", code("ui/SettingsScreen.kt").contains("pause_until_"))
    }

    // ── ③ 判据⑥：没有 5 分钟以内的定时、没有常驻轮询 ────────────────────────

    @Test
    fun `判据6_没有5分钟以内的定时_也没有常驻轮询`() {
        assertTrue(
            "看账节拍不许小于 5 分钟（现在是 ${Opportunistic.CHECK_PERIOD_HOURS} 小时）",
            Opportunistic.CHECK_PERIOD_HOURS * 60 >= 5,
        )
        listOf(
            "baopu/inspect/NocturnalAuto.kt",
            "baopu/inspect/PatrolAuto.kt",
            "baopu/inspect/Opportunistic.kt",
            "baopu/inspect/TriggerWiring.kt",
            "baopu/maintenance/AutoMaintenance.kt",
        ).forEach { rel ->
            val s = code(rel)
            listOf(
                "TimeUnit.MINUTES", "TimeUnit.SECONDS", "postDelayed", "scheduleAtFixedRate",
                "Timer(", "setExact", "setRepeating", "while (true)", "while(true)",
            ).forEach { word ->
                assertFalse("$rel 出现了 $word：那是轮询/微定时（判据⑥不许）", s.contains(word))
            }
        }
        // 两件都走"小时"级的周期作业
        assertTrue(code("baopu/inspect/NocturnalAuto.kt").contains("TimeUnit.HOURS"))
        assertTrue(code("baopu/inspect/PatrolAuto.kt").contains("TimeUnit.HOURS"))
        // 432 的旧周期位要撤（名字换了不撤 ⇒ 老机器上两条周期位并存）
        assertTrue(
            "排新位时要撤掉旧作业名",
            code("baopu/inspect/NocturnalAuto.kt").contains("cancelUniqueWork(LEGACY_WORK_NAME)"),
        )
    }

    // ── ④ 判据⑦：重活互斥 ─────────────────────────────────────────────────

    @Test
    fun `判据7_两件都抢同一把锁_且成对放`() {
        listOf("baopu/inspect/NocturnalAuto.kt", "baopu/inspect/PatrolAuto.kt").forEach { rel ->
            val s = code(rel)
            assertTrue("$rel 要先抢锁", s.contains("HeavyWork.tryEnter("))
            assertEquals("$rel 抢锁一次", 1, Regex("""HeavyWork\.tryEnter\(""").findAll(s).count())
            assertEquals("$rel 放锁一次（finally 里放，抛了也要放）", 1, Regex("""HeavyWork\.exit\(""").findAll(s).count())
            assertTrue("$rel 放锁要在 finally 里", s.contains("} finally {"))
        }
        assertTrue("体检也进同一把锁", code("baopu/inspect/HealthScheduler.kt").contains("HeavyWork.tryEnter("))
    }

    // ── ⑤ 落账四格 ────────────────────────────────────────────────────────

    @Test
    fun `判据5_两本账都写触发来源与机会读数与结论`() {
        val night = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("暗卫要写谁触发", night.contains("trigger = trigger.wire"))
        assertTrue("暗卫要写机会读数", night.contains("opportunity = readout"))
        assertTrue("暗卫要写结论一行", night.contains("conclusion = conclusionOf("))
        val patrol = code("baopu/inspect/PatrolAuto.kt")
        assertTrue("巡按要写谁触发", patrol.contains("trigger = trigger.wire"))
        assertTrue("巡按要写机会读数", patrol.contains("opportunity = readout"))
        // 账本结构（读侧要认得，否则写了也白写）
        val nightLedger = code("baopu/inspect/NocturnalPatrol.kt")
        assertTrue(nightLedger.contains("\"trigger\""))
        assertTrue(nightLedger.contains("\"conclusion\""))
        val patrolLedger = code("baopu/inspect/PatrolLedger.kt")
        assertTrue(patrolLedger.contains("\"trigger\""))
        assertTrue(patrolLedger.contains("\"opp\""))
    }

    // ── ⑥ 事件驱动只有一个生产调用点 ───────────────────────────────────────

    @Test
    fun `事件驱动只有一个生产调用点_在收工那一行_且在IO上跑`() {
        val callers = mainFiles().filter { it.second.contains("TriggerWiring.onRunSettled(") }.map { it.first }
        assertEquals("收工事件只许一个生产调用点", listOf("ui/baopu/BaopuChatController.kt"), callers)
        val controller = code("ui/baopu/BaopuChatController.kt")
        assertTrue("巡按是重活：要放到 IO 上，别卡主线程", controller.contains("Dispatchers.IO"))
        assertTrue("两件都由 TriggerWiring 串起来问一遍", code("baopu/inspect/TriggerWiring.kt").contains("NocturnalAuto.runDue("))
        assertTrue(code("baopu/inspect/TriggerWiring.kt").contains("PatrolAuto.runDue("))
    }

    // ── ⑦ 手动零回归 ───────────────────────────────────────────────────────

    @Test
    fun `手动零回归_巡一遍那颗照旧走manualGate`() {
        val screen = code("ui/baopu/DecisionCenterScreen.kt")
        assertTrue("「巡一遍」仍要先过手动闸", screen.contains("PatrolScheduler.manualGate("))
        assertTrue("手动那条仍是 manual = true", screen.contains("PatrolRunner.run(patrolCtx, manual = true)"))
        // 手动闸本身没被改松也没被改紧
        val scheduler = code("baopu/inspect/PatrolScheduler.kt")
        assertTrue(scheduler.contains("fun manualGate("))
    }

    // ── ⑧ 巡按自动档接上了，六项判据一个字没改 ─────────────────────────────

    @Test
    fun `巡按自动档接上了_但六项判据一个字没改`() {
        assertTrue(
            "ZhengdaoApp 要排巡按周期位（删了这行，巡按就退回只能手动）",
            code("ZhengdaoApp.kt").contains("PatrolAuto.ensureScheduled("),
        )
        val patrol = code("baopu/inspect/PatrolAuto.kt")
        assertTrue("跑的是既有那一条巡按", patrol.contains("PatrolRunner.run("))
        // 六项判据与三行报告都住在 PatrolRunner，装配侧一个都不许碰（511 边界：只改"何时跑"）
        listOf(
            "AuditGapCheck", "NumberGroundingCheck", "ContractHollowCheck",
            "MediatorBiasCheck", "UnarchivedFailureCheck", "ShadowCheck",
        ).forEach { check ->
            assertFalse("$check 跑到装配层来了（六项判据只许住 PatrolRunner）", patrol.contains(check))
        }
        assertTrue("结论一行走报告自带的 headline", code("baopu/inspect/PatrolRunner.kt").contains("PatrolLedger.entryOf("))
    }
}
