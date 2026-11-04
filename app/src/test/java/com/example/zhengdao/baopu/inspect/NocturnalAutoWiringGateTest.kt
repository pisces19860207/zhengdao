// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十二批 · 暗卫自动档的**接线门禁**（照 291 体检接线门禁的做法）：
// 自动档不许再变成"有实现、零调用点"——409 之后暗卫挂了三个批次的原因就是运行器没接。
// "删掉 ZhengdaoApp 里那行""Worker 改成空壳""开关被写成常写口"这类事只有扫源码才验得到。
//
// 七条（对应派工单 432 §二）：
// 1. 生产调用点：开 App 就排周位；
// 2. 周位形制：周期作业＋仅充电＋不按流量计费（WiFi）＋KEEP；
// 2b. 周位判据的来源：只许住在 `PatrolScheduler`（夜巡那层），装配侧只做装配；
// 3. 执行体真跑：Worker 调 `runDue`，判据两道闸（周位＋配额）都在装配侧被真调；
// 4. 结果只进体检单＋账本：不递意见、不弹卡、**不起通知**（派工单 §二③／判据）；
// 5. 抽检不是全检：随机抽（pick）＋投回判据输入侧（scopeTo，裁定②）；
// 6. 开关只读（§二④：写侧是 UI 批的活，本批不做写口）；
// 7. 普查账实相符：不良人／调度·夜巡两条部位档跟着接线改（callSites 0→1）。
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
            "ZhengdaoApp.onCreate 里必须排暗卫周位（这是自动档唯一的生产调用点）",
            app.contains("NocturnalAuto.ensureScheduled("),
        )
    }

    // ── ② 周位形制：周期＋充电＋WiFi＋KEEP ──────────────────────────────────

    @Test
    fun `周位是周期作业且只在充电与WiFi时跑`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("要用周期作业（非精确）", s.contains("PeriodicWorkRequestBuilder"))
        assertTrue("系统侧那一半：仅充电（§二②）", s.contains("setRequiresCharging(true)"))
        assertTrue("系统侧那一半：不按流量计费＝WiFi（§二②）", s.contains("NetworkType.UNMETERED"))
        assertTrue("重复开 App 不许把周期清零", s.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertTrue("周期只许有一个数（判据层的）", s.contains("PERIOD_DAYS: Long = PatrolScheduler.WEEKLY_PERIOD_DAYS"))
    }

    // ── ②b 周位判据的来源：只许住在夜巡那层 ─────────────────────────────────

    @Test
    fun `周位判据只许来自夜巡调度器_装配侧只做装配`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("周位要真的调到夜巡那层", s.contains("PatrolScheduler.autoWeeklyGate("))
        assertTrue("充电与配额要过暗卫自己的闸（409 钉过它一字未动）", s.contains("NocturnalPatrol.gate("))
        assertFalse("装配侧不许自己读时区算周（周键只许有一份实现）", s.contains("ZoneId"))
        assertFalse("也不许自己写到期判定", s.contains("weeklyKey("))

        val p = code("baopu/inspect/PatrolScheduler.kt")
        assertTrue("周位判据要住在夜巡那层", p.contains("fun autoWeeklyGate("))
        assertTrue("缺省开关要住在判据层（拍板 A＝开）", p.contains("WEEKLY_ENABLED"))
        assertTrue("周期只许有一个数", p.contains("WEEKLY_PERIOD_DAYS"))
        assertTrue("样本下限只许有一个数（拍板补充②的 n<5）", p.contains("WEEKLY_MIN_SAMPLE"))
    }

    // ── ③ 执行体真跑 ───────────────────────────────────────────────────────

    @Test
    fun `Worker 调的是真执行体_判据两道闸都在`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("Worker 里必须调 runDue（判周位与配额）", s.contains("NocturnalAuto.runDue("))
        assertTrue("Worker 要能注册进 WorkManager（类名出现在周期作业里）", s.contains("NocturnalAutoWorker"))
        // 抽检不是全检：随机抽＋投回判据输入侧（裁定②），运行记录落自己的账
        assertTrue(s.contains("NocturnalPatrol.pick("))
        assertTrue(s.contains("NocturnalPatrol.scopeTo("))
        assertTrue(s.contains("NocturnalLedger.append("))
    }

    // ── ④ 结果只进体检单＋账本：不递意见、不弹卡、不起通知 ──────────────────

    @Test
    fun `自动档三件不许弹卡_不起通知_不递意见`() {
        val banned = listOf(
            "AlertDialog", "Toast", "Snackbar", "Dialog(", "startActivity",
            "NotificationManager", "NotificationCompat", ".notify(",
            "OpinionWiring",
        )
        listOf(
            "baopu/inspect/NocturnalAuto.kt",
            "baopu/inspect/PatrolScheduler.kt",
        ).forEach { rel ->
            val text = code(rel)
            banned.forEach { word ->
                assertFalse("$rel 里出现了 $word（结果只进体检单＋账本，432 §二③）", text.contains(word))
            }
        }
    }

    // ── ⑥ 开关只读 ─────────────────────────────────────────────────────────

    @Test
    fun `开关只读_写侧留给UI批`() {
        val s = code("baopu/inspect/NocturnalAuto.kt")
        assertTrue("读口要有（SP：${NocturnalAuto.SWITCH_FILE}）", s.contains("getSharedPreferences"))
        assertTrue("键要能认出来", s.contains(NocturnalAuto.SWITCH_KEY))
        assertFalse("本批只做读开关（§二④）：不许出现写口", s.contains("putBoolean("))
        assertFalse("本批只做读开关（§二④）：不许出现 SP 编辑器", s.contains(".edit"))
    }

    // ── ⑦ 普查账实相符 ─────────────────────────────────────────────────────

    @Test
    fun `普查跟着接线改_不良人与调度夜巡都真接上了`() {
        val night = OrganCensus.HEALTH_PARTS.first { it.name == "不良人" }
        assertEquals("运行器 432 接上，普查不许再记零调用", 1, night.callSites)
        assertEquals("NocturnalPatrol.gate", night.entry)
        val scheduler = OrganCensus.HEALTH_PARTS.first { it.name == "调度·夜巡" }
        assertEquals("周位判据 432 接上，普查不许再记零调用", 1, scheduler.callSites)
        assertEquals("PatrolScheduler.autoWeeklyGate", scheduler.entry)
    }
}
