// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百一十四批 · 六体读数接线的单测（JVM 直测；喂入缝与器官缝全注入，零 Android）。
//
// 判据对照（派工单）：
//   ① 六件各 ≥1 生产调用点——生产侧的四个调用点（salience/relay/readPlan/key）在
//      `emitAll` 的缺省器官缝里逐字存在；rpe/snapshot 两件既有生产点（521）不归本件；
//   ② 每次调用产出一行 BioLog——器官自 log，本测钉"同输入两轮 stdout 逐字节相等"（判据⑥）；
//   ③ 异常夹具：喂入缝或器官缝注入抛异常 ⇒ 那一件按"没有它"跳过、其余件照常、
//      无异常外漏（emitAll 正常返回）；
//   ④ 禁词断言：接线两文件剥注释后零判定词/零账写/零登记标记（判别器先喂违规样本自证咬得住）；
//   ⑤ G-708-2：键定义全仓只此一处（bio/Hippocampus.EpisodeKey，seq Int），678 自造键已撤；
//   ⑥ 收工壳：TriggerWiring 的 safe("六体读数") 在场——件炸了不许带崩收工链。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.bio.DangerWords
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BioReadoutWiringTest {

    // ── 夹具：喂入与器官缝全注入，不碰生产库 ──────────────────────────────────

    private fun feedsOf(
        sid: Long = 9L,
        message: BioUserLine? = null,
        plan: List<PlanRules.PlanItem> = emptyList(),
        failures: List<Int?> = emptyList(),
        words: DangerWords = DangerWords.DEFAULT,
    ) = BioFeeds(
        sid = sid,
        latestUserMessage = { message },
        planItems = { plan },
        recentFailures = { failures },
        words = { words },
    )

    private fun item(status: String) = PlanRules.PlanItem(text = "步骤", status = status)

    private fun boomCalls(vararg broken: String) = BioOrganCalls(
        salience = { e, w -> if ("杏仁核" in broken) error("夹具炸") else com.example.zhengdao.baopu.bio.Amygdala.salience(e, w) },
        relay = { e, w -> if ("丘脑" in broken) error("夹具炸") else com.example.zhengdao.baopu.bio.Thalamus.relay(e, w) },
        key = { s, q, c -> if ("海马" in broken) error("夹具炸") else com.example.zhengdao.baopu.bio.Hippocampus.key(s, q, c) },
        readPlan = { v -> if ("前额叶" in broken) error("夹具炸") else com.example.zhengdao.baopu.bio.PrefrontalCortex.readPlan(v) },
    )

    /** 捕获 stdout（BioLog 的唯一落点）后还原；返回（块返回值, 捕获文本）。 */
    private fun <T> captureStdout(block: () -> T): Pair<T, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        val printStream = PrintStream(buffer, true, Charsets.UTF_8.name())
        System.setOut(printStream)
        try {
            val result = block()
            printStream.flush()
            return result to buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(original)
        }
    }

    /** 全量输入：一条用户消息＋三步计划（1 done）＋三条失败。 */
    private fun fullFeeds() = feedsOf(
        message = BioUserLine(seq = 3, text = "帮我删掉这个测试文件！"),
        plan = listOf(item(PlanRules.STATUS_TODO), item(PlanRules.STATUS_DONE), item(PlanRules.STATUS_DOING)),
        failures = listOf(null, null, null),
    )

    // ── 判据⑥：同输入两次读数逐字一致（BioLog 无时间戳＋器官无状态＋喂入确定） ──

    @Test
    fun 判据6_同输入两次读数_stdout逐字节一致_四行齐() {
        val feeds = fullFeeds()
        val (first, out1) = captureStdout { BioReadoutWiring.emitAll(feeds, BioOrganCalls()) }
        val (_, out2) = captureStdout { BioReadoutWiring.emitAll(feeds, BioOrganCalls()) }
        assertEquals(4, first)
        assertEquals("同输入两轮 stdout 必须逐字节一致（判据⑥）", out1, out2)
        // 四行各自在场（前缀钉住喂入语义：seq/session/分档/stepsLeft 都来自注入的输入）。
        assertTrue(out1.contains("[海马] seq=3 session=9 band=短"))
        assertTrue(out1.contains("[杏仁核] threat=1 novelty=1"))
        assertTrue(out1.contains("[丘脑] route=GUARD novelty=1"))
        assertTrue(out1.contains("[前额叶] stepsLeft=2 stuckAt=null failures=3"))
    }

    // ── 三件套①：接线在场＝读数行出现；没有输入＝零行零变化 ──────────────────

    @Test
    fun 三件套1_有输入就有读数行_无输入零行零变化() {
        val (_, outFull) = captureStdout { BioReadoutWiring.emitAll(fullFeeds(), BioOrganCalls()) }
        assertTrue("接线在场 ⇒ 四行 BioLog（没它这四行都不存在）", outFull.lines().count { it.isNotBlank() } == 4)
        val (emptyCount, outEmpty) = captureStdout {
            BioReadoutWiring.emitAll(feedsOf(), BioOrganCalls())
        }
        assertEquals(0, emptyCount)
        assertEquals("无输入＝零行零变化（主链路更是一字节没动）", "", outEmpty)
    }

    @Test
    fun 三件套1_只有计划没有用户消息_只出前额叶一行() {
        val feeds = feedsOf(plan = listOf(item(PlanRules.STATUS_TODO), item(PlanRules.STATUS_DONE)))
        val (count, out) = captureStdout { BioReadoutWiring.emitAll(feeds, BioOrganCalls()) }
        assertEquals(1, count)
        assertTrue(out.contains("[前额叶] stepsLeft=1 stuckAt=null failures=0"))
        assertTrue("没有本轮消息 ⇒ 海马/杏仁核/丘脑三行不出（合法兜底）", !out.contains("[海马]"))
    }

    // ── 判据③：喂入缝注入抛异常 ⇒ 那一件按"没有它"走，其余件照常 ─────────────

    @Test
    fun 判据3_消息喂入炸_输入三件整路跳过_前额叶照常() {
        val feeds = BioFeeds(
            sid = 9L,
            latestUserMessage = { error("夹具炸") },
            planItems = { listOf(item(PlanRules.STATUS_TODO)) },
            recentFailures = { emptyList() },
            words = { DangerWords.DEFAULT },
        )
        val (count, out) = captureStdout { BioReadoutWiring.emitAll(feeds, BioOrganCalls()) }
        assertEquals(1, count)
        assertTrue(out.contains("[前额叶]"))
        assertTrue(!out.contains("[海马]"))
        assertTrue(!out.contains("[杏仁核]"))
        assertTrue(!out.contains("[丘脑]"))
    }

    @Test
    fun 判据3_词表喂入炸_键不吃词表照发_突显中继跳过() {
        val feeds = BioFeeds(
            sid = 9L,
            latestUserMessage = { BioUserLine(seq = 1, text = "正文") },
            planItems = { listOf(item(PlanRules.STATUS_DONE)) },
            recentFailures = { emptyList() },
            words = { error("夹具炸") },
        )
        val (count, out) = captureStdout { BioReadoutWiring.emitAll(feeds, BioOrganCalls()) }
        assertEquals(2, count)
        assertTrue(out.contains("[海马]"))
        assertTrue(out.contains("[前额叶]"))
        assertTrue(!out.contains("[杏仁核]"))
        assertTrue(!out.contains("[丘脑]"))
    }

    @Test
    fun 判据3_计划喂入炸_只少前额叶一行() {
        val feeds = BioFeeds(
            sid = 9L,
            latestUserMessage = { BioUserLine(seq = 1, text = "正文") },
            planItems = { error("夹具炸") },
            recentFailures = { error("夹具炸") },
            words = { DangerWords.DEFAULT },
        )
        val (count, out) = captureStdout { BioReadoutWiring.emitAll(feeds, BioOrganCalls()) }
        assertEquals(3, count)
        assertTrue(out.contains("[海马]"))
        assertTrue(!out.contains("[前额叶]"))
    }

    // ── 判据③：器官缝注入抛异常（逐件）⇒ 主链路照常走完、同轮少一行 ──────────

    @Test
    fun 判据3_器官抛异常_逐件各断一次_其余三件照常() {
        val feeds = fullFeeds()
        for (organ in listOf("海马", "杏仁核", "丘脑", "前额叶")) {
            val (count, out) = captureStdout { BioReadoutWiring.emitAll(feeds, boomCalls(organ)) }
            assertEquals("$organ 炸 ⇒ 这轮 3 行（少一行读数，不多不少）", 3, count)
            assertTrue("$organ 炸 ⇒ 它的行不出", !out.contains("[$organ]"))
            for (other in listOf("海马", "杏仁核", "丘脑", "前额叶")) {
                if (other != organ) assertTrue("$organ 炸 ⇒ 其余件照常：[$other] 在", out.contains("[$other]"))
            }
        }
    }

    // ── 判据④：禁词断言（判别器先自证咬得住，再对真实源码断言干净） ──────────

    private val forbiddenTokens = listOf(
        "RiskDecision", "PROCEED", "Proceed", "proceed", "REJECT", "Reject", "reject",
        "execSQL", "INSERT INTO", "DELETE FROM", "UPDATE ", "LedgerStore.append", "MemoryEntry(",
        "AskTier", "AskGovernance", "TodoStore", "SupervisionDiary.append",
    )

    private fun hitsIn(code: String): List<String> = forbiddenTokens.filter { it in code }

    private fun wiringSources(): List<File> = listOf(
        "src/main/java/com/example/zhengdao/baopu/inspect/BioReadoutWiring.kt",
        "src/main/java/com/example/zhengdao/baopu/inspect/TriggerWiring.kt",
    ).map(::projectFile)

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun 判别器自证_违规样本会咬() {
        val bad = "fun x(): Int { val d = RiskDecision(); execSQL(\"DELETE FROM t\") }"
        assertEquals(listOf("RiskDecision", "execSQL", "DELETE FROM"), hitsIn(stripComments(bad)))
    }

    @Test
    fun 判据4_接线两文件剥注释后零判定词零账写零登记标记() {
        val marker = "@" + "BioAnalog"
        for (f in wiringSources()) {
            val code = stripComments(f.readText())
            assertTrue("${f.name} 剥注释后禁词 0 命中（判据④）", hitsIn(code).isEmpty())
            assertTrue("${f.name} 不许带器官登记标记（六件闭集不受接线影响）", marker !in code)
        }
    }

    @Test
    fun 判据4_喂入只读_SQL文本三路都只SELECT不写() {
        val code = stripComments(wiringSources()[0].readText())
        assertTrue("本轮用户消息＝role='user' 单行只读", code.contains("role = 'user'"))
        assertTrue("近失败＝run_log ok=0 只读", code.contains("ok = 0"))
        assertTrue("近失败限量走常量（与器官的最近 3 条窗口一致）", code.contains("RECENT_FAILURES_MAX"))
        assertTrue("全程 rawQuery 只读（不碰写口）", !code.contains("execSQL"))
    }

    // ── 判据⑤：G-708-2 键形状收口（全仓只此一处） ────────────────────────────

    private val allMainSources: List<Pair<String, String>> by lazy {
        val root = projectFile("src/main/java/com/example/zhengdao")
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .toList()
    }

    @Test
    fun G7082_键定义全仓只此一处_类型已统一() {
        val definitions = allMainSources.filter { it.second.contains("data class EpisodeKey") }
        assertEquals("键定义只许 bio 一处（G-708-2）", listOf("baopu/bio/Hippocampus.kt"), definitions.map { it.first })
        val bio = stripComments(definitions.single().second)
        assertTrue("EpisodeKey 必须带 seq: Int（类型统一；Long 并存即红）", bio.contains("EpisodeKey(val sessionId: Long, val seq: Int"))
        val localKeys = allMainSources.filter { stripComments(it.second).contains("data class HippocampusKey") }
        assertTrue("678 自造键类必须已撤（G-708-2）", localKeys.isEmpty())
    }

    // ── 判据③（收工侧）：safe 壳在场，拆壳即红 ────────────────────────────────

    @Test
    fun 判据3_收工壳六体读数挂safe_接线一行在场() {
        val code = stripComments(wiringSources()[1].readText())
        assertTrue("六体读数必须挂 safe 壳（件炸了不许带崩收工链）", code.contains("safe(\"六体读数\")"))
        assertTrue("收工链必须真调 BioReadoutWiring.onRunSettled", code.contains("BioReadoutWiring.onRunSettled"))
        // 不计 ran：读数不是"跑了一件活"，ran 口径一字不动（源码钉：safe 行不接 ran++）。
        assertTrue(
            "六体读数行不许进 ran 计数",
            !code.contains("BioReadoutWiring.onRunSettled(ctx, sessionId) } != null) ran++"),
        )
    }

    // ── 755：喂入读已记录的归因线（attributed 喂步号；其余一律 null，不推不猜） ──

    @Test
    fun 判据755_喂入归因线映射_attributed给步号_其余全null() {
        // attributed:N ⇒ N（唯一放行的单一落点）
        assertEquals(3, BioFeeds.attributedStepOf("attributed:3"))
        // ambiguous（候选≥2）/unattributed（原因码）判不出单一落点 ⇒ null（判不了就不猜）
        assertNull(BioFeeds.attributedStepOf("ambiguous:2,4"))
        assertNull(BioFeeds.attributedStepOf("unattributed:no-plan"))
        assertNull(BioFeeds.attributedStepOf("unattributed:step-not-recorded"))
        assertNull(BioFeeds.attributedStepOf("unattributed:tool-failed-early"))
        // 755 之前的历史行（NULL 列）与坏线 ⇒ null
        assertNull(BioFeeds.attributedStepOf(null))
        assertNull(BioFeeds.attributedStepOf("step:3"))
        assertNull(BioFeeds.attributedStepOf(""))
    }

    @Test
    fun 判据755_喂入缝SQL读step_ref列_不再恒null() {
        val code = stripComments(wiringSources()[0].readText())
        assertTrue("喂入必须读 step_ref 列（755 当场归因的落账处）", code.contains("SELECT step_ref FROM run_log"))
        assertTrue("旧口径『每行映射成 null』必须已撤", !code.contains("rows.add(null)"))
    }
}
