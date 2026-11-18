// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百九十七批（裁 G-690-5）· 导航步的校验点与转正门（G2）。
//
// 病的现场：点一条新闻这种**整页跳转**的示教步，观察记录只有 `nc`（节点数）、没有 expect
// ⇒ 转正时 G2 恒不过 ⇒「点开某条链接」这类技能永远入不了库。
//
// 五条判据各一组夹具（真根因也在这几条里，见每条注释）：
//   ① 整页跳转（url 变了）⇒ 该步 expect 含 urlChanged、G2 过；
//   ② 同页锚点／SPA 没改地址 ⇒ expectless_navigate ＋ 节点数前后值、仍可过，技能正文标
//      「未校验跳转」；
//   ③ 负例：观察里没有 url 证据 ⇒ **一个断言都不写**（不许凭空造）；闭集外的断言结构门拒；
//   ④ 负例：标了窄口但 evidence 为空 ⇒ 转正拒（窄口只在有证据时开）；
//   ⑤ 回归：既有带 expect 的步行为不变。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.EntrySubject
import com.example.zhengdao.baopu.EntryVerdict
import com.example.zhengdao.baopu.ExpectlessNavigate
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.Rehearsal
import com.example.zhengdao.baopu.SkillAcceptance
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachSkillDraft
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class NavigateExpectTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        globalRoot = tmp.newFolder("global")
        DecisionLedger.install(globalRoot)
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private val listUrl = "https://news.test/list"
    private val itemUrl = "https://news.test/item/1"
    private val sessionId = "s697"

    private fun faces(name: String = "一条新闻") = ObserveFaces(
        tag = "A", role = "link", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = "div[1]>a[1]", cls = emptyList(),
    )

    private fun click(seq: Int = 1, url: String, nc: Int, ts: Long, doc: String = "doc1") = ObserveEvent(
        kind = ObserveKind.CLICK, ts = ts, docId = doc, seq = seq, url = url,
        nodeCount = nc, faces = faces(), shape = null,
    )

    private fun page(doc: String, url: String, nc: Int, ts: Long) =
        ObservePage(ts = ts, docId = doc, url = url, title = "新闻页", nodeCount = nc, nodes = emptyList())

    private fun snapshot(pages: List<ObservePage>, events: List<ObserveEvent>) =
        ObserveSnapshot(openedAtMs = 1_700_000_000_000L, events = events, pages = pages)

    private fun induce(snap: ObserveSnapshot): SkillDraft = when (val o = DraftInducer.induce(snap, sessionId, "点开一条链接", "d697")) {
        is DraftInducer.Outcome.Drafted -> o.draft
        is DraftInducer.Outcome.Rejected -> throw AssertionError("归纳应成功，实见拒收 ${o.problems}")
        DraftInducer.Outcome.NoSteps -> throw AssertionError("归纳应成功，实见 NoSteps")
    }

    private fun skillOf(draft: SkillDraft): DraftConverter.Outcome.Skill =
        when (val c = DraftConverter.toSkill(draft, "点开一条链接", emptyMap(), 1_000L)) {
            is DraftConverter.Outcome.Skill -> c
            is DraftConverter.Outcome.Refused -> throw AssertionError("转换应成功，实见拒 ${c.reason}")
        }

    /** G2 只看这两件事：每步有没有断言、窄口那几条证据齐不齐。 */
    private fun gate(converted: DraftConverter.Outcome.Skill) = SkillAcceptance.entryGate(
        subject = EntrySubject.Replay(converted.skill, expectlessNavigate = converted.expectlessNavigate),
        rehearsal = Rehearsal(sites = listOf("news.test", "other.test")),
    )

    private fun pf() = PersonaFiles(projectRoot = ws, globalRoot = globalRoot)

    // ── 判据①：整页跳转 ⇒ urlChanged，G2 过 ─────────────────────────────────

    @Test
    fun `判据1_整页跳转_归纳补urlChanged_G2通过`() {
        // 真根因夹具：点下去**换了文档**（doc1 ⇒ doc2）。旧实现按 docId 过滤「之后的页事实」，
        // 恰好把整页跳转滤掉 ⇒ 一条 expect 都推不出来（690 现场就是这一条）。
        val snap = snapshot(
            pages = listOf(page("doc2", itemUrl, 193, ts = 2_000)),
            events = listOf(click(url = listUrl, nc = 10, ts = 1_000, doc = "doc1")),
        )
        val step = induce(snap).skeleton.single()
        assertEquals("跳转有真证据 ⇒ 必须填 urlChanged（不是猜的）", listOf("urlChanged"), step.expect)
        assertTrue("这一步是导航步", step.navigate)
        assertFalse("有 url 证据就不叫「未校验跳转」", step.expectlessNavigate)

        val g2 = gate(skillOf(induce(snap)))
        assertTrue("G2 必须过（实见 $g2）", g2 is EntryVerdict.Pass)
    }

    // ── 判据②：同页没改地址 ⇒ 窄口，可过，正文标「未校验跳转」 ──────────────────

    @Test
    fun `判据2_同页锚点没改地址_标未校验跳转_可过且正文写明`() {
        // url 一直是同一个，但页面确实变了（节点数 100 → 120）——SPA/同页锚点的现场
        val snap = snapshot(
            pages = listOf(page("doc1", listUrl, 120, ts = 2_000)),
            events = listOf(click(url = listUrl, nc = 100, ts = 1_000, doc = "doc1")),
        )
        val draft = induce(snap)
        val step = draft.skeleton.single()
        assertEquals("没有 url 证据 ⇒ 一个断言都不许写", emptyList<String>(), step.expect)
        assertTrue("页面变了 ⇒ 是导航步", step.navigate)
        assertTrue("窄口打开：标「未校验跳转」", step.expectlessNavigate)
        assertEquals(100, step.nodeCount)
        assertEquals(120, step.nodeCountAfter)
        assertTrue("草案结构门要放行（证据齐）", SkillDraftRules.validate(draft) is DraftCheck.Ok)

        val converted = skillOf(draft)
        assertEquals("窄口记录跟着转换走", 1, converted.expectlessNavigate.size)
        val g2 = gate(converted)
        assertTrue("G2 必须过（窄口只在有证据时开，实见 $g2）", g2 is EntryVerdict.Pass)
        assertTrue(
            "过闸要留痕：${(g2 as EntryVerdict.Pass).notes}",
            g2.notes.any { it.contains(SkillDraftRules.EXPECTLESS_MARK) },
        )

        // 技能正文（人读卡）必须写明这一步没校验跳转（判据⑥）
        val saved = TeachReplayStore.saveConfirmed(
            draft = TeachSkillDraft(converted.skill, warnings = emptyList(), summary = draft.goal),
            projectRoot = ws,
            globalRoot = globalRoot,
            personaFiles = pf(),
            stampText = "20261118-010000",
            expectlessNavigate = converted.expectlessNavigate,
        )
        assertNotNull("窄口步要能落盘（实见 ${saved.reason}）", saved.written)
        val md = File(ws, "技能/点开一条链接/SKILL.md").readText(Charsets.UTF_8)
        assertTrue("正文要标「未校验跳转」：$md", md.contains(SkillDraftRules.EXPECTLESS_MARK))
        assertTrue("正文要说清凭什么放行（节点数前后）", md.contains("100→120"))
    }

    // ── 判据③：不许凭空造断言 ─────────────────────────────────────────────────

    @Test
    fun `判据3_观察无url证据_不许凭空造expect`() {
        val snap = snapshot(
            pages = listOf(page("doc1", listUrl, 120, ts = 2_000)),
            events = listOf(click(url = listUrl, nc = 100, ts = 1_000, doc = "doc1")),
        )
        val step = induce(snap).skeleton.single()
        assertFalse("url 没变 ⇒ 不许写 urlChanged（凑 G2 就是造假）", step.expect.contains("urlChanged"))
        assertFalse("节点数变化也不许翻译成 rowCountChange（nc≠行数）", step.expect.contains("rowCountChange"))
        assertEquals("没有证据就一条断言都不写", emptyList<String>(), step.expect)
    }

    @Test
    fun `判据3_闭集外的断言_结构门拒收`() {
        val draft = induce(
            snapshot(
                pages = listOf(page("doc2", itemUrl, 193, ts = 2_000)),
                events = listOf(click(url = listUrl, nc = 10, ts = 1_000, doc = "doc1")),
            ),
        )
        val forged = draft.copy(skeleton = draft.skeleton.map { it.copy(expect = listOf("textAppears")) })
        val check = SkillDraftRules.validate(forged)
        assertTrue("闭集外的断言要拒（实见 $check）", check is DraftCheck.Invalid)
        assertTrue(
            "拒因要点名「凭空造断言」：${(check as DraftCheck.Invalid).problems}",
            check.problems.any { it.contains(SkillDraftRules.EXPECT_FABRICATED) },
        )
    }

    // ── 判据④：窄口没有证据 ⇒ 转正拒 ──────────────────────────────────────────

    @Test
    fun `判据4_窄口但证据为空_转正拒`() {
        val snap = snapshot(
            pages = listOf(page("doc1", listUrl, 120, ts = 2_000)),
            events = listOf(click(url = listUrl, nc = 100, ts = 1_000, doc = "doc1")),
        )
        val draft = induce(snap)
        // ① 草案层：evidence 空了，窄口就不许开
        val noEvidence = draft.copy(skeleton = draft.skeleton.map { it.copy(evidence = emptyList()) })
        val check = SkillDraftRules.validate(noEvidence)
        assertTrue("草案层要拒（实见 $check）", check is DraftCheck.Invalid)
        assertTrue(
            "拒因点名窄口：${(check as DraftCheck.Invalid).problems}",
            check.problems.any { it.contains(SkillDraftRules.EXPECTLESS_NO_EVIDENCE) },
        )
        // ② 门禁层：就算把一条「没证据」的窄口记录硬递进 G2，也不许放行
        val converted = skillOf(draft)
        val bogus = ExpectlessNavigate(index = 1, evidence = emptyList(), nodeCountBefore = 100, nodeCountAfter = 120)
        assertFalse("没证据的窄口不算准入", bogus.isAdmissible())
        val g2 = SkillAcceptance.entryGate(
            subject = EntrySubject.Replay(converted.skill, expectlessNavigate = listOf(bogus)),
            rehearsal = Rehearsal(sites = listOf("news.test", "other.test")),
        )
        assertTrue("G2 必须拒（实见 $g2）", g2 is EntryVerdict.Reject)
        assertTrue("拒因要点到 G2：${(g2 as EntryVerdict.Reject).reason}", g2.reason.contains("G2"))
    }

    // ── 判据⑤：既有带 expect 的步行为不变 ────────────────────────────────────

    @Test
    fun `判据5_带expect的步回归不变`() {
        // 同文档内 url 变了（老路径）：断言照旧是 urlChanged，窄口不开
        val snap = snapshot(
            pages = listOf(page("doc1", itemUrl, 10, ts = 2_000)),
            events = listOf(click(url = listUrl, nc = 10, ts = 1_000, doc = "doc1")),
        )
        val step = induce(snap).skeleton.single()
        assertEquals(listOf("urlChanged"), step.expect)
        assertFalse(step.expectlessNavigate)
        val g2 = gate(skillOf(induce(snap)))
        assertTrue("常规步照旧过闸（实见 $g2）", g2 is EntryVerdict.Pass)
        assertFalse(
            "常规步不该出现窄口留痕：${(g2 as EntryVerdict.Pass).notes}",
            g2.notes.any { it.contains(SkillDraftRules.EXPECTLESS_MARK) },
        )
    }

    // ── 回归：观察账解析的真根因（697 修的三处让上面几条在真机上才成立）───────────

    /** 页内脚本那一行事件的形状（短键 d/s，长键一个都没有）。 */
    private fun scriptEventJson(seq: Int, doc: String, url: String, nc: Int, t: Long, kind: String = "click"): JSONObject =
        JSONObject()
            .put("a", kind).put("url", url).put("nc", nc).put("t", t)
            .put("d", doc).put("s", seq)
            .put(
                "el",
                JSONObject()
                    .put("tag", "A").put("role", "link").put("name", "一条新闻").put("xp", "div[1]>a[1]")
                    .put("cls", JSONArray()),
            )

    private fun rawDrain(inner: JSONObject): String = JSONObject().put("result", inner.toString()).toString()

    @Test
    fun `回归_页内短键d与s要能读回文档号与序号`() {
        val inner = JSONObject().put("doc", "docX")
            .put("ev", JSONArray(listOf(scriptEventJson(7, "docX", listUrl, 10, 1_000))))
        val parsed = DrainResult.parse(rawDrain(inner), nowMs = 5_000)!!
        val ev = parsed.events.single()
        assertEquals("短键 d 就是文档号（否则去重键恒 #0、一次会话只剩一行）", "docX", ev.docId)
        assertEquals("短键 s 就是序号（否则证据指针恒 #seq0）", 7, ev.seq)
        assertEquals("外层的文档号要回填进页行", "docX", parsed.docId)
    }

    @Test
    fun `回归_页行没有t时用收割时刻兜住`() {
        val inner = JSONObject().put("doc", "docY")
            .put("ev", JSONArray())
            .put("pg", JSONObject().put("url", listUrl).put("title", "列表").put("nc", 9))
        val parsed = DrainResult.parse(rawDrain(inner), nowMs = 4_242)!!
        assertEquals("页行 ts 不许是 0（0 等于「永远没有之后」）", 4_242L, parsed.page!!.ts)
        assertEquals("页行也要有文档号（否则判断不了整页跳转）", "docY", parsed.page!!.docId)
    }

    @Test
    fun `回归_实时会话可以看一眼而不结算`() {
        TempSandbox.use("zd697-peek") { root ->
            val live = ObserveSession("s697live", File(root, ObserveSession.fileNameFor("s697live"))) { 1_000L }
            live.ingest(
                DrainResult.parse(
                    rawDrain(
                        JSONObject().put("doc", "doc1")
                            .put("ev", JSONArray(listOf(scriptEventJson(1, "doc1", listUrl, 10, 900)))),
                    ),
                    nowMs = 1_000,
                )!!,
            )
            assertEquals("看一眼拿得到行", 1, live.peek().events.size)
            assertEquals("看一眼之后会话还开着（还能继续收割）", 1, live.eventCount)
            live.ingest(
                DrainResult.parse(
                    rawDrain(
                        JSONObject().put("doc", "doc1")
                            .put("ev", JSONArray(listOf(scriptEventJson(2, "doc1", listUrl, 12, 950, kind = "type")))),
                    ),
                    nowMs = 1_100,
                )!!,
            )
            assertEquals("后续行照收（peek 没冻结会话）", 2, live.peek().events.size)
        }
    }
}
