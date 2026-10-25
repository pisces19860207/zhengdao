// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十批 · 影响提示的判据（派工单 §4 的正例＋负例逐条落地）。
//
// 覆盖面：八类动作各一条读数 ＋ 300ms 降级 ＋ 原件区（复用 PathGuard）＋ 台账计数
// ＋ 空事实源不编结论。事实全部由假件喂 ⇒ 不碰真盘、不碰 db、不碰 Android。
//
// ⚠️ 路径口径：所有 key 都用 [k] 造（`File(root, rel).absolutePath`）—— 与生产代码
// `ImpactPreview.resolve` 逐字同一句。**不许手写 `"/ws/a.txt"`**：Windows 上
// `File.absolutePath` 会补盘符（`C:\ws\a.txt`），手写字面量在 Windows 上必假红。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class ImpactPreviewTest {

    /** 假工作区根（不存在的路径，只当字符串用）。 */
    private val root = "/ws"

    /** 把相对路径变成生产代码会算出来的那个绝对路径。 */
    private fun k(rel: String): String = File(root, rel).absolutePath

    // ── 假事实源（只读口的可控替身）────────────────────────────────────

    /**
     * @param stepMs 每次 [nowMs] 前进多少毫秒（0 = 时间不动 ⇒ 永远不超预算）
     * @param statDelayMs 每次 [sizeOf] 前进多少毫秒（模拟慢盘）
     */
    private class Fake(
        override val root: String? = "/ws",
        override val rawDir: String? = File("/ws", "资料库/原始").absolutePath,
        private val sizes: Map<String, Long> = emptyMap(),
        private val dirs: Set<String> = emptySet(),
        private val listings: Map<String, List<String>> = emptyMap(),
        private val ledger: Map<String, Int> = emptyMap(),
        private val form: FormState? = null,
        private val stepMs: Long = 0,
        private val statDelayMs: Long = 0,
    ) : LocalFacts {
        private var clock = 0L
        var stats = 0
            private set

        override fun nowMs(): Long {
            val t = clock
            clock += stepMs
            return t
        }

        override fun sizeOf(path: String): Long? {
            stats++
            clock += statDelayMs
            return sizes[path]
        }

        override fun isDir(path: String): Boolean = path in dirs

        override fun listNames(path: String): List<String>? = listings[path]

        override fun ledgerRowsFor(paths: List<String>): Int = paths.sumOf { ledger[it] ?: 0 }

        override fun formState(selector: String): FormState? = form
    }

    private fun call(name: String, json: String, id: String = "c1"): ToolCall =
        ToolCall(id, name, JSONObject(json))

    // ── 正例 1：写一个新文件 ────────────────────────────────────────────

    @Test
    fun `正例_写新文件_新建1且不覆盖`() {
        val facts = Fake(sizes = emptyMap())
        val i = ImpactPreview.preview(call("write_file", """{"path":"产物/新.txt","content":"x"}"""), facts)
        assertEquals(Impact.Kind.WRITE, i.kind)
        assertEquals(1, i.newFiles)
        assertEquals(0, i.overwriteTotal)
        assertTrue(i.overwrite.isEmpty())
        assertFalse("不该标原件区", i.originalsTouched)
        assertFalse(i.unknown)
        assertTrue("单次 stat 应远小于 10ms 预算（实测 ${i.elapsedMs}ms）", i.elapsedMs < 10)
    }

    // ── 正例 2：覆盖（合批 / 单项）＋ 台账 ──────────────────────────────

    @Test
    fun `正例_覆盖两个_名单去重`() {
        val facts = Fake(
            sizes = mapOf(k("a.txt") to 10L, k("b.txt") to 20L),
            ledger = mapOf(k("a.txt") to 1),
        )
        val impacts = ImpactPreview.previewAll(
            listOf(
                call("write_file", """{"path":"a.txt","content":"x","overwrite":true}""", "c1"),
                call("write_file", """{"path":"b.txt","content":"y","overwrite":true}""", "c2"),
            ),
            facts,
        )
        assertEquals(2, impacts.overwriteTotal)
        assertEquals(listOf("a.txt", "b.txt"), impacts.overwrite)
    }

    @Test
    fun `正例_覆盖单项也带台账条数`() {
        val facts = Fake(sizes = mapOf(k("a.txt") to 10L), ledger = mapOf(k("a.txt") to 1))
        val i = ImpactPreview.preview(
            call("write_file", """{"path":"a.txt","content":"x","overwrite":true}"""),
            facts,
        )
        assertEquals(1, i.overwriteTotal)
        assertEquals(listOf("a.txt"), i.overwrite)
        assertEquals("台账 1 条要进 impact（覆盖已发产物会动台账）", 1, i.ledgerRows)
    }

    @Test
    fun `负例_目标已存在又不许覆盖_报会被拒而不是新建`() {
        val facts = Fake(sizes = mapOf(k("a.txt") to 10L))
        val i = ImpactPreview.preview(call("write_file", """{"path":"a.txt","content":"x"}"""), facts)
        assertTrue("write_file 不给 overwrite 时自己会拒", i.refused)
        assertEquals(0, i.newFiles)
    }

    // ── 正例 3：删 30 个含 5 条已发（合批）────────────────────────────

    @Test
    fun `正例_删30个含5条已发_条数体积台账三样都要对`() {
        val sizes = (1..30).associate { k("f$it.txt") to (it * 100L) }
        val ledger = (1..5).associate { k("f$it.txt") to 1 }
        val facts = Fake(sizes = sizes, ledger = ledger)
        val calls = (1..30).map { call("delete_path", """{"path":"f$it.txt"}""", "c$it") }
        val i = ImpactPreview.previewAll(calls, facts)
        assertEquals(Impact.Kind.DELETE, i.kind)
        assertEquals(30, i.deleteCount)
        assertEquals((1..30).sumOf { it * 100L }, i.deleteBytes)
        assertEquals("已入台账 5 条——少算多算都是负例 5", 5, i.ledgerRows)
    }

    @Test
    fun `正例_清空回收站只数直接子文件_子目录不数`() {
        val trash = k("回收站")
        val facts = Fake(
            sizes = mapOf("$trash/1-a.txt" to 100L, "$trash/2-b.txt" to 200L),
            dirs = setOf("$trash/子目录"),
            listings = mapOf(trash to listOf("1-a.txt", "2-b.txt", "子目录")),
        )
        val i = ImpactPreview.preview(call("delete_path", """{"empty_trash":true}"""), facts)
        assertEquals(2, i.deleteCount)
        assertEquals(300L, i.deleteBytes)
    }

    @Test
    fun `负例_删目录_报会被拒而不是删1个`() {
        val facts = Fake(dirs = setOf(k("产物")))
        val i = ImpactPreview.preview(call("delete_path", """{"path":"产物"}"""), facts)
        assertTrue("工具只删单文件，目录硬拒", i.refused)
        assertEquals(0, i.deleteCount)
    }

    // ── 正例 4：移动撞名 ────────────────────────────────────────────────

    @Test
    fun `正例_移动撞名_两个数字都要对`() {
        val facts = Fake(sizes = mapOf(k("旧.txt") to 5L, k("新.txt") to 7L))
        val i = ImpactPreview.preview(call("move_path", """{"from":"旧.txt","to":"新.txt"}"""), facts)
        assertEquals(Impact.Kind.MOVE, i.kind)
        assertEquals(1, i.moved)
        assertEquals(1, i.collisions)
        assertFalse(i.refused)
    }

    @Test
    fun `正例_移动源不在_标会被拒`() {
        val facts = Fake(sizes = mapOf(k("新.txt") to 7L))
        val i = ImpactPreview.preview(call("move_path", """{"from":"没有.txt","to":"新.txt"}"""), facts)
        assertEquals(0, i.moved)
        assertTrue(i.refused)
    }

    // ── 正例 5：提交类点击（已填/必填/跨域）─────────────────────────────

    @Test
    fun `正例_点击表单_已填与必填都报`() {
        val facts = Fake(
            form = FormState(
                filled = 3, requiredEmpty = 1,
                actionHost = "a.com", currentHost = "a.com", buttonText = "提交",
            ),
        )
        val i = ImpactPreview.preview(call("browser_click", """{"target":"#submit"}"""), facts)
        assertEquals(3, i.formFilled)
        assertEquals(1, i.formRequiredEmpty)
        assertFalse(i.crossDomain)
        assertEquals("提交", i.nodeText)
        assertTrue(i.formKnown)
    }

    @Test
    fun `正例_点击跨域_主机不同即算`() {
        val facts = Fake(
            form = FormState(
                filled = 0, requiredEmpty = 0,
                actionHost = "pay.example.com", currentHost = "shop.example.com", buttonText = "去支付",
            ),
        )
        val i = ImpactPreview.preview(call("browser_click", """{"target":"#pay"}"""), facts)
        assertTrue(i.crossDomain)
    }

    @Test
    fun `负例_拿不到DOM不编已填0项`() {
        val facts = Fake(form = null)
        val i = ImpactPreview.preview(call("browser_click", """{"target":"div.wrap > button#submit"}"""), facts)
        assertFalse("没拿到表单就不能说知道", i.formKnown)
        assertEquals(0, i.formFilled)
        assertEquals("只留标签名，不带 #id/.class/[attr]", "button", i.nodeText)
    }

    @Test
    fun `判据_选择器只有id时保留id`() {
        val i = ImpactPreview.preview(call("browser_click", """{"target":"#submit"}"""), Fake(form = null))
        assertEquals("#submit", i.nodeText)
    }

    // ── 正例 6：定时任务下次触发（与 ScheduleRules 同源）────────────────

    @Test
    fun `正例_定时任务_下次触发与规则函数同源`() {
        val zone = ZoneId.systemDefault().id
        val from = 16 * 3600_000L
        val j = JSONObject("""{"freq":"daily","minute_of_day":510,"zone":"$zone","now_ms":$from}""")
        val i = ImpactPreview.preview(ToolCall("c1", "schedule_task", j), Fake())
        assertEquals(Impact.Kind.SCHEDULE, i.kind)
        assertTrue("次日 08:30（510 分）应晚于今天 16:00", i.nextFireAtMs > from)
        assertEquals(1, i.firePerDay)
        val expect = ScheduleRules.nextRunAfter(
            freq = ScheduleFreq.DAILY,
            minuteOfDay = 510,
            dayOfWeek = 0,
            onceDate = "",
            zone = zone,
            fromMs = from,
        )
        assertEquals("预览不许另算一套时间", expect, i.nextFireAtMs)
    }

    @Test
    fun `负例_定时任务参数认不出_不显示`() {
        val i = ImpactPreview.preview(call("schedule_task", """{"foo":1}"""), Fake())
        assertEquals(Impact.Kind.NONE, i.kind)
    }

    // ── 正例 7：技能回放 ───────────────────────────────────────────────

    @Test
    fun `正例_技能回放_步数区间与碰几个文件`() {
        val body = """{"steps":[
            {"action":"click","path":"a.txt"},
            {"action":"click","path":"b.txt"},
            {"action":"click","path":"a.txt"}
        ]}"""
        val i = ImpactGovernance.skillReplay(body)
        assertEquals(Impact.Kind.SKILL_REPLAY, i.kind)
        assertEquals(3, i.stepsLo)
        assertEquals(5, i.stepsHi)
        assertEquals("去重后 2 个文件", 2, i.touches)
    }

    @Test
    fun `负例_技能JSON坏掉_不报预计0步`() {
        val i = ImpactGovernance.skillReplay("不是 JSON")
        assertEquals(Impact.Kind.NONE, i.kind)
    }

    // ── 正例 8：技能自治（复用 205 的纯规则）─────────────────────────────

    @Test
    fun `正例_技能自治_退役转正保留三个数`() {
        val now = 1_000_000_000_000L
        val stale = List(3) { n ->
            DraftStat(
                name = "s$n", scope = SkillScope.PROJECT, file = File("s$n"),
                body = "", uses = 0, lastActivityMs = now - 40L * 24 * 3600_000, scenario = "",
            )
        }
        val due = listOf(
            DraftStat(
                name = "hot", scope = SkillScope.PROJECT, file = File("hot"),
                body = "", uses = 3, lastActivityMs = now, scenario = "",
            ),
        )
        val plan = SkillDraftGovernor.plan(stale + due, now)
        val i = ImpactGovernance.skillGovern(plan, statsSize = 20, recentUsed = 17)
        assertEquals(3, i.retireCount)
        assertEquals(listOf("s0", "s1", "s2"), i.retireNames)
        assertEquals(1, i.promoteCount)
        assertEquals(17, i.keepCount)
    }

    @Test
    fun `负例_技能自治无事可做_报不动而不是空`() {
        val plan = SkillDraftGovernor.plan(emptyList(), 0L)
        val i = ImpactGovernance.skillGovern(plan, statsSize = 4, recentUsed = 4)
        assertEquals(0, i.retireCount)
        assertEquals(4, i.keepCount)
    }

    // ── 正例 9：日记自治 ───────────────────────────────────────────────

    @Test
    fun `正例_日记自治_归档条数按保留期算`() {
        val now = 2_000L * 86_400_000L
        val old = entry("d1", now - 40L * 86_400_000L)
        val fresh = entry("d2", now - 2L * 86_400_000L)
        val i = ImpactGovernance.diaryMaintain(listOf(old, fresh), archived = emptyList(), nowMs = now)
        assertEquals(Impact.Kind.DIARY_MAINTAIN, i.kind)
        assertEquals(1, i.archiveCount)
        assertEquals(0, i.cleanCount)
        assertTrue("有归档就得重建索引", i.indexRebuild)
    }

    @Test
    fun `负例_日记都没到期_一个字也不说`() {
        val now = 2_000L * 86_400_000L
        val i = ImpactGovernance.diaryMaintain(listOf(entry("d2", now)), archived = emptyList(), nowMs = now)
        assertEquals(0, i.archiveCount)
        assertNull("没有可说的就不显示", ImpactLine.text(i))
    }

    private fun entry(id: String, timeTo: Long) = DiaryEntry(
        id = id,
        date = "2026-10-01",
        sessionId = 1,
        timeFromMs = timeTo - 1000,
        timeToMs = timeTo,
        title = "t",
        summary = "s",
    )

    // ── 负例 2：超预算立刻降级「后果未知」───────────────────────────────

    @Test
    fun `负例_慢盘超预算_立刻降级为未知`() {
        val trash = k("回收站")
        val facts = Fake(
            sizes = (1..200).associate { "$trash/$it.txt" to 1L },
            listings = mapOf(trash to (1..200).map { "$it.txt" }),
            statDelayMs = 120, // 每次 stat 120ms
        )
        val t0 = facts.nowMs()
        val i = ImpactPreview.preview(call("delete_path", """{"empty_trash":true}"""), facts)
        val spent = facts.nowMs() - t0
        assertTrue("超预算必须标未知", i.unknown)
        assertTrue("只该读了 2–3 个就停手（读了 ${facts.stats} 个）", facts.stats <= 4)
        assertTrue("总耗时不许随文件数线性涨（实测 ${spent}ms）", spent < 500)
    }

    // ── 负例 4：原件区不许算成普通覆盖 ──────────────────────────────────

    @Test
    fun `负例_原件区_标originalsTouched而不是普通覆盖`() {
        val facts = Fake(sizes = mapOf(k("资料库/原始/诗.txt") to 9L))
        val i = ImpactPreview.preview(
            call("write_file", """{"path":"资料库/原始/诗.txt","content":"x","overwrite":true}"""),
            facts,
        )
        assertTrue(i.originalsTouched)
    }

    @Test
    fun `负例_原件区同前缀兄弟目录_不算原件区`() {
        val facts = Fake(sizes = mapOf(k("资料库/原始2/诗.txt") to 9L))
        val i = ImpactPreview.preview(
            call("write_file", """{"path":"资料库/原始2/诗.txt","content":"x","overwrite":true}"""),
            facts,
        )
        assertFalse("组件级比较：原始2 不是原件区", i.originalsTouched)
    }

    // ── 负例 6：不预览的动作一律 NONE（界面一个字不显示）─────────────────

    @Test
    fun `负例_只读动作_没有可说的东西`() {
        val i = ImpactPreview.preview(call("read_file", """{"path":"a.txt"}"""), Fake())
        assertEquals(Impact.Kind.NONE, i.kind)
        assertTrue(i.isEmpty)
        assertNull(ImpactLine.text(i))
    }

    // ── 空事实源：什么都不知道时绝不编 ──────────────────────────────────

    @Test
    fun `空事实源_写文件降级为未知而不是新建1`() {
        val i = ImpactPreview.preview(call("write_file", """{"path":"a.txt","content":"x"}"""), NoFacts)
        assertTrue("拿不到根 ⇒ 不知道目标在不在 ⇒ 未知", i.unknown)
        assertEquals(0, i.newFiles)
    }
}
