// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百批 · 契约漂移的验收测试（判据四条正例 ＋ 四条负例逐条红）。
//
// 正例（编号＝用户判据）：
//   ① 改一个选择器/表头 ⇒ 判漂移并**降级为需确认**（不是禁用）
//   ② 连续 3 次不一致 ⇒ **自动停用**并进「未办成单」（原因＝现实漂移）
//   ③ 重新校准后**立刻恢复**（状态复位、新指纹写回契约、正文其余字节不动）
//   ④ 比对 P90 < 50ms（纯本地，不联网不调模型）
//
// 负例（各红）：
//   ① 漂移直接禁用；② 连 3 次不停用；③ 比对时联网或调模型；④ 静默重写契约（校准必须人确认）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
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

class DriftCheckTest {

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

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private val fpLine = "<!-- contract fingerprint web domain=example.com selectors=#order-list,#status-badge -->"

    private fun body(vararg extra: String): String = (listOf(
        "# 装车核对",
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract protected 不点提交按钮 -->",
        fpLine,
    ) + extra.toList() + listOf("", "## 步骤", "1. 打开装车单列表", "2. 核对装车状态")).joinToString("\n")

    private fun webLive(vararg selectors: String, host: String = "example.com") =
        DriftObservation.Web("https://$host/list", selectors.toList())

    private fun storedWeb() = DriftFingerprint.Web("example.com", listOf("#order-list", "#status-badge"))

    private fun contractOf(text: String): SkillContract.Contract =
        (SkillContract.parse(text) as SkillContract.Parsed.Contracted).contract

    /** 盘上一条正式技能（正文带指纹行）。 */
    private fun putSkill(name: String): File {
        val plan = SkillStore.plan(
            proposal = SkillProposal(SkillScope.PROJECT, name, body(), "漂移用例", draft = false),
            projectRoot = ws,
            globalRoot = globalRoot,
            backupStamp = "s",
        )
        val r = SkillStore.apply(plan, PersonaFiles(projectRoot = ws, globalRoot = globalRoot), PersonaFiles(projectRoot = ws, globalRoot = globalRoot).read(PersonaLayer.C_PROJECT).orEmpty())
        assertTrue("前置：技能要能写成：$r", r is SkillWriteResult.Written)
        return (r as SkillWriteResult.Written).file
    }

    // ── 指纹的读与写 ────────────────────────────────────────────────────────

    @Test
    fun `D1_三类指纹都能读能写_往返一字不差`() {
        val web = DriftFingerprint.Web("example.com", listOf("#a", "#b"))
        val file = DriftFingerprint.FileShape("xlsx", listOf("单号", "客户", "数量"))
        val cmd = DriftFingerprint.Command("/system/bin/sh", "git version 2.42.0")
        listOf(web, file, cmd).forEach { fp ->
            val line = DriftCheck.renderLine(fp)
            assertTrue("必须是契约注释行：$line", line.startsWith("<!-- contract fingerprint "))
            val parsed = SkillContract.parse("# t\n$line\n正文")
            assertTrue("读不成：$line", parsed is SkillContract.Parsed.Contracted)
            val got = (parsed as SkillContract.Parsed.Contracted).contract.fingerprints.single()
            assertEquals("往返变了：$line", fp, got)
            assertEquals(fp.digest(), got.digest())
        }
        // 集合类分量：书写顺序变了，hash 不变（"集合 hash"的口径）
        assertEquals(web.digest(), DriftFingerprint.Web("example.com", listOf("#b", "#a")).digest())
    }

    @Test
    fun `D2_指纹行写坏了整份拒收_逐行给原因`() {
        val bad = listOf(
            "<!-- contract fingerprint web domain=example.com -->",            // 缺 selectors
            "<!-- contract fingerprint web domain=example.com selectors=#a color=red -->", // 多字段
            "<!-- contract fingerprint pdf ext=pdf headers=甲 -->",            // 类型不认识
            "<!-- contract fingerprint web domain=不是域名 selectors=#a -->",   // 域名不像
            "<!-- contract fingerprint cmd path=\"/a b version=1 -->",          // 引号没闭合
            "<!-- contract fingerprint file ext=xlsx headers= -->",            // 表头空
        )
        for (line in bad) {
            val parsed = SkillContract.parse("# t\n$line\n正文")
            assertTrue("这条该拒收：$line", parsed is SkillContract.Parsed.Invalid)
            assertTrue("拒收要指到第几行：$line", (parsed as SkillContract.Parsed.Invalid).errors.first().startsWith("第 2 行"))
        }
        // 同一类两条 ⇒ 拒收（比对时不知道该认哪条）
        val dup = SkillContract.parse("# t\n$fpLine\n$fpLine\n正文")
        assertTrue(dup is SkillContract.Parsed.Invalid)
        // 指纹是**可选段**：有它不多算"不齐"，没它也不掉出自动选中
        assertTrue(contractOf(body()).missingSections().isEmpty())
        assertTrue(contractOf(body().replace(fpLine, "")).missingSections().isEmpty())
    }

    // ── ① 改一个选择器/表头 ⇒ 判漂移并降级为需确认 ────────────────────────────

    @Test
    fun `D3_改一个选择器就判漂移_且只降级为需确认`() {
        val verdict = DriftCheck.compare(storedWeb(), webLive("#order-list", "#status-badge-v2"))
        assertTrue("该判漂移：$verdict", verdict is DriftVerdict.Drifted)
        assertEquals(listOf("#status-badge"), (verdict as DriftVerdict.Drifted).missing)

        val update = DriftCheck.record(DriftState(), verdict, 1_000L)
        assertFalse("负例①：漂移**不许**直接禁用", update.disabledNow)
        assertNull("负例①：一次漂移不许落停用章", update.state.disabledAtMs)
        assertEquals(DriftGate.CONFIRM, DriftCheck.gate(update.state))
        assertEquals(1, update.state.streak)
        // 页面多一块不算改版：extra 只记不判
        val more = DriftCheck.compare(storedWeb(), webLive("#order-list", "#status-badge", "#new-banner"))
        assertEquals(DriftVerdict.Consistent, more)
    }

    @Test
    fun `D4_表头与命令版本串同样判漂移`() {
        val file = DriftFingerprint.FileShape("xlsx", listOf("单号", "客户", "数量"))
        val hdr = DriftCheck.compare(file, DriftObservation.FileShape("装车单.xlsx", listOf("单号", "客户名称", "数量")))
        assertTrue(hdr is DriftVerdict.Drifted)
        assertEquals(listOf("客户"), (hdr as DriftVerdict.Drifted).missing)

        val ext = DriftCheck.compare(file, DriftObservation.FileShape("装车单.csv", listOf("单号", "客户", "数量")))
        assertTrue("扩展名变了也是漂移：$ext", ext is DriftVerdict.Drifted)

        val cmd = DriftFingerprint.Command("/system/bin/sh", "git version 2.42.0")
        val ver = DriftCheck.compare(cmd, DriftObservation.Command("/system/bin/sh", "git version 2.44.0"))
        assertTrue(ver is DriftVerdict.Drifted)
        assertEquals(listOf("git version 2.42.0"), (ver as DriftVerdict.Drifted).missing)

        val path = DriftCheck.compare(cmd, DriftObservation.Command("/system/bin/bash", "git version 2.42.0"))
        assertTrue(path is DriftVerdict.Drifted)
    }

    @Test
    fun `D5_一致就照常_连续计数归零`() {
        var state = DriftState()
        repeat(2) { i ->
            state = DriftCheck.record(state, DriftVerdict.Drifted(emptyList(), emptyList(), "改了"), 1_000L + i).state
        }
        assertEquals(2, state.streak)
        val ok = DriftCheck.record(state, DriftVerdict.Consistent, 2_000L)
        assertEquals("一致必须归零，否则不叫「连续」", 0, ok.state.streak)
        assertEquals(DriftGate.RUN, DriftCheck.gate(ok.state))
        // 归零之后再漂两次，仍不停用
        var again = ok.state
        repeat(2) { i ->
            again = DriftCheck.record(again, DriftVerdict.Drifted(emptyList(), emptyList(), "又改"), 3_000L + i).state
        }
        assertNull(again.disabledAtMs)
    }

    // ── ② 连续 3 次 ⇒ 自动停用并进未办成单 ──────────────────────────────────

    @Test
    fun `D6_连续三次才停用_并进未办成单_原因现实漂移`() {
        val name = "装车核对"
        putSkill(name)
        val drift = DriftVerdict.Drifted(listOf("#status-badge"), emptyList(), "选择器不见了：#status-badge")
        val dirs = listOf(tmp.newFolder("memory"))

        val first = DriftStore.recordVerdict(ws, name, drift, 1_000L, dirs)
        val second = DriftStore.recordVerdict(ws, name, drift, 2_000L, dirs)
        assertFalse("负例②：两次不许停用", second.disabledNow)
        assertEquals(2, second.state.streak)

        val third = DriftStore.recordVerdict(ws, name, drift, 3_000L, dirs)
        assertTrue("负例②：第三次必须停用", third.disabledNow)
        assertEquals(DriftGate.DISABLED, DriftCheck.gate(DriftStore.load(ws, name)))
        assertEquals(3_000L, third.state.disabledAtMs ?: -1L)

        val items = UnfinishedLedger(dirs).load()
        val row = items.singleOrNull { it.intent.contains(name) }
        assertNotNull("停用必须进未办成单：$items", row)
        val item = row!!
        assertEquals(UnfinishedOutcome.NOT_DONE, item.outcome)
        assertEquals(UnfinishedReason.REALITY_DRIFT, item.reason)
        assertEquals("skill:$name", item.reasonRef)
        assertTrue("要能补：${item.fixHint}", item.fixable && item.fixHint.contains("重新校准"))
        // 停用不删不改：正文一个字没动
        assertTrue(DriftStore.skillMdFile(ws, name).readText().contains(fpLine))
        assertTrue("第一次也照常记账", !first.disabledNow)
    }

    @Test
    fun `D7_判不了不推进连续计数_但也不放过`() {
        var state = DriftState()
        repeat(3) { i ->
            val u = DriftCheck.record(state, DriftVerdict.NoData("没读到表头"), 1_000L + i)
            assertFalse("判不了不许算到技能头上", u.disabledNow)
            state = u.state
        }
        assertEquals(0, state.streak)
        assertNull(state.disabledAtMs)
        assertEquals("判不了要人看一眼（fail-closed）", DriftGate.CONFIRM, DriftCheck.gate(state))
        // 停用之后再"一致"也不许自己解开
        val disabled = DriftState(streak = 3, lastVerdict = DriftCheck.WIRE_DRIFT, disabledAtMs = 5L, disabledWhy = "x")
        assertEquals(DriftGate.DISABLED, DriftCheck.gate(DriftCheck.record(disabled, DriftVerdict.Consistent, 9L).state))
    }

    // ── ③ 重新校准后立刻恢复 ────────────────────────────────────────────────

    @Test
    fun `D8_校准把新指纹写回契约_状态立刻恢复_其余正文不动`() {
        val name = "装车核对"
        val md = putSkill(name)
        val drift = DriftVerdict.Drifted(listOf("#status-badge"), emptyList(), "改版了")
        repeat(3) { i -> DriftStore.recordVerdict(ws, name, drift, 1_000L + i) }
        assertEquals(DriftGate.DISABLED, DriftCheck.gate(DriftStore.load(ws, name)))

        val live = webLive("#order-list", "#status-badge-v2", "#new-banner")
        val out = DriftStore.recalibrate(
            projectRoot = ws,
            globalRoot = globalRoot,
            personaFiles = PersonaFiles(projectRoot = ws, globalRoot = globalRoot),
            name = name,
            live = live,
            confirmedBy = "用户：这是新版",
            stampText = "s2",
            nowMs = 10_000L,
        )
        assertTrue("校准该成：$out", out is RecalibrateOutcome.Done)
        val done = out as RecalibrateOutcome.Done
        assertEquals(DriftGate.RUN, DriftCheck.gate(done.state))
        assertEquals(0, done.state.streak)
        assertNull(done.state.disabledAtMs)
        assertEquals(1, done.state.recalibrations)

        val text = md.readText()
        assertTrue("新选择器要写回契约：$text", text.contains("#status-badge-v2"))
        assertFalse("旧选择器该被换掉：$text", text.contains("selectors=#order-list,#status-badge -->"))
        assertTrue("正文其余字节不许动", text.contains("2. 核对装车状态"))
        val fp = contractOf(text).fingerprints.single() as DriftFingerprint.Web
        assertEquals(listOf("#order-list", "#status-badge-v2", "#new-banner"), fp.selectors)
        // 校准之后同一份现场 ⇒ 立刻一致
        assertEquals(DriftVerdict.Consistent, DriftCheck.compare(fp, live))
        // 「重新校准」要留一条账（且只有一条：一次改动一行），账上带确认人
        val ledger = File(ws, "技能/_变更.jsonl").readLines().filter { it.contains("重新校准") }
        assertEquals("校准账有且只有一条：$ledger", 1, ledger.size)
        assertTrue("账上要写清是谁确认的：${ledger.first()}", ledger.first().contains("这是新版"))
    }

    @Test
    fun `D9_负例④_没人确认就一个字节都不改`() {
        val name = "装车核对"
        val md = putSkill(name)
        val before = md.readText()
        val live = webLive("#order-list", "#status-badge-v2")

        val pure = DriftCheck.recalibrate(before, live, confirmedBy = "  ", state = DriftState(), nowMs = 1L)
        assertTrue("纯层必须拒：$pure", pure is RecalibrationResult.Refused)
        assertTrue((pure as RecalibrationResult.Refused).reason.contains("必须由人确认"))

        val out = DriftStore.recalibrate(
            ws, globalRoot, PersonaFiles(projectRoot = ws, globalRoot = globalRoot), name, live,
            confirmedBy = "", stampText = "s3", nowMs = 2L,
        )
        assertTrue("落盘层必须拒：$out", out is RecalibrateOutcome.Refused)
        assertEquals("负例④：盘上一个字节都不许动", before, md.readText())
        assertEquals(0, DriftStore.load(ws, name).recalibrations)
    }

    // ── ④ 比对够快，且不联网不调模型 ────────────────────────────────────────

    @Test
    fun `D10_比对 P90 低于 50ms`() {
        val stored = DriftFingerprint.Web("example.com", (1..8).map { "#col-$it" })
        val live = DriftObservation.Web("https://example.com/list", (1..8).map { "#col-$it" })
        val n = 400
        val samples = LongArray(n)
        repeat(n) { i ->
            val t0 = System.nanoTime()
            val v = DriftCheck.compare(stored, live)
            samples[i] = System.nanoTime() - t0
            assertTrue(v is DriftVerdict.Consistent)
        }
        samples.sort()
        val p90 = samples[n * 90 / 100]
        assertTrue("P90 必须 < 50ms（实测 ${p90 / 1_000_000.0}ms）", p90 < 50_000_000L)
    }

    @Test
    fun `D11_负例③_比对层不联网不调模型不读时钟`() {
        val src = File("src/main/java/com/example/zhengdao/baopu/DriftCheck.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/baopu/DriftCheck.kt") }
            .readText()
        val imports = Regex("""^import\s+(.+)$""", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            "纯层只许这四个 import（一个算 hash，三个是未办成单的类型）",
            listOf(
                "com.example.zhengdao.baopu.scout.UnfinishedItem",
                "com.example.zhengdao.baopu.scout.UnfinishedOutcome",
                "com.example.zhengdao.baopu.scout.UnfinishedReason",
                "java.security.MessageDigest",
            ),
            imports.sorted(),
        )
        // 用正则而不是裸串：「compareFile(」这种正常函数名里也含 File(，裸串会误伤。
        for (banned in listOf(
            "okhttp", "OkHttp", "HttpURLConnection", "java\\.net", "LlmClient", "ModelRouter",
            "RunLog", "System\\.currentTimeMillis", "SystemClock", "android\\.",
            "readText", "writeText", "Runtime\\.getRuntime", "ProcessBuilder",
            "(?<![A-Za-z])File\\(", "(?<![A-Za-z])URL\\(",
        )) {
            val re = Regex(banned)
            val hits = src.lines().filter { !it.trim().startsWith("//") && re.containsMatchIn(it) }
            assertTrue("负例③：比对层出现 $banned ⇒ ${hits.firstOrNull()}", hits.isEmpty())
        }
    }

    // ── 状态快照的读写（漂移.json 不许半份）──────────────────────────────────

    @Test
    fun `D12_漂移状态能原样读回_坏文件当空账`() {
        val name = "装车核对"
        putSkill(name)
        val drift = DriftVerdict.Drifted(listOf("#a"), emptyList(), "改了")
        DriftStore.recordVerdict(ws, name, drift, 7_000L)
        val state = DriftStore.load(ws, name)
        assertEquals(1, state.streak)
        assertEquals(DriftCheck.WIRE_DRIFT, state.lastVerdict)
        assertEquals("改了", state.lastWhy)
        assertEquals(7_000L, state.lastAtMs)

        val f = DriftStore.file(ws, name)
        f.writeText("{坏 json")
        assertEquals("坏文件不许读成停用解除，也不许炸", DriftState(), DriftStore.load(ws, name))
        assertNull(DriftStore.fromJson("""{"schema":"other"}"""))
    }
}
