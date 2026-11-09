// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十九批（2026-11-09）· 治理中心第 4 块「今天干过什么」的**判据**（①–⑦）。
//
// 判据一半在纯函数上（①行数 / ④四列与倒序 / ②清空），一半在源码上（③没有动作口 /
// ⑤没新增工具 / ⑥页面不自己算 / ⑦入口净增零）——Compose 树在 JVM 直测不了，这是本仓
// 既有门禁的同一取舍（照 `GovernanceCenterGateTest` 的三条原则：去注释再扫、锚点稳定、
// 禁词按业务口列）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.SelfRulingLedger
import com.example.zhengdao.baopu.evolution.ProposalStatus
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActivityBoardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 固定时区与固定"今天"：判据②③④要的是**可复现**，不是"运行那天恰好"。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val now: Long = LocalDate.of(2026, 11, 9).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val from: Long = ActivityBoardRules.fromMs(now, 0L, zone)

    private val dayStart: Long = LocalDate.of(2026, 11, 9).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        // 自决账是进程级单例：内存那份会跨用例漏（同 208／277 的既有教训）
        SelfRulingLedger.resetForTest()
    }

    @After
    fun tearDown() {
        // 进程级单例谁装谁还（DecisionLedger 的既有纪律）
        DecisionLedger.uninstallForTest()
        SelfRulingLedger.resetForTest()
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private fun cand(atMs: Long, who: ActivityWho, what: String, status: String) =
        ActivityCandidate(atMs, who, what, status)

    private fun source(suffix: String): String {
        val candidates = listOf(
            "src/main/java/com/example/zhengdao/$suffix",
            "../app/src/main/java/com/example/zhengdao/$suffix",
            "app/src/main/java/com/example/zhengdao/$suffix",
        )
        val f = candidates.map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("找不到 $suffix（工作目录 ${File(".").absolutePath}）")
        return f.readText(Charsets.UTF_8)
    }

    /** 去行注释与块注释后的代码（结构性断言用；注释里提到禁词不算违规）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val screen by lazy { codeOnly(source("ui/baopu/GovernanceCenterScreen.kt")) }
    private val drawer by lazy { codeOnly(source("ui/baopu/BaopuDrawer.kt")) }
    private val host by lazy { codeOnly(source("ui/baopu/BaopuScreen.kt")) }
    private val rules by lazy { codeOnly(source("ui/baopu/ActivityBoardRules.kt")) }
    private val boardSource by lazy { codeOnly(source("ui/baopu/ActivityBoardSource.kt")) }
    private val store by lazy { codeOnly(source("ui/baopu/ActivityBoardStore.kt")) }

    // ── 判据①：3 已执行 ＋ 1 被压 ＋ 1 计划未执行 ⇒ 只显示 3 行 ──────────────

    @Test
    fun `判据①_三已执行_一被压_一计划未执行_只显示三行`() {
        val rows = ActivityBoardRules.rows(
            candidates = listOf(
                cand(now - 3 * MINUTE, ActivityWho.MODEL, "read_file", ActivityBoardRules.WIRE_ACTED),
                cand(now - 2 * MINUTE, ActivityWho.MEDIATOR, "劝了一句", ActivityBoardRules.WIRE_SKIPPED),
                cand(now - 1 * MINUTE, ActivityWho.EVOLVER, "gap:x", ActivityBoardRules.WIRE_ROLLED_BACK),
                // 被压：卡没摆出来，什么都没发生 ⇒ 不上屏
                cand(now - 4 * MINUTE, ActivityWho.MODEL, "write_file", ActivityBoardRules.WIRE_SUPPRESSED),
                // 计划未执行：等确认 / 提案没走到验证 ⇒ 不上屏
                cand(now - 5 * MINUTE, ActivityWho.REPAIRER, "index a.md", ActivityBoardRules.WIRE_PLANNED),
            ),
            nowMs = now,
            clearedAtMs = 0L,
            zone = zone,
        )
        assertEquals("三已执行一被压一未执行 ⇒ 只剩三行", 3, rows.size)
        assertEquals(
            "时间倒序：新的在上",
            listOf("gap:x", "劝了一句", "read_file"),
            rows.map { it.what },
        )
    }

    @Test
    fun `判据①_负例_被拒与认不出的线值一律不上屏`() {
        val rows = ActivityBoardRules.rows(
            candidates = listOf(
                cand(now - MINUTE, ActivityWho.EVOLVER, "p1", ActivityBoardRules.WIRE_REFUSED),
                cand(now - MINUTE, ActivityWho.MODEL, "x", "something_new_from_a_future_ledger"),
                cand(now - MINUTE, ActivityWho.MODEL, "y", ""),
            ),
            nowMs = now,
            clearedAtMs = 0L,
            zone = zone,
        )
        assertTrue("被拒 / 认不出 / 空线值都不许上屏（fail-closed）", rows.isEmpty())
    }

    // ── 判据②：清空只清投影（水位），账本行数不变 ──────────────────────────

    @Test
    fun `判据②_清空后看板空_账本行数一字不动`() {
        val root = tmp.newFolder("mem")
        val ledger = File(root, SelfRulingLedger.FILE_NAME)
        ledger.writeText(
            (1..3).joinToString("\n") { i -> selfRulingJson("sr:$i", now - i * MINUTE, "gist$i") } + "\n",
            Charsets.UTF_8,
        )
        DecisionLedger.install(root)
        val before = ledger.readText(Charsets.UTF_8)
        val beforeLines = before.lines().count { it.isNotBlank() }

        val candidates = ActivityBoardSource.selfRulingCandidates(SelfRulingLedger.replay(), from)
        assertEquals("清之前：三条自决单都在", 3, ActivityBoardRules.rows(candidates, now, 0L, zone).size)

        // 「清空」只写水位文件；账本一个字都不该动
        val appDir = tmp.newFolder("files")
        assertTrue("水位要写得上", ActivityBoardStore.clear(appDir, now))
        val cleared = ActivityBoardStore.readClearedAt(appDir)
        assertEquals("水位＝清空那一刻", now, cleared)

        assertTrue("清空后看板必须空", ActivityBoardRules.rows(candidates, now, cleared, zone).isEmpty())
        assertEquals("账本行数不变", beforeLines, ledger.readText(Charsets.UTF_8).lines().count { it.isNotBlank() })
        assertEquals("账本原文逐字节不变", before, ledger.readText(Charsets.UTF_8))
        assertEquals("水位文件之外没有第二件产物", 1, appDir.listFiles()?.size ?: 0)
    }

    @Test
    fun `判据②_水位只往前_清空之后新落账的仍然显示`() {
        val after = now + MINUTE
        val rows = ActivityBoardRules.rows(
            candidates = listOf(cand(after, ActivityWho.MODEL, "read_file", ActivityBoardRules.WIRE_ACTED)),
            nowMs = after,
            clearedAtMs = now,
            zone = zone,
        )
        assertEquals("清空只挡它之前的那一段", 1, rows.size)
        // 等于水位的那一条也算清掉（同一毫秒两条一起走：宁可多清，不许清不掉）
        assertTrue(
            ActivityBoardRules.rows(
                listOf(cand(now, ActivityWho.MODEL, "read_file", ActivityBoardRules.WIRE_ACTED)),
                now,
                now,
                zone,
            ).isEmpty(),
        )
    }

    @Test
    fun `水位文件缺失或损坏_都读成零_不抛`() {
        val dir = tmp.newFolder("files2")
        assertEquals("没有文件 ⇒ 0（没清过）", 0L, ActivityBoardStore.readClearedAt(dir))
        ActivityBoardStore.fileIn(dir).writeText("{ 这不是 json", Charsets.UTF_8)
        assertEquals("坏文件 ⇒ 0（宁可多显示，不许把账藏掉）", 0L, ActivityBoardStore.readClearedAt(dir))
    }

    // ── 判据③：没有批 / 驳 / 搁置按钮（源码断言）───────────────────────────

    @Test
    fun `判据③_看板没有批驳搁置_整块只有一个按钮`() {
        listOf("批", "驳", "搁置", "回滚").forEach { word ->
            assertFalse("本屏代码不许出现动作字「$word」", screen.contains(word))
        }
        val atSection = screen.indexOf("private fun ActivitySection")
        val atDialog = screen.indexOf("private fun ActivityClearDialog")
        assertTrue("锚点丢了（看板结构变了要回来重新钉）：$atSection", atSection >= 0)
        assertTrue("锚点丢了（清空确认框换了名字）：$atDialog", atDialog > atSection)
        val block = screen.substring(atSection, atDialog)
        assertTrue("看板块必须真在（锚点丢了要回来重新钉）", block.contains("ActivityFooter"))
        assertEquals(
            "看板上只许一个按钮（清空）；批/驳/搁置一个都不许有",
            1,
            Regex("onClick\\s*=").findAll(block).count(),
        )
        assertTrue("那个唯一的按钮必须是清空", block.contains("R.string.gc_activity_clear"))
    }

    // ── 判据④：四列齐全 ＋ 时间倒序 ─────────────────────────────────────────

    @Test
    fun `判据④_四列齐全_时间倒序_时间列是时分`() {
        val rows = ActivityBoardRules.rows(
            candidates = listOf(
                cand(dayStart + 3 * HOUR, ActivityWho.MODEL, "read_file", ActivityBoardRules.WIRE_ACTED),
                cand(now, ActivityWho.REPAIRER, "index a.md", ActivityBoardRules.WIRE_ACTED),
                cand(dayStart + 9 * HOUR + 7 * MINUTE, ActivityWho.MEDIATOR, "劝了一句", ActivityBoardRules.WIRE_SKIPPED),
            ),
            nowMs = now,
            clearedAtMs = 0L,
            zone = zone,
        )
        assertEquals(3, rows.size)
        rows.forEach { row ->
            assertTrue("时间列必须是 HH:mm（拿到：${row.clock}）", Regex("^\\d{2}:\\d{2}$").matches(row.clock))
            assertTrue("做了什么不许空", row.what.isNotBlank())
        }
        assertEquals("谁这一列三行分别是模型/御医/调解器", 3, rows.map { it.who }.distinct().size)
        assertEquals("结果这一列也要有值", 3, rows.size)
        assertTrue("必须时间倒序", rows.map { it.atMs } == rows.map { it.atMs }.sortedDescending())
        assertEquals("03:00 那条在最下面", "03:00", rows.last().clock)
    }

    @Test
    fun `判据④_做了什么压成一行_超长截断`() {
        val long = "  a\nb   c  " + "x".repeat(200)
        val flat = ActivityBoardRules.oneLine(long)
        assertFalse("不许留换行", flat.contains("\n"))
        assertEquals("截断到上限几个字（含省略号）", ActivityBoardRules.WHAT_CHARS, flat.length)
        assertTrue("截断要能看出来", flat.endsWith("…"))
    }

    @Test
    fun `判据④_最多二十行_更早的按新到旧截掉`() {
        val rows = ActivityBoardRules.rows(
            candidates = (1..30).map { i ->
                cand(dayStart + i * MINUTE, ActivityWho.MODEL, "t$i", ActivityBoardRules.WIRE_ACTED)
            },
            nowMs = now,
            clearedAtMs = 0L,
            zone = zone,
        )
        assertEquals(ActivityBoardRules.MAX_ROWS, rows.size)
        assertEquals("留下的是最新的那批", "t30", rows.first().what)
    }

    @Test
    fun `昨天的不上屏`() {
        val rows = ActivityBoardRules.rows(
            candidates = listOf(cand(now - 26 * HOUR, ActivityWho.MODEL, "read_file", ActivityBoardRules.WIRE_ACTED)),
            nowMs = now,
            clearedAtMs = 0L,
            zone = zone,
        )
        assertTrue("这是「今天干过什么」，昨天的不算", rows.isEmpty())
    }

    // ── 判据⑤：不新增工具 ──────────────────────────────────────────────────

    @Test
    fun `判据⑤_本批四个文件都没有工具注册口`() {
        listOf("ActivityBoardRules.kt" to rules, "ActivityBoardSource.kt" to boardSource, "ActivityBoardStore.kt" to store)
            .forEach { (name, text) ->
                listOf("ToolRegistry", "BaopuToolCatalog", "DEFAULT_ENABLED", "ToolSpec", "ENTRIES")
                    .forEach { b -> assertFalse("$name 不许碰工具注册（$b）——人看的看板，模型读账本即可", text.contains(b)) }
            }
        listOf("ToolRegistry", "BaopuToolCatalog", "DEFAULT_ENABLED", "ToolSpec", "ENTRIES")
            .forEach { b -> assertFalse("屏幕层同样不许碰工具注册（$b）", screen.contains(b)) }
    }

    // ── 判据⑥：数据只来自账本，页面不自己算 ────────────────────────────────

    @Test
    fun `判据⑥_屏幕不读账不筛不算_取数只在取数层`() {
        listOf("rawQuery", "SELECT ", "execSQL", "sortedBy", "filter {", "ActivityCandidate(")
            .forEach { b -> assertFalse("本屏不许自己取数/筛（$b）", screen.contains(b)) }
        assertEquals(
            "四列行只许从判据层那一个入口来",
            1,
            Regex("ActivityBoardRules\\.rows\\(").findAll(screen).count(),
        )
        assertEquals(
            "账本只许在取数层那一个入口读",
            1,
            Regex("ActivityBoardSource\\.candidates\\(").findAll(screen).count(),
        )
        assertTrue("取数层必须有带上界的 SQL", boardSource.contains("LIMIT ?"))
        assertFalse("取数层不许有写口", boardSource.contains("execSQL"))
        assertFalse("取数层不许追加账本", boardSource.contains("appendText"))
        assertFalse("水位层不许碰账本", store.contains("appendText") || store.contains("execSQL"))
    }

    // ── 判据⑦：入口净增 0 ───────────────────────────────────────────────────

    @Test
    fun `判据⑦_入口净增零_抽屉与宿主都没长新东西`() {
        assertEquals(
            "抽屉里「治理中心」仍只有一处入口",
            1,
            Regex("R\\.string\\.baopu_drawer_governance_center\\b").findAll(drawer).count(),
        )
        assertFalse("看板不许长出抽屉入口", drawer.contains("ActivityBoard"))
        assertFalse("看板不许长出第二个开屏标志", host.contains("showActivityBoard"))
        assertEquals(
            "宿主仍只挂一次治理中心",
            1,
            Regex("GovernanceCenterScreen\\(").findAll(host).count(),
        )
    }

    // ── 账本行的翻译（纯函数，逐支）────────────────────────────────────────

    @Test
    fun `执行账_出手成功与失败都上屏_等确认不上屏`() {
        val ok = ActivityBoardSource.runLogRow("read_file", 1, "Acting", now - 2 * MINUTE)
        val bad = ActivityBoardSource.runLogRow("write_file", 0, "Acting", now - MINUTE)
        val wait = ActivityBoardSource.runLogRow("delete_path", 1, "AwaitConfirm", now)
        assertEquals("成功", ActivityBoardRules.WIRE_ACTED, ok.status)
        assertEquals("失败也有结果", ActivityBoardRules.WIRE_FAILED, bad.status)
        assertEquals("等你确认＝计划未执行", ActivityBoardRules.WIRE_PLANNED, wait.status)
        val rows = ActivityBoardRules.rows(listOf(ok, bad, wait), now, 0L, zone)
        assertEquals("三行只上两行", 2, rows.size)
        assertEquals("失败那一行要留着（不看错题就是报喜不报忧）", ActivityResult.FAILED, rows.first().result)
    }

    @Test
    fun `修复账_intent不上屏_result两种都上屏`() {
        val intent = ActivityBoardSource.repairRow("index", "a.md", RepairLedger.PHASE_INTENT, "", now)
        val done = ActivityBoardSource.repairRow("index", "a.md", RepairLedger.PHASE_RESULT, RepairLedger.OUTCOME_DONE, now)
        val failed = ActivityBoardSource.repairRow("index", "a.md", RepairLedger.PHASE_RESULT, RepairLedger.OUTCOME_FAILED, now)
        assertEquals(ActivityBoardRules.WIRE_PLANNED, intent.status)
        assertEquals(ActivityBoardRules.WIRE_ACTED, done.status)
        assertEquals(ActivityBoardRules.WIRE_FAILED, failed.status)
        assertEquals("intent 那条不上屏", 2, ActivityBoardRules.rows(listOf(intent, done, failed), now, 0L, zone).size)
        assertTrue("做了什么要带上目标", done.what.contains("a.md"))
    }

    @Test
    fun `进化提案_六档全覆盖_只有落版与回滚上屏`() {
        val kept = listOf(ProposalStatus.LANDED, ProposalStatus.ROLLED_BACK)
        val dropped = listOf(
            ProposalStatus.OPEN, ProposalStatus.ADMITTED, ProposalStatus.RETURNED, ProposalStatus.REFUSED,
        )
        kept.forEach { s ->
            val c = cand(now, ActivityWho.EVOLVER, "p", ActivityBoardSource.proposalStatus(s))
            assertEquals("$s 要上屏", 1, ActivityBoardRules.rows(listOf(c), now, 0L, zone).size)
        }
        dropped.forEach { s ->
            val c = cand(now, ActivityWho.EVOLVER, "p", ActivityBoardSource.proposalStatus(s))
            assertTrue("$s 不许上屏", ActivityBoardRules.rows(listOf(c), now, 0L, zone).isEmpty())
        }
        assertEquals(
            "已回滚那一档要落到「已回滚」",
            ActivityResult.ROLLED_BACK,
            ActivityBoardRules.resultOf(ActivityBoardSource.proposalStatus(ProposalStatus.ROLLED_BACK)),
        )
    }

    // ── 源级：判据层不认识账本（同 322 的口径）──────────────────────────────

    @Test
    fun `判据层不认识任何账本`() {
        listOf("DecisionLedger", "SelfRulingLedger", "RepairLedger", "EvolutionWiring", "BaopuRuntime", "rawQuery")
            .forEach { b -> assertFalse("判据层只认归一后的候选（$b 该在取数层）", rules.contains(b)) }
    }

    private fun selfRulingJson(id: String, atMs: Long, gist: String): String = JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("at", atMs)
        .put("day", "2026-11-09")
        .put("round", "r1")
        .put("tool", "t")
        .put("gist", gist)
        .put("law", "L1")
        .put("why", "already_refused")
        .put("reason", "同类已关过三次，不打扰")
        .put("op", "op1")
        .toString()

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
