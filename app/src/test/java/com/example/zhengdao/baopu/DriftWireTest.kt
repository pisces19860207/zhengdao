// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十四批 · 漂移闸接线的验收测试（判据五条正例 ＋ 四条负例，JVM 假 DOM）。
//
// 正例（编号＝派工单判据）：
//   ① 改一个选择器 ⇒ 回放前判 DRIFT 并降级为需确认（W1）；
//   ② 连 3 次 ⇒ 停用并进未办成单（W3）；
//   ③ recalibrate 后立刻恢复（含已停用的技能：W4）；
//   ④ 探针只取选择器存在性，不抓正文/不截图（W5 源级门禁 ＋ W7 结构回抄）；
//   ⑤ 有生产调用点（W6 源级门禁）。
//
// 负例（各红）：
//   - 探针抓页面正文（W5 的禁词扫描会红）；
//   - DRIFT 直接禁用（W2：一次漂移不停用）；
//   - 只接 preflight 不接 recalibrate（W6：回放路径必须同时含四处调用）；
//   - 连 3 次不停用（W3 的第三次必停）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedReason
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriftWireTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var globalRoot: File
    private lateinit var unfinishedDirs: List<File>

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        globalRoot = tmp.newFolder("global")
        unfinishedDirs = listOf(tmp.newFolder("memory"))
        DecisionLedger.install(globalRoot)
    }

    // ── 夹具（与 DriftCheckTest 同一套口径，不自造第二份）────────────────────

    private val fpLine = "<!-- contract fingerprint web domain=example.com selectors=#order-list,#status-badge -->"

    private fun body(vararg extra: String): String = (listOf(
        "# 装车核对",
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
        fpLine,
    ) + extra.toList() + listOf("", "## 步骤", "1. 打开装车单列表", "2. 核对装车状态")).joinToString("\n")

    /** 盘上一条正式技能（正文带指纹行）。 */
    private fun putSkill(name: String): File {
        val plan = SkillStore.plan(
            proposal = SkillProposal(SkillScope.PROJECT, name, body(), "漂移接线用例", draft = false),
            projectRoot = ws,
            globalRoot = globalRoot,
            backupStamp = "s",
        )
        val r = SkillStore.apply(
            plan,
            PersonaFiles(projectRoot = ws, globalRoot = globalRoot),
            PersonaFiles(projectRoot = ws, globalRoot = globalRoot).read(PersonaLayer.C_PROJECT).orEmpty(),
        )
        assertTrue("前置：技能要能写成：$r", r is SkillWriteResult.Written)
        return (r as SkillWriteResult.Written).file
    }

    /** 假 DOM：选择器在不在由测试说了算；selector 一栏**按位置回抄入参**（与生产探针同一约定）。 */
    private class FakeReader(
        private val url: String? = "https://example.com/list",
        present: Set<String> = emptySet(),
    ) : DriftPageReader {
        var probes = 0
        var presentSet: Set<String> = present

        override fun currentUrl(): String? = url

        override fun selectorPresence(selectors: List<String>): List<SelectorPresence>? {
            probes += 1
            // 入参顺序回抄：假 DOM 也装不进任何页面文本
            return selectors.map { SelectorPresence(selector = it, present = it in presentSet, visible = it in presentSet) }
        }
    }

    private fun gate(
        reader: DriftPageReader?,
        name: String = "装车核对",
        choice: DriftChoice = DriftChoice.ASK,
        nowMs: Long = 1_000L,
        stampText: String = "s",
    ): DriftGateOutcome = DriftWire.gate(
        reader = reader,
        projectRoot = ws,
        name = name,
        choice = choice,
        unfinishedDirs = unfinishedDirs,
        globalRoot = globalRoot,
        personaFiles = PersonaFiles(projectRoot = ws, globalRoot = globalRoot),
        stampText = stampText,
        nowMs = nowMs,
    )

    // ── ① 改一个选择器 ⇒ 回放前判 DRIFT 并降级 ─────────────────────────────

    @Test
    fun `W1_改一个选择器_回放前判漂移并降级为需确认`() {
        val md = putSkill("装车核对")
        val out = gate(FakeReader(present = setOf("#order-list")), choice = DriftChoice.ASK)
        assertTrue("降级该是问人：$out", out is DriftGateOutcome.Ask)
        val ask = (out as DriftGateOutcome.Ask).reason
        assertTrue("问题要带三选一的指引：$ask", ask.contains("照跑") && ask.contains("这是新版") && ask.contains("算了"))
        assertTrue("要说出少了什么：$ask", ask.contains("#status-badge"))
        // ASK 只是问，不记账也不改盘
        val state = DriftStore.load(ws, "装车核对")
        assertEquals("问人阶段不许推进计数", 0, state.streak)
        assertNull("负例：一次漂移就停用 ⇒ 这里必须没有停用章", state.disabledAtMs)
        assertTrue("技能正文一个字没动", md.readText().contains(fpLine))
    }

    @Test
    fun `W2_负例_一次漂移照跑不停用_只降级`() {
        putSkill("装车核对")
        val out = gate(FakeReader(present = setOf("#order-list")), choice = DriftChoice.PROCEED)
        assertTrue("照跑该放行：$out", out is DriftGateOutcome.Proceed)
        val state = DriftStore.load(ws, "装车核对")
        assertEquals("漂移要记一笔（不记就数不到 3）", 1, state.streak)
        assertNull("负例：DRIFT 直接禁用 ⇒ 第一次必须没有停用章", state.disabledAtMs)
        assertEquals(DriftGate.CONFIRM, DriftCheck.gate(state))
    }

    // ── ② 连 3 次 ⇒ 停用并进未办成单 ────────────────────────────────────────

    @Test
    fun `W3_连三次停用并进未办成单`() {
        putSkill("装车核对")
        val reader = FakeReader(present = setOf("#order-list"))
        assertTrue("第 1 次不停用", gate(reader, choice = DriftChoice.PROCEED, nowMs = 1_000L) is DriftGateOutcome.Proceed)
        assertTrue("第 2 次不停用", gate(reader, choice = DriftChoice.PROCEED, nowMs = 2_000L) is DriftGateOutcome.Proceed)
        val third = gate(reader, choice = DriftChoice.PROCEED, nowMs = 3_000L)
        assertTrue("负例：连 3 次不停用 ⇒ 第三次必须停", third is DriftGateOutcome.Stop)
        val state = DriftStore.load(ws, "装车核对")
        assertEquals(3, state.streak)
        assertEquals(DriftGate.DISABLED, DriftCheck.gate(state))

        val row = UnfinishedLedger(unfinishedDirs).load().singleOrNull { it.reasonRef == "skill:装车核对" }
        assertNotNull("停用必须进未办成单", row)
        assertEquals(UnfinishedReason.REALITY_DRIFT, row!!.reason)
    }

    // ── ③ recalibrate 后立刻恢复（含已停用技能）────────────────────────────

    @Test
    fun `W4_停用后确认这是新版_校准立刻恢复`() {
        val md = putSkill("装车核对")
        // 300 API 语义（实测对齐）：探针只认契约里登记的选择器，所以校准写回的是**幸存锚点**——
        // 旧契约的 #status-badge 没了，#order-list 还在 ⇒ 新指纹=[#order-list]。
        // 页面上新长出来的锚点（#status-badge-v2 等）探针看不见，补齐归重教，不归接线。
        val reader = FakeReader(present = setOf("#order-list", "#status-badge-v2", "#new-banner"))
        repeat(3) { i -> gate(reader, choice = DriftChoice.PROCEED, nowMs = 1_000L + i) }
        assertEquals(DriftGate.DISABLED, DriftCheck.gate(DriftStore.load(ws, "装车核对")))

        val out = gate(reader, choice = DriftChoice.NEW_VERSION, nowMs = 9_000L, stampText = "s2")
        assertTrue("确认新版该放行：$out", out is DriftGateOutcome.Proceed)
        val state = DriftStore.load(ws, "装车核对")
        assertEquals("停用要解", null, state.disabledAtMs)
        assertEquals(0, state.streak)
        assertEquals(1, state.recalibrations)
        assertEquals(DriftGate.RUN, DriftCheck.gate(state))
        val text = md.readText()
        assertTrue("幸存锚点要写回契约：$text", text.contains("selectors=#order-list -->"))
        assertFalse("旧指纹行该被换掉", text.contains(fpLine))
        // 校准账：一次改动一行，带确认人
        val ledger = File(ws, "技能/_变更.jsonl").readLines().filter { it.contains("重新校准") }
        assertEquals("校准账有且只有一条：$ledger", 1, ledger.size)
        assertTrue("账上要带确认人", ledger.first().contains(DriftWire.CONFIRMED_BY))
        // 恢复之后同一现场 ⇒ 立刻一致（RUN 分支记 Consistent 归零）
        assertTrue(gate(reader, choice = DriftChoice.ASK, nowMs = 10_000L) is DriftGateOutcome.Proceed)
    }

    @Test
    fun `W4c_全新改版旧锚点全灭_校准被拒_停用保持`() {
        val md = putSkill("装车核对")
        val reader = FakeReader(present = setOf("#order-list"))
        repeat(3) { i -> gate(reader, choice = DriftChoice.PROCEED, nowMs = 1_000L + i) }
        assertEquals(DriftGate.DISABLED, DriftCheck.gate(DriftStore.load(ws, "装车核对")))
        // 页面彻底改版：契约里的选择器一个都不剩 ⇒ 现场读数空 ⇒ 校准 fail-closed 拒绝
        reader.presentSet = emptySet()
        val out = gate(reader, choice = DriftChoice.NEW_VERSION, nowMs = 9_000L, stampText = "s2")
        assertTrue("读数不全不许校准（不拿半份读数当新契约）：$out", out is DriftGateOutcome.Stop)
        val state = DriftStore.load(ws, "装车核对")
        assertNotNull("停用章不许被解", state.disabledAtMs)
        assertEquals("旧正文一个字没动", true, md.readText().contains(fpLine))
        val ledger = File(ws, "技能/_变更.jsonl").readLines().filter { it.contains("重新校准") }
        assertEquals("被拒的校准不许留账：$ledger", 0, ledger.size)
    }

    @Test
    fun `W4b_停用不许照跑绕过_只能校准`() {
        putSkill("装车核对")
        val reader = FakeReader(present = setOf("#order-list"))
        repeat(3) { i -> gate(reader, choice = DriftChoice.PROCEED, nowMs = 1_000L + i) }
        assertTrue("停用后照跑不许放行", gate(reader, choice = DriftChoice.PROCEED, nowMs = 4_000L) is DriftGateOutcome.Stop)
        assertTrue("停用后问人也是停（引导去校准）", gate(reader, choice = DriftChoice.ASK, nowMs = 5_000L) is DriftGateOutcome.Stop)
        assertEquals("硬状态不许被绕开时推进计数", 3, DriftStore.load(ws, "装车核对").streak)
    }

    // ── ④ 探针只取存在性（源级门禁 ＋ 结构回抄）────────────────────────────

    /** 去注释后的源码（W5/W6 共用；行注释与块注释都剥，防「只写在注释里」的假绿）。 */
    private fun sourceOf(rel: String): String {
        val f = File("src/main/java/com/example/zhengdao/$rel")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/$rel") }
        assertTrue("源码文件要存在：${f.absolutePath}", f.isFile)
        val raw = f.readText()
        val noBlock = raw.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.lines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    @Test
    fun `W5_负例_探针不许抓正文不许截图`() {
        val src = sourceOf("baopu/DriftWire.kt")
        for (banned in listOf(
            "textContent", "innerText", "outerHTML", "innerHTML", "nodeValue",
            "captureShot", "screencap", "screenshot", "data:text", "eval(",
        )) {
            val hits = src.lines().filter { it.contains(banned) }
            assertTrue("判据④：探针层出现 $banned ⇒ ${hits.firstOrNull()}", hits.isEmpty())
        }
        // 结构保证：存在性读数上只有「原串＋两个布尔」，没有装正文的格子
        val fields = SelectorPresence::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            "SelectorPresence 只许 selector/present/visible：$fields",
            fields.containsAll(listOf("selector", "present", "visible")) &&
                fields.none { it !in setOf("selector", "present", "visible", "Companion", "serialVersionUID", "\$stable") },
        )
    }

    @Test
    fun `W7_命中结果按契约原串回抄_不做归一化`() {
        val presence = listOf(
            SelectorPresence("#a > .b", present = true, visible = true),
            SelectorPresence("#gone", present = false, visible = false),
        )
        assertEquals("原样回抄（300 硬口径 2：不做 CSS 归一化）", listOf("#a > .b"), DriftProbe.hitSelectors(presence))
        val live = DriftProbe.observe("https://example.com/list", presence)
        assertEquals("https://example.com/list", live.url)
        // 与契约比对：书写的空格形状不影响「在不在」（比对是 trim 后逐字相等，判据在 DriftCheck）
        val stored = DriftFingerprint.Web("example.com", listOf("#a > .b"))
        assertEquals(DriftVerdict.Consistent, DriftCheck.compare(stored, live))
        // 读数缺位（探针不通）⇒ 空命中 ⇒ NoData，不冒充一致
        val broken = DriftProbe.observe("https://example.com/list", null)
        assertTrue(DriftCheck.compare(stored, broken) is DriftVerdict.NoData)
    }

    @Test
    fun `W7b_判不了不推进计数_也不冒充一致`() {
        putSkill("装车核对")
        val reader = FakeReader(url = null) // execJs 通了但没有地址 ⇒ 空命中
        repeat(3) { i ->
            val out = gate(reader, choice = DriftChoice.PROCEED, nowMs = 1_000L + i)
            assertTrue("判不了 ⇒ 先问人再照跑（记账但不推进）：$out", out is DriftGateOutcome.Proceed)
        }
        val state = DriftStore.load(ws, "装车核对")
        assertEquals("负例：判不了不许算到技能头上", 0, state.streak)
        assertNull(state.disabledAtMs)
        assertEquals("但也不放过：闸仍要人看一眼", DriftGate.CONFIRM, DriftCheck.gate(state))
    }

    // ── ⑤ 有生产调用点（源级门禁）──────────────────────────────────────────

    @Test
    fun `W6_负例_回放路径必须四环全接`() {
        val replay = sourceOf("baopu/TeachReplay.kt")
        // 两个回放入口都经装配助手 driftGate(context,…)，助手再调 DriftWire.gate（≥1 处）
        val wired = Regex("""driftGate\(context""").findAll(replay).count()
        assertTrue("判据⑤：两条回放入口都要过漂移闸（找到 $wired 处）", wired >= 2)
        val helper = Regex("""DriftWire\.gate\(""").findAll(replay).count()
        assertTrue("装配助手要真调 DriftWire.gate（找到 $helper 处）", helper >= 1)
        val wire = sourceOf("baopu/DriftWire.kt")
        for (must in listOf(
            "DriftCheck.preflight(",      // 环②：判
            "DriftStore.recordVerdict(",  // 环④：记账
            "DriftStore.recalibrate(",    // 环③：确认新版
            "DriftProbe.observe(",        // 环①：探针喂现场
        )) {
            assertTrue("接线层缺环：$must", wire.contains(must))
        }
    }

    @Test
    fun `W8_没有web指纹的技能不设闸_探针一个字节都不查`() {
        val name = "无指纹技能"
        val bodyNoFp = body().lines().filterNot { it.startsWith("<!-- contract fingerprint") }.joinToString("\n")
        SkillStore.plan(
            proposal = SkillProposal(SkillScope.PROJECT, name, bodyNoFp, "用例", draft = false),
            projectRoot = ws,
            globalRoot = globalRoot,
            backupStamp = "s",
        ).let { plan ->
            val r = SkillStore.apply(
                plan,
                PersonaFiles(projectRoot = ws, globalRoot = globalRoot),
                PersonaFiles(projectRoot = ws, globalRoot = globalRoot).read(PersonaLayer.C_PROJECT).orEmpty(),
            )
            assertTrue("前置：技能要能写成：$r", r is SkillWriteResult.Written)
        }
        val reader = FakeReader(present = emptySet())
        assertTrue(gate(reader, name = name, choice = DriftChoice.PROCEED) is DriftGateOutcome.Proceed)
        assertEquals("没有 web 指纹就不许碰页面", 0, reader.probes)
        assertEquals("也没记账", 0, DriftStore.load(ws, name).streak)
    }

    @Test
    fun `W9_算了就不跑不记_取消后盘面一个字不动`() {
        val md = putSkill("装车核对")
        val before = md.readText()
        val out = gate(FakeReader(present = setOf("#order-list")), choice = DriftChoice.CANCEL, nowMs = 1_000L)
        assertTrue(out is DriftGateOutcome.Stop)
        assertTrue("要告诉用户没跑也没记", (out as DriftGateOutcome.Stop).reason.contains("没跑"))
        val state = DriftStore.load(ws, "装车核对")
        assertEquals(0, state.streak)
        assertNull(state.disabledAtMs)
        assertEquals(before, md.readText())
    }

    @Test
    fun `W10_drift参数不认得的值退回问人`() {
        assertNull(DriftChoice.ofWire("whatever"))
        assertEquals(DriftChoice.PROCEED, DriftChoice.ofWire("proceed"))
        assertEquals(DriftChoice.NEW_VERSION, DriftChoice.ofWire("new_version"))
        assertEquals(DriftChoice.CANCEL, DriftChoice.ofWire("cancel"))
        assertEquals(DriftChoice.ASK, DriftChoice.ofWire("ask"))
    }
}
