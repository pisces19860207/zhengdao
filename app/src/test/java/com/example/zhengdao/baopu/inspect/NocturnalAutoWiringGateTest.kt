// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十二批 · 暗卫自动档的**接线门禁**；第五百一十一批按新触发规则重钉。
// 自动档不许再变成"有实现、零调用点"——409 之后暗卫挂了三个批次的原因就是运行器没接。
// "删掉 ZhengdaoApp 里那行""Worker 改成空壳""装配侧自己另写一套判据"这类事只有扫源码才验得到。
//
// 本文件只管**暗卫这一件**的接线；两件共用的那套（唯一开关、无轮询、重活互斥、落账四格、
// 收工事件）在 `OpportunisticWiringGateTest`。
//
// 六条：
// 1. 生产调用点：开 App 就排周期位；
// 2. 周期位形制：周期作业＋KEEP＋**不带充电／WiFi 约束**（511 §一② 是"电量够**或**充电中"的"或"，
//    WorkManager 约束只能表达"且"，带了就把没插电但电量够的机器永久挡在下限之外）；
// 3. 判据的来源：只许住在 `Opportunistic`（装配侧不许自己读时区、写到期判定、抄阈值）；
// 4. 执行体真跑：Worker 调 `runDue`，抽检仍是"随机抽＋投回判据输入侧"＋落自己的账；
// 5. 结果只进体检单＋账本：不递意见、不弹卡、不起通知（511 §二）；
// 6. 普查账实相符：调度那条部位档跟着判据搬家改（entry／callSites）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NocturnalAutoWiringGateTest {

    private val root = File("src/main/java/com/example/zhengdao")

    private fun code(rel: String): String {
        val f = File(root, rel)
        assertTrue("找不到 $rel（被挪走/改名了？先改这条门禁）", f.isFile)
        return f.readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    // ── ① 生产调用点 ────────────────────────────────────────────────────────

    @Test
    fun `开 App 就排周位_删了这行自动档就退回没接线`() {
        val app = code("ZhengdaoApp.kt")
        assertTrue(
            "ZhengdaoApp.onCreate 里必须排暗卫周期位（这是自动档唯一的生产排程点）",
            app.contains("NocturnalAuto.ensureScheduled("),
        )
    }

    // ── ② 周期位形制：周期＋KEEP＋不带约束 ──────────────────────────────────

    @Test
    fun `周期位是周期作业_且不拿充电约束把电量够的机器挡在外面`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("要用周期作业（非精确）", s.contains("PeriodicWorkRequestBuilder"))
        assertTrue("重复开 App 不许把周期清零", s.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertFalse(
            "511 §一② 是'电量≥30% 或充电中'：约束只能表达'且'，带了就挡死电量够的机器",
            s.contains("setRequiresCharging(true)"),
        )
        assertFalse("也不再要求 WiFi（511 §一② 没有这一条）", s.contains("NetworkType.UNMETERED"))
        assertTrue("周期只许有一个数（判据层的）", s.contains("PERIOD_HOURS: Long = Opportunistic.CHECK_PERIOD_HOURS"))
    }

    // ── ③ 判据的来源：只许住在 Opportunistic ────────────────────────────────

    @Test
    fun `触发判据只许来自Opportunistic_装配侧只做装配`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("触发要真的问到判据层", s.contains("Opportunistic.decide("))
        assertTrue("抽几条与额度窗要过暗卫自己的闸（409 钉过它一字未动）", s.contains("NocturnalPatrol.gate("))
        assertFalse("装配侧不许自己读时区算周期（日历口径只许一份）", s.contains("ZoneId"))
        assertFalse("也不许自己写到期判定", s.contains("floorDue("))

        val p = code("baopu/inspect/Opportunistic.kt")
        assertTrue("判据要住在这里", p.contains("fun decide("))
        assertTrue("三条触发都在", p.contains("MANUAL") && p.contains("LOAD") && p.contains("FLOOR"))
        assertTrue("6h 冷却只许一个数", p.contains("COOLDOWN_MS"))
        assertTrue("30 分钟静默只许一个数", p.contains("QUIET_MS"))
        assertTrue("7 天下限只许一个数", p.contains("FLOOR_MS"))
    }

    // ── ④ 执行体真跑 ───────────────────────────────────────────────────────

    @Test
    fun `Worker 调的是真执行体_抽检还是抽不是全检`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("Worker 里必须调 runDue（判据全部在那一趟里）", s.contains("NocturnalAuto.runDue("))
        assertTrue("Worker 要能注册进 WorkManager（类名出现在周期作业里）", s.contains("NocturnalAutoWorker"))
        assertTrue("抽检不是全检：随机抽", s.contains("NocturnalPatrol.pick("))
        assertTrue("抽中的要投回判据输入侧（裁定②）", s.contains("NocturnalPatrol.scopeTo("))
        assertTrue("运行记录落自己的账", s.contains("NocturnalLedger.append("))
    }

    // ── ⑤ 结果只进体检单＋账本：不递意见、不弹卡、不起通知 ──────────────────

    @Test
    fun `自动档三件不许弹卡_不起通知_不递意见`() {
        val banned = listOf(
            "AlertDialog", "Toast", "Snackbar", "Dialog(", "startActivity",
            "NotificationManager", "NotificationCompat", ".notify(",
            "OpinionWiring",
        )
        listOf(
            "baopu/inspect/NocturnalAuto.kt",
            "baopu/inspect/PatrolAuto.kt",
            "baopu/inspect/Opportunistic.kt",
            "baopu/inspect/TriggerWiring.kt",
        ).forEach { rel ->
            val text = code(rel)
            banned.forEach { word ->
                assertFalse("$rel 里出现了 $word（结果只进体检单＋账本）", text.contains(word))
            }
        }
    }

    // ── ⑥ 普查账实相符 ─────────────────────────────────────────────────────

    @Test
    fun `普查跟着判据搬家改_两件装配都算在生产调用点上`() {
        val night = OrganCensus.HEALTH_PARTS.first { it.name == "不良人" }
        assertEquals("运行器 432 接上，普查不许再记零调用", 1, night.callSites)
        assertEquals("NocturnalPatrol.gate", night.entry)
        val scheduler = OrganCensus.HEALTH_PARTS.first { it.name == "调度·夜巡" }
        assertEquals("判据搬到 Opportunistic 之后两件装配各一处调用", 2, scheduler.callSites)
        assertEquals("Opportunistic.decide", scheduler.entry)
        assertTrue(
            "出处要指向新判据（旧的那条 autoWeeklyGate 已经退役）",
            scheduler.evidence.any { it.contains("Opportunistic.decide(") },
        )
    }
}
