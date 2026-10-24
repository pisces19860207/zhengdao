// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §2/§4 执行器单测：正例（录 5 步 → 回放通过 → 再跑一次仍通过）＋
// 七条负例逐条红——全部打在真判据（TeachResolve/TeachExecutor/TeachSafety）上，
// 不 mock 判据本身。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeachExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 夹具：公开搜索页三张（与 TeachLearnerTest 的示教事件同一套页面）────────

    val START = "https://search.example.com/start"
    val RESULTS = "https://search.example.com/results?wb=WB12345678"
    val EXPORT = "https://search.example.com/export"

    private fun rows(n: Int, prefix: String = "行"): List<FakeEl> =
        (1..n).map { FakeEl(tag = "tr", text = "$prefix$it") }

    private fun searchPages(): Map<String, FakePage> {
        val startPage = FakePage(
            START,
            listOf(
                FakeEl(tag = "th", text = "关键词"),
                FakeEl(
                    tag = "input", id = "search-input", nameAttr = "q", inputType = "search",
                    placeholder = "输入关键词", formLabel = "关键词", classes = listOf("filter-box"),
                ),
                FakeEl(
                    tag = "button", text = "搜索", testid = "search-btn", id = "btn-search",
                    classes = listOf("btn", "primary"),
                    onClick = { it.navigate(RESULTS) },
                ),
                // 诱饵：页面上有个「提交」按钮——录制时不点（学习器拒记），回放时也不许点（执行器拒点）
                FakeEl(tag = "button", text = "提交", classes = listOf("trap")),
            ),
        )
        val resultsPage = FakePage(
            RESULTS,
            listOf(
                FakeEl(tag = "th", text = "运单号"),
                FakeEl(
                    tag = "input", id = "filter-input", nameAttr = "filter", inputType = "text",
                    placeholder = "筛选运单号", formLabel = "筛选",
                ),
                FakeEl(
                    tag = "button", text = "下一页", classes = listOf("pager-next"),
                    onClick = { dom -> dom.replacePage(RESULTS) { els -> els.filter { it.upperTag != "TR" } + rows(15, "次") } },
                ),
                FakeEl(
                    tag = "button", text = "导出", classes = listOf("btn", "export"),
                    onClick = { it.navigate(EXPORT) },
                ),
            ) + rows(20),
        )
        val exportPage = FakePage(EXPORT, listOf(FakeEl(tag = "th", text = "导出清单"), FakeEl(tag = "button", text = "返回")))
        return mapOf(START to startPage, RESULTS to resultsPage, EXPORT to exportPage)
    }

    /** 用「真学习器」从示教事件造技能（判据链完整：不是手写技能绕过学习器）。 */
    private fun skillFromTeaching(): TeachSkill {
        val learnerEvents = TeachLearnerTestAccess.fiveEvents()
        val draft = TeachLearner.buildDraft(
            learnerEvents, "查货", "查当日报文",
            TeachPageFacts(START, listOf("关键词"), listOf("搜索", "提交")),
            nowMs = 1_728_000_000_000L,
        )
        return draft.skill
    }

    private fun executor(dom: FakeDom, audits: MutableList<String> = mutableListOf()): Pair<TeachExecutor, File> {
        val evidenceRoot = tmp.newFolder()
        val sink = FileTeachEvidenceSink(evidenceRoot)
        // 可推进的假时钟：expect 轮询的时限靠 sleeper 推着走（不真睡，也不会死循环）
        var now = 0L
        val ex = TeachExecutor(
            dom = dom,
            evidence = sink,
            audit = { report, ts -> audits += report.auditLine(ts) },
            clock = { now },
            sleeper = { now += TeachExecutor.EXPECT_POLL_MS },
        )
        return ex to evidenceRoot
    }

    // ── 正例：录 5 步 → 回放通过 → 再跑一次仍通过 ─────────────────────────────

    @Test
    fun `正例_5步技能回放通过_证据与审计都落`() {
        val skill = skillFromTeaching()
        val audits = mutableListOf<String>()
        val dom = FakeDom(searchPages(), START)
        val (ex, evidenceRoot) = executor(dom, audits)
        val report = ex.run(skill)

        assertTrue("回放应通过：${report.stopReason}", report.ok)
        assertEquals(5, report.stepsDone)
        assertTrue(report.drifts.isEmpty())
        // 动作都对得上：输入×2、点击×3；诱饵没被点（负例 3 的顺带断言）
        assertEquals(listOf("BUTTON/搜索", "BUTTON/下一页", "BUTTON/导出"), dom.clicks)
        assertTrue(dom.poisonClicked.isEmpty())
        assertEquals(2, dom.typed.size)
        // 审计行（§3.5）
        assertEquals(1, audits.size)
        assertTrue(audits[0].contains("\"ok\":true"))
        assertTrue(audits[0].contains("\"steps\":5"))
        // 证据目录（§2 取证）
        assertNotNull(report.evidenceDir)
        val dir = File(report.evidenceDir!!)
        assertTrue(dir.isDirectory)
        assertTrue(File(dir, "step-1-type.dom.txt").isFile)
        assertTrue(File(dir, "step-2-click.dom.txt").isFile)
        assertTrue(File(dir, "run.json").isFile)
        assertEquals(dir.parentFile, File(evidenceRoot, "玄览技能"))
    }

    @Test
    fun `正例_再跑一次仍通过_技能文件未动`() {
        val skill = skillFromTeaching()
        val jsonBefore = skill.toJsonText()
        val r1 = executor(FakeDom(searchPages(), START)).first.run(skill)
        val r2 = executor(FakeDom(searchPages(), START)).first.run(skill)
        assertTrue(r1.ok)
        assertTrue(r2.ok)
        assertEquals(jsonBefore, skill.toJsonText()) // 技能对象全程只读
    }

    // ── 负例 1：改一个 class ⇒ 自愈成功且标「定位漂移」，技能文件未变（哈希不变）──

    @Test
    fun `负例1_类名改了_自愈成功_标定位漂移_技能哈希不变`() {
        // 手写技能（执行器开发期的试跑方式）：锚是 data-testid + CSS 类
        val skill = TeachSkill(
            name = "查明细", purpose = "查明细", createdAtMs = 0,
            startUrl = RESULTS, allowedDomains = listOf("search.example.com"),
            clickTagWhitelist = listOf("BUTTON"),
            fingerprint = TeachFingerprint(listOf(TeachFingerprintAnchor("header", "运单号"))),
            steps = listOf(
                TeachStep(
                    index = 1, action = TeachAction.CLICK, url = RESULTS,
                    locators = listOf(
                        TeachLocator(TeachLocatorKind.TESTID, "detail-btn"),
                        TeachLocator(TeachLocatorKind.CSS_CLASS, "btn.detail"),
                    ),
                    value = null,
                    snapshot = TeachElementSnapshot("BUTTON", "button", "查明细", "查明细", null),
                    expect = listOf(TeachExpect(TeachExpectKind.URL_CHANGED, null)),
                ),
            ),
        )
        val jsonBefore = skill.toJsonText()
        val hashBefore = sha256(jsonBefore)

        // 变异页面：testid 没了、类名全换——录下的两档全空
        val dom = FakeDom(searchPages(), RESULTS)
        dom.replacePage(RESULTS) { els ->
            els + FakeEl(
                tag = "button", text = "查明细", classes = listOf("b2"),
                onClick = { it.navigate(EXPORT) },
            )
        }
        val report = executor(dom).first.run(skill)

        assertTrue("自愈应成功：${report.stopReason}", report.ok)
        assertEquals(1, report.drifts.size)
        val drift = report.drifts[0]
        assertEquals(1, drift.step)
        assertTrue(drift.oldLocator.contains("T2"))
        assertTrue("漂移要落到语义档：${drift.newLocator}", drift.newLocator.contains("T1"))
        assertTrue(drift.newLocator.contains("查明细"))
        assertEquals("BUTTON/查明细", dom.clicks.first())
        // 技能未变（哈希不变）——自愈结果只进报告，不回写技能
        assertEquals(hashBefore, sha256(skill.toJsonText()))
        assertEquals(jsonBefore, skill.toJsonText())
    }

    // ── 负例 2：改表头/关键按钮名 ⇒ 停手报「结构变了」，不硬跑 ──────────────────

    @Test
    fun `负例2_表头改字_指纹不符_一步都不跑`() {
        val skill = skillFromTeaching()
        val dom = FakeDom(searchPages(), START)
        dom.replacePage(START) { els ->
            els.map { if (it.upperTag == "TH") FakeEl(tag = "th", text = "关键字") else it }
        }
        val report = executor(dom).first.run(skill)

        assertFalse(report.ok)
        assertTrue(report.stopReason!!.contains("页面结构变了"))
        assertTrue(report.stopReason.contains("关键词")) // 缺哪个锚要说明
        assertEquals(0, report.stepsDone)
        assertEquals(0, dom.clicks.size) // 一步都没动
    }

    // ── 负例 3：诱饵「提交」按钮——录制不点、回放不许点（代码层面拒）────────────

    @Test
    fun `负例3a_正常回放_诱饵提交按钮从未被点`() {
        val skill = skillFromTeaching()
        val dom = FakeDom(searchPages(), START)
        val report = executor(dom).first.run(skill)
        assertTrue(report.ok)
        assertEquals(listOf("BUTTON/搜索", "BUTTON/下一页", "BUTTON/导出"), dom.clicks)
        assertFalse("诱饵「提交」不许被点：${dom.clicks}", dom.clicks.any { it.contains("提交") })
        assertTrue("诱饵哨兵必须为空", dom.poisonClicked.isEmpty())
    }

    @Test
    fun `负例3b_被污染的技能指向提交_执行器代码层面拒点`() {
        // 场景：有人手改了技能（或学习器有 bug），某步的定位就指向「提交」——
        // 执行器解析成功也不能点：TeachSafety 在代码层面拒，先于一切动作。
        val skill = skillFromTeaching().let { s ->
            s.copy(
                steps = listOf(
                    TeachStep(
                        index = 1, action = TeachAction.CLICK, url = s.startUrl,
                        locators = listOf(TeachLocator(TeachLocatorKind.TEXT, "提交")),
                        value = null,
                        snapshot = TeachElementSnapshot("BUTTON", "button", "提交", "提交", null),
                        expect = emptyList(),
                    ),
                ) + s.steps,
            )
        }
        val dom = FakeDom(searchPages(), START)
        val report = executor(dom).first.run(skill)

        assertFalse(report.ok)
        assertTrue(report.stopReason!!.contains("拒点"))
        assertTrue(report.stopReason.contains("提交"))
        assertEquals(0, dom.clicks.size) // 没点任何东西
        assertEquals(1, report.refusals.size)
        assertTrue(report.refusals[0].contains("提交"))
    }

    // ── 负例 4：密码框 ⇒ 拒填并报原因 ──────────────────────────────────────────

    @Test
    fun `负例4_密码框拒填_报原因不输入`() {
        val skill = skillFromTeaching().let { s ->
            s.copy(
                steps = listOf(
                    TeachStep(
                        index = 1, action = TeachAction.TYPE, url = s.startUrl,
                        locators = listOf(
                            TeachLocator(TeachLocatorKind.ELEM_ID, "login-pwd"),
                            TeachLocator(TeachLocatorKind.INPUT_NAME, "password"),
                        ),
                        value = "hunter2secret",
                        snapshot = TeachElementSnapshot("INPUT", "textbox", null, null, "password"),
                        expect = listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "hunter2secret")),
                    ),
                ),
            )
        }
        val dom = FakeDom(searchPages(), START)
        dom.replacePage(START) { els ->
            els + FakeEl(tag = "input", id = "login-pwd", nameAttr = "password", inputType = "password")
        }
        val report = executor(dom).first.run(skill)

        assertFalse(report.ok)
        assertTrue(report.stopReason!!.contains("拒填"))
        assertTrue(report.stopReason.contains("密码框"))
        assertEquals(0, dom.typed.size) // 一个字都没输
    }

    // ── 负例 5：同一文案命中两个元素 ⇒ 停下给候选，不选第一个 ──────────────────

    @Test
    fun `负例5_同文案两个查询按钮_停下问人_给候选_不选第一个`() {
        val skill = skillFromTeaching().let { s ->
            s.copy(
                steps = listOf(
                    TeachStep(
                        index = 1, action = TeachAction.CLICK, url = s.startUrl,
                        locators = listOf(TeachLocator(TeachLocatorKind.ROLE_NAME, "button|查询")),
                        value = null,
                        snapshot = TeachElementSnapshot("BUTTON", "button", "查询", "查询", null),
                        expect = emptyList(),
                    ),
                ),
            )
        }
        val dom = FakeDom(searchPages(), START)
        dom.replacePage(START) { els ->
            els + listOf(
                FakeEl(tag = "button", text = "查询", classes = listOf("q1")),
                FakeEl(tag = "button", text = "查询", classes = listOf("q2")),
            )
        }
        val report = executor(dom).first.run(skill)

        assertFalse(report.ok)
        val ask = report.ask
        assertNotNull("必须给候选问人", ask)
        assertEquals(1, ask!!.step)
        assertTrue(ask.reason.contains("命中 2 个"))
        assertEquals(2, ask.candidates.size)
        assertEquals(listOf("BUTTON／查询", "BUTTON／查询"), ask.candidates)
        assertEquals(0, dom.clicks.size) // 没有替用户选第一个
    }

    // ── 负例 6：跳出白名单域 ⇒ 停 ──────────────────────────────────────────────

    @Test
    fun `负例6a_起点就不在授权域_一步不跑`() {
        val skill = skillFromTeaching()
        val report = executor(FakeDom(searchPages(), "https://evil.example.net/nowhere")).first.run(skill)
        assertFalse(report.ok)
        assertTrue(report.stopReason!!.contains("不在授权域"))
        assertEquals(0, report.stepsDone)
    }

    @Test
    fun `负例6b_执行中跳出授权域_立即停_后续步不跑`() {
        // 把「导出」按钮的目标改成域外站点
        val pages = searchPages()
        val skill = skillFromTeaching()
        val dom = FakeDom(pages, START)
        dom.replacePage(RESULTS) { els ->
            els.map {
                if (it.text == "导出") {
                    FakeEl(tag = "button", text = "导出", classes = listOf("btn", "export"),
                        onClick = { d -> d.navigate("https://other.example.net/done") })
                } else {
                    it
                }
            }
        }
        val report = executor(dom).first.run(skill)

        assertFalse(report.ok)
        assertTrue(report.stopReason!!.contains("跳出授权域"))
        assertTrue(report.stopReason.contains("other.example.net"))
        assertTrue("后续步骤不许再跑", report.stepsDone < skill.steps.size)
        assertTrue(dom.clicks.contains("BUTTON/导出")) // 点本身发生在授权页上（是录过的动作）
        assertFalse(dom.clicks.contains("BUTTON/返回"))
    }

    // ── 负例 7（技能里出现明文密码 ⇒ 写盘前直接拒绝）在 TeachReplayStoreTest 落盘层打；
    //    执行器侧的结构性判据（技能只读、哈希不变）已由负例 1 钉死。──────────────────

    // ── 门控与定位（TeachResolve 判据面）───────────────────────────────────────

    @Test
    fun `门控_tier顺序_高档先中就不再看低档`() {
        val dom = FakeDom(searchPages(), START)
        val res = TeachResolve.resolve(
            listOf(
                TeachLocator(TeachLocatorKind.CSS_CLASS, "btn.primary"), // 低档在后
                TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索"), // 高档在前
            ),
            dom,
        )
        assertEquals(1, res.winner?.tier)
        assertEquals(1, res.hits.size)
        // 反过来传也一样：按 tier 排序后逐档试
        val res2 = TeachResolve.resolve(
            listOf(
                TeachLocator(TeachLocatorKind.CSS_CLASS, "btn.primary"),
                TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索"),
            ),
            dom,
        )
        assertEquals(res.winner, res2.winner)
    }

    @Test
    fun `门控_只命中低档CSS_停下问人不硬点`() {
        val dom = FakeDom(searchPages(), START)
        val res = TeachResolve.resolve(listOf(TeachLocator(TeachLocatorKind.CSS_CLASS, "btn.primary")), dom)
        val verdict = TeachResolve.gate(res)
        assertTrue(verdict is TeachResolve.Verdict.AskCandidates)
        assertTrue((verdict as TeachResolve.Verdict.AskCandidates).reason.contains("低档"))
    }

    @Test
    fun `门控_0命中_进自愈`() {
        val dom = FakeDom(searchPages(), START)
        val res = TeachResolve.resolve(listOf(TeachLocator(TeachLocatorKind.TESTID, "不存在")), dom)
        assertTrue(TeachResolve.gate(res) is TeachResolve.Verdict.Heal)
    }

    @Test
    fun `自愈_快照命中多个_不再猜_Ambiguous`() {
        val dom = FakeDom(searchPages(), START)
        dom.replacePage(START) { els -> els + FakeEl(tag = "button", text = "搜索", classes = listOf("dupe")) }
        val outcome = TeachResolve.selfHeal(TeachElementSnapshot("BUTTON", "button", "搜索", "搜索", null), dom)
        assertTrue(outcome is TeachResolve.HealOutcome.Ambiguous)
        assertEquals(2, (outcome as TeachResolve.HealOutcome.Ambiguous).candidates.size)
    }

    @Test
    fun `自愈_只剩低档锚_不自动过_停下问人`() {
        // 专用最小页：整页只有这一个无名图标按钮（快照 name/text 全空仍能唯一匹配），
        // 它除 CSS 外没有任何语义锚——自愈不许只凭低档锚自动继续。
        val url = "https://search.example.com/icon"
        val dom = FakeDom(
            mapOf(url to FakePage(url, listOf(FakeEl(tag = "button", text = "", classes = listOf("icon-go"))))),
            url,
        )
        val outcome = TeachResolve.selfHeal(TeachElementSnapshot("BUTTON", "button", null, null, null), dom)
        assertTrue(outcome is TeachResolve.HealOutcome.NotFound)
        assertTrue((outcome as TeachResolve.HealOutcome.NotFound).reason.contains("低档"))
    }

    @Test
    fun `expect不满足_停并取证`() {
        // 「导出」点了但没有 URL 变化（onClick 被去掉）⇒ expect URL_CHANGED 不满足 ⇒ 停
        val skill = skillFromTeaching()
        val dom = FakeDom(searchPages(), START)
        dom.replacePage(RESULTS) { els -> els.map { if (it.text == "导出") FakeEl(tag = "button", text = "导出", classes = listOf("btn", "export")) else it } }
        val report = executor(dom).first.run(skill)
        assertFalse(report.ok)
        assertEquals(5, report.failedStep)
        assertTrue(report.stopReason!!.contains("期望断言不满足"))
        // 停之前的前 4 步都完成了
        assertEquals(4, report.stepsDone)
    }

    @Test
    fun `点击类别白名单_点示教时没点过的标签_拒`() {
        val skill = skillFromTeaching().let { s ->
            s.copy(
                steps = listOf(
                    TeachStep(
                        index = 1, action = TeachAction.CLICK, url = s.startUrl,
                        locators = listOf(TeachLocator(TeachLocatorKind.ROLE_NAME, "columnheader|关键词")),
                        value = null,
                        snapshot = TeachElementSnapshot("TH", "columnheader", "关键词", "关键词", null),
                        expect = emptyList(),
                    ),
                ),
            )
        }
        val report = executor(FakeDom(searchPages(), START)).first.run(skill)
        assertFalse(report.ok)
        assertTrue(report.stopReason!!.contains("示教时点过的元素类别"))
        assertTrue(report.refusals.isNotEmpty())
    }

    // ── 工具 ────────────────────────────────────────────────────────────────────

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 跨测试文件共用示教事件的门（把夹具接到执行器正例，判据链不靠手写技能绕过学习器）。 */
internal object TeachLearnerTestAccess {
    fun fiveEvents(): List<TeachRawEvent> = TeachLearnerTest().fiveEvents()
}
