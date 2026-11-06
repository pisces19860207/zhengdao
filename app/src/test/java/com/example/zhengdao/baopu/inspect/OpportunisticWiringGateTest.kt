// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · 机会式触发的**接线门禁**（两件共用那一套）。
//
// 判据里"跑得对不对"在 `OpportunisticTest`；这里钉的是**源码层**那几条——它们运行时
// 没有可观察的下场（"没有 5 分钟以内的定时"只有扫源码才验得到），删一行也不会红：
// 1. 唯一出口：设置里只有「后台自动维护」一个开关，写口全仓只此一处；
// 2. 关总开关两件都不启动：两件装配都读同一个读口，且都不自己摸 SharedPreferences；
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

    // ── ① 唯一出口 ─────────────────────────────────────────────────────────

    @Test
    fun `设置里只有这一个开关_写口全仓只此一处`() {
        val settings = code("ui/SettingsScreen.kt")
        assertTrue("设置页要有「后台自动维护」这个开关", settings.contains("AutoMaintenance.set("))
        assertEquals(
            "写口只许一处（多处写同一个键，就会有两套「谁说了算」）",
            1,
            Regex("""AutoMaintenance\.set\(""").findAll(settings).count(),
        )
        val writers = mainFiles().filter { it.second.contains("AutoMaintenance.set(") }.map { it.first }
        assertEquals("全仓只有设置页那一个写口", listOf("ui/SettingsScreen.kt"), writers)
        // 不再暴露"每日/每周/夜间"这类小开关（511 §三）：连键名都不许出现在界面层
        listOf("nightwatch_auto", "auto_maintenance", "patrol_weekly", "nightly_auto").forEach { key ->
            assertFalse("设置页认了键名字面量 $key（该走属主读口，别在界面里认键）", settings.contains("\"$key\""))
        }
    }

    @Test
    fun `开关文案中英都有_缺一条就是半拉子`() {
        val zh = code("../../../../res/values/strings_health.xml")
        val en = code("../../../../res/values-en/strings_health.xml")
        listOf(
            "settings_auto_maintenance",
            "settings_auto_maintenance_desc",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key（切英文那一行会空着）", en.contains("name=\"$key\""))
        }
    }

    // ── ② 关总开关两件都不启动 ─────────────────────────────────────────────

    @Test
    fun `两件都读同一个读口_装配侧谁也不自己摸SP`() {
        val night = code("baopu/inspect/NocturnalAuto.kt")
        val patrol = code("baopu/inspect/PatrolAuto.kt")
        assertTrue("暗卫要读总开关", night.contains("AutoMaintenance.on(ctx)"))
        assertTrue("巡按要读总开关", patrol.contains("AutoMaintenance.on(ctx)"))
        // 装配侧只许拿"生效值"，不许自己认键／自己摸 SP（两套读法＝漂移的出生地）
        listOf(
            "baopu/inspect/NocturnalAuto.kt",
            "baopu/inspect/PatrolAuto.kt",
            "baopu/inspect/Opportunistic.kt",
            "baopu/inspect/TriggerWiring.kt",
            "baopu/inspect/HealthScheduler.kt",
        ).forEach { rel ->
            val s = code(rel)
            assertFalse("$rel 自己摸了 SharedPreferences（该走 AutoMaintenance）", s.contains("getSharedPreferences"))
            assertFalse("$rel 自己认了键名", s.contains("auto_maintenance"))
        }
        // 属主本身带缺省开与写口
        val owner = code("baopu/maintenance/AutoMaintenance.kt")
        assertTrue("缺省必须是开", owner.contains("DEFAULT_ON: Boolean = true"))
        assertTrue("写口只用 KTX 的 edit{}（lint 预算 0，不许涨）", owner.contains(".edit {"))
        assertFalse("不许出现 .edit().put…（UseKtx 会记一条）", owner.contains(".edit().put"))
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
        assertTrue("巡按要写机会读数", patrol.contains("opportunity = Opportunistic.readout("))
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
