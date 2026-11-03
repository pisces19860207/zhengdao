// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批（第二刀）· **接线门禁**（照 276 的做法）：体检器不许再变成"有实现、零调用点"。
//
// 为什么这一类只能扫源码：运行时"没被调用"与"调用了但没效果"长得一模一样，
// 而这次要钉的恰恰是"那一行还在不在"——删掉 `ZhengdaoApp` 里那行，体检就退回 NOT_WIRED，
// 没有任何测试会在运行时发现（因为没人调它，也就没有可观察的下场）。
//
// 七条（对应派工单四问）：
// 1. 生产调用点：开 App 就排月位；
// 2. 月位形制：周期作业＋仅充电＋KEEP（重复开 App 不许把周期清零）；
// 2b. 月位判据的来源：只许住在 `PatrolScheduler`（夜巡那层），体检侧只做装配；
// 3. 执行体真跑：Worker 调的是 `runDue`，不是空壳；
// 4. 投递只进队列：用 `enqueueOnly`，**不许**走 `onOpinion`（那条道会吃御案额度、判"该弹"）；
// 5. 全程不弹卡：三件里没有 Dialog／Toast／Snackbar；
// 6. 自指排除：普查里没有"体检"自己（否则它永远 NOT_WIRED，形成循环）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthWiringGateTest {

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
    fun `开 App 就排月位_删了这行体检就退回没接线`() {
        val app = code("ZhengdaoApp.kt")
        assertTrue(
            "ZhengdaoApp.onCreate 里必须排月位（这是体检器唯一的生产调用点）",
            app.contains("HealthScheduler.ensureScheduled("),
        )
    }

    @Test
    fun `设置页有手动入口`() {
        val settings = code("ui/SettingsScreen.kt")
        assertTrue("设置页要有一行『体检一次』（验收时随时能跑）", settings.contains("HealthScheduler.runNow("))
    }

    // ── ② 月位形制：周期＋仅充电＋KEEP ─────────────────────────────────────

    @Test
    fun `月位是周期作业且只在充电时跑`() {
        val s = code("baopu/inspect/HealthScheduler.kt")
        assertTrue("要用周期作业（非精确）", s.contains("PeriodicWorkRequestBuilder"))
        assertTrue("系统侧那一半：仅充电", s.contains("setRequiresCharging(true)"))
        assertTrue("重复开 App 不许把周期清零", s.contains("ExistingPeriodicWorkPolicy.KEEP"))
    }

    // ── ②b 月位判据的来源：只许住在夜巡那层 ─────────────────────────────────

    @Test
    fun `月位判据只许来自夜巡调度器_体检侧只做装配`() {
        val s = code("baopu/inspect/HealthScheduler.kt")
        assertTrue("月位要真的调到夜巡那层", s.contains("PatrolScheduler.monthlyGate("))
        assertFalse("体检侧不许自己读时区算月（月键只许有一份实现）", s.contains("ZoneId"))
        assertFalse("也不许自己写到期判定", s.contains("monthlyKey("))

        val p = code("baopu/inspect/PatrolScheduler.kt")
        assertTrue("月位判据要住在夜巡那层", p.contains("fun monthlyGate("))
        assertTrue("一键停要能拨（同夜巡 AUTO_ENABLED 的口径）", p.contains("MONTHLY_ENABLED"))
        assertTrue("周期只许有一个数", p.contains("MONTHLY_PERIOD_DAYS"))
    }

    // ── ③ 执行体真跑 ───────────────────────────────────────────────────────

    @Test
    fun `Worker 调的是真执行体`() {
        val s = code("baopu/inspect/HealthScheduler.kt")
        assertTrue("Worker 里必须调 runDue（判月位与充电）", s.contains("HealthScheduler.runDue("))
        assertTrue("Worker 要能注册进 WorkManager（类名出现在周期作业里）", s.contains("HealthWorker"))
    }

    // ── ④ 投递只进队列，不占御案额度 ───────────────────────────────────────

    @Test
    fun `投递走 enqueueOnly_不许走会弹卡的 onOpinion`() {
        val run = code("baopu/inspect/HealthRun.kt")
        assertTrue("体检单要进延后队列", run.contains("OpinionWiring.enqueueOnly("))
        assertFalse(
            "不许走 onOpinion：那条道预算内会判『该弹』并吃掉当日御案额度",
            run.contains("OpinionWiring.onOpinion("),
        )
    }

    @Test
    fun `enqueueOnly 真在接线层里_且只有一个写入路径`() {
        val w = code("baopu/agent/OpinionWiring.kt")
        assertTrue("enqueueOnly 要有实现", w.contains("fun enqueueOnly("))
        assertTrue("它必须复用 submit（不另起一套写入）", w.contains("queueOnly = true"))
        assertTrue("队列键要能认出来", w.contains("QUEUE_ONLY"))
    }

    // ── ⑤ 全程不弹卡 ───────────────────────────────────────────────────────

    @Test
    fun `体检三件都不许弹卡`() {
        val banned = listOf("AlertDialog", "Toast", "Snackbar", "Dialog(", "startActivity")
        listOf(
            "baopu/inspect/HealthRun.kt",
            "baopu/inspect/HealthScheduler.kt",
            "baopu/inspect/HealthLedger.kt",
        ).forEach { rel ->
            val text = code(rel)
            banned.forEach { word ->
                assertFalse("$rel 里出现了 $word（体检不弹卡、不起界面）", text.contains(word))
            }
        }
    }

    // ── ⑥ 自指排除 ─────────────────────────────────────────────────────────

    @Test
    fun `普查里没有体检自己`() {
        val self = OrganCensus.HEALTH_PARTS.filter { it.name.contains("体检") || it.entry.contains("HealthRun") }
        assertTrue(
            "体检不能进自己的体检单：它永远拿不到自己的产出数 ⇒ 永远 NOT_WIRED，形成自指循环",
            self.isEmpty(),
        )
    }

    // ── ⑦ 部位表是**数据**，不是判据（第三百二十六批）──────────────────────
    //
    // 315 自报：这里原来写着 `assertEquals(14, OrganCensus.PROBES.size)`。
    // 那条闸的病不在"14 这个数对不对"，而在**把条目数当成了判据**——加第 15 个部位时
    // 动的第一件事是改闸，而闸一改，读提交的人就分不清是"新增了一条"还是"掩盖了一条"。
    // 现在闸只问两件事：表非空、每项有出处。条目数交给表自己说。

    @Test
    fun `部位表非空_且每项都有出处`() {
        val parts = OrganCensus.HEALTH_PARTS
        assertTrue("部位表空了？空单读起来像「全都没问题」，那是最坏的一种谎", parts.isNotEmpty())
        assertEquals(
            "表里有项没有出处（出处＝证据行；记成「不存在」的要有 byDesign）——先补出处再交",
            emptyList<String>(),
            OrganCensus.evidenceGaps(parts),
        )
    }

    @Test
    fun `只加常量表就能加第15个部位_闸一个字不用改`() {
        // 判据①的机械证明：拿真表 + 一项合成条目再跑一遍同一条判据——它照样成立。
        // 闸里没有任何地方认识"14"这个数（下面同时钉住它也确实只多了 1）。
        val fifteenth = OrganProbe(
            name = "第十五处（判据样本）",
            entry = "SampleWiring.on",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/SampleWiring.kt",
            callSites = 1,
            sink = "样本账.jsonl",
            evidence = listOf("SampleWiring.kt:1 SampleWiring.on( 的唯一调用点"),
        )
        val widened = OrganCensus.HEALTH_PARTS + fifteenth
        assertEquals("加了第 15 项之后判据仍成立（闸不认识数量）", emptyList<String>(), OrganCensus.evidenceGaps(widened))
        assertEquals(
            "表必须真的多了这一项——否则上面那条是空转",
            OrganCensus.HEALTH_PARTS.size + 1,
            widened.size,
        )
    }

    @Test
    fun `部位表里出现无出处的项_必红`() {
        val ok = OrganProbe(
            name = "基准项",
            entry = "Base.entry",
            declaredIn = "app/src/main/java/com/example/zhengdao/baopu/Base.kt",
            callSites = 1,
            sink = "账.jsonl",
            evidence = listOf("Base.kt:1 Base.entry("),
        )
        assertEquals("基准项自己得是干净的，不然下面的负例不算数", emptyList<String>(), OrganCensus.evidenceGaps(listOf(ok)))
        assertTrue("空表必须红", OrganCensus.evidenceGaps(emptyList()).isNotEmpty())

        val bad = listOf(
            "没有证据行" to listOf(ok.copy(evidence = emptyList())),
            "证据是空白行（占位符不是出处）" to listOf(ok.copy(evidence = listOf("   "))),
            "有证据有空白行混在一起" to listOf(ok.copy(evidence = listOf("Base.kt:1 Base.entry(", ""))),
            "没有名字" to listOf(ok.copy(name = "")),
            "重名（两项互相顶掉）" to listOf(ok, ok.copy(entry = "Other.entry")),
            "说它接线了却没写 entry" to listOf(ok.copy(entry = "")),
            "记成「不存在」却说不出凭什么" to listOf(ok.copy(present = false, entry = "", byDesign = null)),
        )
        bad.forEach { (why, parts) ->
            val gaps = OrganCensus.evidenceGaps(parts)
            assertTrue("【$why】这种项必须被抓出来（现在 gaps 是空的 ⇒ 闸是假绿）", gaps.isNotEmpty())
        }
    }

    @Test
    fun `产出落点只收单一写者`() {
        // 决策记录.jsonl 有十几个写者、进化档案有反思与学习两个写者 ⇒ 数它的行数不等于这个部位的产出
        val run = code("baopu/inspect/HealthRun.kt")
        assertFalse("决策记录.jsonl 是多写者账，不许拿来当某个部位的产出数", run.contains("决策记录.jsonl"))
        assertTrue("单一写者的账要收（勘验走 SINK_FILES 的行数）", run.contains("PatrolLedger.FILE_NAME"))
        // 409：影子／不良人／金吾卫三个抽检件改按**运行记录**判（有没有跑 × 跑出了什么）——
        // 账还是那三本单一写者的账，只是不再数行数；门禁跟着钉「运行记录真被读」：
        assertTrue("影子的运行记录要被体检读", run.contains("ShadowLedger.read("))
        assertTrue("暗卫的运行记录要被体检读", run.contains("NocturnalLedger.read("))
        assertTrue("金吾卫的运行记录要被体检读", run.contains("GuardLedger.read("))
        // 且「该跑没跑」要吃 I6 的同一把尺（compare 的结构化缺口，不开第二套口径）：
        assertTrue("影子该跑没跑要吃 I6 的判据", run.contains("ShadowCheck.compare("))
    }
}
