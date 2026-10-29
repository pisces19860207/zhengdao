// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-28-第二百六十五批-反思自治器接线 §1 R1–R3 ＋ §3 负例①–⑤ ＋ 红线三条。
//
// 与 `ReflectionRulesTest`／`AutonomySelfEditTest` 的分工：那边打的是 205/236 的**判据**；
// 本文件打的是**接线**——判据一个字节没改（C4 钉住），改的只有"什么时候调、调完写哪儿"。
// - A 组：三类信号的认领、一条候选的形状、档位与档案行（纯函数，JVM 直测）；
// - B 组：接线的行为（临时目录＋假轨迹）——R2 落一行／下次读回来／R3 零落盘只出声；
// - C 组：源码门禁（照 `PlanContractWiringTest` 的房）——唯一调用点在 settle 末尾、
//   轮次开始时不许调、结果不许进上下文、判据常量原样、红线（不自改／不记 208）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.TracePhase
import com.example.zhengdao.baopu.agent.TraceRow
import com.example.zhengdao.baopu.agent.TraceStatus
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门禁按仓库相对路径找接线点（测试工作目录＝app 模块；`app/` 前缀兜底另一台跑法）。 */
private const val ENGINE = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
private const val WIRING = "src/main/java/com/example/zhengdao/baopu/ReflectionWiring.kt"

/** 本轮的固定时刻（2026-10-28T00:00:00Z 前后）：起点＝收工前 60 秒。 */
private const val NOW = 1_793_145_600_000L
private const val START = NOW - 60_000L
private const val RUN_ID = "r7@$START"
private const val TODAY = "2026-10-28"

class ReflectionWiringTest {

    private val dirs = mutableListOf<File>()

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
        dirs.clear()
    }

    // ── A 组 · 判据层（三类信号 → 一条候选 → 一行档案）──────────────────────────

    @Test
    fun `A1_三类信号各认各的_窗口外的账本行不算本轮证据`() {
        val f = row(1, TraceStatus.FAILED, failureKind = "TOOL_TIMEOUT")
        val ok = row(2, TraceStatus.OK)
        val rej = row(3, TraceStatus.REJECTED, tool = "delete_path")
        val inWin = auditLine(START + 10, "写手册", 1)
        val outWin = auditLine(START - 10, "上一轮的", 2)
        val r = round(trace = listOf(f, ok, rej), ledger = listOf(inWin, outWin))

        assertEquals(listOf(f), SettledReflectionRules.failures(r))
        assertEquals(listOf(rej), SettledReflectionRules.rejections(r))
        assertEquals(listOf(inWin), SettledReflectionRules.ledgerInRound(r))
        assertTrue(SettledReflectionRules.hasSignal(r))
        // 只有 OK 行 ⇒ 没有信号（"这轮挺顺"不是反思，是感想）
        assertFalse(SettledReflectionRules.hasSignal(round(trace = listOf(ok))))
    }

    @Test
    fun `A2_起点未知时账本行一条都不认_宁可少认不许错认`() {
        val r = round(ledger = listOf(auditLine(5L, "写手册", 1)), startedAtMs = 0L)
        assertTrue(SettledReflectionRules.ledgerInRound(r).isEmpty())
        assertFalse(SettledReflectionRules.hasSignal(r))
    }

    @Test
    fun `A3_一轮只出一条候选_落点是失败最多的那个工具`() {
        val rows = listOf(
            row(1, TraceStatus.FAILED, tool = "browser_navigate", failureKind = "TOOL_TIMEOUT"),
            row(2, TraceStatus.FAILED, tool = "browser_navigate", failureKind = "TOOL_ERROR"),
            row(3, TraceStatus.FAILED, tool = "read_file", failureKind = "TOOL_ERROR"),
        )
        val c = SettledReflectionRules.candidate(round(trace = rows), newDir(), newDir())
        assertNotNull(c)
        assertEquals("browser_navigate", c!!.target)
        assertEquals(ReflectionChangeKind.EDIT_FLOW, c.changeKind)
        assertTrue("主张里要带次数：${c.claim}", c.claim.contains("2 次"))
        // 证据只取**落点自己**那两条（与 205 的 twoNewest 同一口径），条条可点开；
        // 别的工具的失败行（#3 read_file）不许混进来——句子里的数和证据得说的是同一个工具。
        assertEquals(2, c.evidences.size)
        assertTrue(c.evidences.all { it.openable && it.kind == ReflectionEvidenceKind.TRACE })
        assertEquals("#2", c.evidences.last().anchor)
        assertEquals("run_trace:$RUN_ID", c.evidences.last().path)
        assertFalse("别的工具的失败行混进了证据：${c.evidences.map { it.anchor }}", c.evidences.any { it.anchor == "#3" })
        assertTrue("benefit 里的数也得是落点自己的：${c.benefit}", c.benefit.contains("少走 1 次"))
    }

    @Test
    fun `A4_一条真证据就能入账_但没有证据一个字节不写`() {
        val c = SettledReflectionRules.candidate(round(trace = failedOnce()), newDir(), newDir())!!
        assertEquals(1, c.evidences.size)
        assertTrue(SettledReflectionRules.writable(c))
        // 与 205 的出卡闸**故意不同**（那边要 ≥2 条：一张错卡要花掉用户的注意力）。
        // 这两行钉住"不同"这件事本身——谁把它顺手统一成 presentable，这里当场红。
        assertFalse(c.presentable)
        assertFalse(SettledReflectionRules.writable(c.copy(evidences = emptyList())))
        assertFalse(SettledReflectionRules.writable(c.copy(evidences = c.evidences.map { it.copy(openable = false) })))
    }

    @Test
    fun `A5_收工点判档位_A档永不可达_接线只判不写`() {
        for (kind in ReflectionChangeKind.entries) {
            val v = AutonomyRules.decide(SettledReflectionRules.proposalOf(candidateOf(kind)))
            assertFalse("改法 ${kind.label} 竟然判成自改：收工点上没有拟写正文也没有可对拍", v is AutonomyVerdict.AutoEdit)
        }
        // 三件条件（回滚点／留痕／对拍）一律 false ⇒ A 档底档也被降到 B（取严不取宽）
        val v = AutonomyRules.decide(SettledReflectionRules.proposalOf(candidateOf(ReflectionChangeKind.EDIT_SKILL)))
        assertTrue(v is AutonomyVerdict.NeedsApproval)
        assertEquals(AutonomyTier.B_HUMAN_APPROVAL, (v as AutonomyVerdict.NeedsApproval).tier)
    }

    @Test
    fun `A6_承诺形状过236的可执行闸_不是感想`() {
        for (kind in listOf(ReflectionChangeKind.EDIT_FLOW, ReflectionChangeKind.EDIT_SKILL, ReflectionChangeKind.NEW_SKILL)) {
            val c = candidateOf(kind)
            val shape = SettledReflectionRules.promiseOf(c)
            assertEquals("改法 ${kind.label} 被 236 判成不可执行", emptyList<String>(), ClosureRules.promiseFindings(shape))
            assertFalse(ClosureRules.isVague(shape.action))
            assertEquals(c.dedupKey, shape.key)
            assertTrue(shape.verifyBy.isNotBlank())
        }
    }

    @Test
    fun `A7_去重复用205的窗_否决进30天冷档_认不出的键不猜`() {
        val c = candidateOf(ReflectionChangeKind.EDIT_FLOW)
        val today = LocalDate.parse(TODAY)
        val promised = { days: Long ->
            listOf(CommitmentLine(today.minusDays(days), c.dedupKey, CommitmentStage.PROMISE, "旧动作"))
        }
        // 同键 3 天前提过 ⇒ 压（否则每轮都追加一行，档案就成了噪声）
        assertTrue(ReflectionDedup.suppressed(c, SettledReflectionRules.archiveOf(promised(3)), today))
        // 8 天前提过、没否决 ⇒ 不压
        assertFalse(ReflectionDedup.suppressed(c, SettledReflectionRules.archiveOf(promised(8)), today))
        // 10 天前被否决 ⇒ 压（承诺档案的「问过人了·否决」映射成 205 的否决）
        val rejected = listOf(
            CommitmentLine(today.minusDays(10), c.dedupKey, CommitmentStage.ASKED, CommitmentAsk.DISAGREED.wire),
        )
        assertTrue(ReflectionDedup.suppressed(c, SettledReflectionRules.archiveOf(rejected), today))
        // 键里没有"::改法" ⇒ 整行丢掉（猜出来的键会把两件事并成一件事）
        val junk = listOf(CommitmentLine(today, "没有双冒号的键", CommitmentStage.PROMISE, "x"))
        assertTrue(SettledReflectionRules.archiveOf(junk).isEmpty())
    }

    @Test
    fun `A8_入账行的依据列就是证据指针_且236的解析器读得回来`() {
        val c = candidateOf(ReflectionChangeKind.EDIT_FLOW)
        val v = AutonomyRules.decide(SettledReflectionRules.proposalOf(c))
        val line = SettledReflectionRules.lineOf(c, v, SettledReflectionRules.promiseOf(c), LocalDate.parse(TODAY))

        assertEquals(c.dedupKey, line.key)
        assertEquals(CommitmentStage.PROMISE, line.stage)
        assertEquals(AutonomyTier.B_HUMAN_APPROVAL.label, line.tier)
        assertTrue("依据列要是点得开的指针：${line.evidence}", line.evidence.contains("run_trace:$RUN_ID:#1"))
        assertTrue(line.note.startsWith(SettledReflectionRules.NOTE_PREFIX))
        // 写进去读不出来＝档案在说假话（236 的 parse 按位置取 7 列）
        val parsed = CommitmentArchive.parse(CommitmentArchive.header() + "\n" + CommitmentArchive.renderLine(line))
        assertEquals(1, parsed.size)
        assertEquals(line.key, parsed[0].key)
        assertEquals(line.evidence, parsed[0].evidence)
        assertEquals(line.tier, parsed[0].tier)
        assertEquals(0, CommitmentArchive.unreadableCount(CommitmentArchive.header() + "\n" + CommitmentArchive.renderLine(line)))
    }

    // ── B 组 · 接线的行为（R2 落一行／下次读回／R3 零落盘）───────────────────────

    @Test
    fun `B1_R2_有证据就落一行_落在236那张档案里`() {
        val ws = newDir()
        val logs = mutableListOf<String>()
        val o = wiring(ws, failedOnce(), logs).onSettled(RUN_ID, START)

        assertTrue(o is SettledReflectionOutcome.Written)
        val f = CommitmentStore.fileOf(ws)
        assertTrue("档案文件该在：${f.absolutePath}", f.isFile)
        val rows = CommitmentArchive.parse(f.readText(Charsets.UTF_8))
        assertEquals(1, rows.size)
        assertEquals("browser_navigate::改流程", rows[0].key)
        assertTrue(rows[0].evidence.contains("run_trace:"))
        assertEquals(AutonomyTier.B_HUMAN_APPROVAL.label, rows[0].tier)
        // 表头是 236 那份（本批不新增文件、不改列）
        assertTrue(CommitmentArchive.hasHeader(f.readText(Charsets.UTF_8)))
        assertTrue(logs.any { it.contains("入账一条") })
    }

    @Test
    fun `B2_R2_下一次收工先读回上次的账_同键不重复入账`() {
        val ws = newDir()
        val logs1 = mutableListOf<String>()
        wiring(ws, failedOnce(), logs1).onSettled(RUN_ID, START)
        assertEquals(1, CommitmentArchive.parse(CommitmentStore.fileOf(ws).readText(Charsets.UTF_8)).size)

        val logs2 = mutableListOf<String>()
        val o2 = wiring(ws, failedOnce(), logs2).onSettled(RUN_ID, START)
        // 读回来了：日志点出上次那条欠账（这就是"下次会话能读到"的凭据）
        assertTrue(logs2.any { it.contains("上次还欠") && it.contains("browser_navigate") })
        // 但没重复写：7 天窗压住同键
        assertTrue(o2 is SettledReflectionOutcome.NotWritten)
        assertEquals(1, CommitmentArchive.parse(CommitmentStore.fileOf(ws).readText(Charsets.UTF_8)).size)
    }

    @Test
    fun `B3_R3_没有证据零字节落盘_只记一行日志`() {
        val ws = newDir()
        val logs = mutableListOf<String>()
        val o = wiring(ws, listOf(row(1, TraceStatus.OK)), logs).onSettled(RUN_ID, START)

        assertTrue(o is SettledReflectionOutcome.NoSignal)
        assertFalse("零字节：连档案文件都不该建", CommitmentStore.fileOf(ws).exists())
        assertFalse(MemoryLayout.productsDir(ws).exists())
        assertEquals("只记一行日志，实际：$logs", 1, logs.size)
        assertTrue(logs[0].contains("没有证据，不落盘"))
    }

    @Test
    fun `B4_R3_有信号但提炼不出落点_同样零落盘`() {
        val ws = newDir()
        val logs = mutableListOf<String>()
        // 工具名是空的 ⇒ 认不出该改谁 ⇒ 不出条目（不许拿"有个东西失败了"当反思）
        val o = wiring(ws, listOf(row(1, TraceStatus.FAILED, tool = "", failureKind = "TOOL_ERROR")), logs)
            .onSettled(RUN_ID, START)

        assertTrue(o is SettledReflectionOutcome.NotWritten)
        assertFalse(CommitmentStore.fileOf(ws).exists())
        assertTrue(logs.any { it.contains("提炼不出落点") })
    }

    @Test
    fun `B5_负例④_档案写不进去必须出声_不许静默丢账`() {
        val ws = newDir()
        // 把 `产物/` 占成一个**文件** ⇒ mkdirs 失败 ⇒ append 返回 false
        MemoryLayout.productsDir(ws).writeText("我不是目录", Charsets.UTF_8)
        val logs = mutableListOf<String>()
        val o = wiring(ws, failedOnce(), logs).onSettled(RUN_ID, START)

        assertTrue(o is SettledReflectionOutcome.WriteFailed)
        assertTrue("落盘失败必须说清「没保住」，实际：$logs", logs.any { it.contains("这条反思没保住") })
        assertTrue(logs.any { it.contains("承诺档案追加失败") })
    }

    @Test
    fun `B6_本轮没起run_不反思也不落盘`() {
        val ws = newDir()
        val logs = mutableListOf<String>()
        val o = wiring(ws, failedOnce(), logs).onSettled("", START)

        assertTrue(o is SettledReflectionOutcome.NotWritten)
        assertFalse(CommitmentStore.fileOf(ws).exists())
        assertTrue(logs.any { it.contains("没有 runId") })
    }

    @Test
    fun `B7_账本行入账_第二手证据是被改的那份正文`() {
        val ws = newDir()
        writeLedger(ws, "写手册", START + 10, 1)
        writeBody(ws, "写手册")
        val logs = mutableListOf<String>()
        val o = wiring(ws, emptyList(), logs).onSettled(RUN_ID, START)

        assertTrue("该入账：$logs", o is SettledReflectionOutcome.Written)
        val line = (o as SettledReflectionOutcome.Written).row
        assertEquals("写手册::改技能", line.key)
        assertEquals(AutonomyTier.B_HUMAN_APPROVAL.label, line.tier)
        assertTrue(line.evidence.contains("技能/_变更.jsonl:L1"))
        assertTrue(line.evidence.contains("技能/写手册/SKILL.md:L1"))
    }

    // ── C 组 · 源码门禁（接线被摘掉／挪到轮次开始／塞进上下文 ⇒ 当场红）───────────

    @Test
    fun `C1_R1_唯一调用点在settle里_全仓只有引擎这一处接它`() {
        val engine = codeOf(ENGINE)
        // 恰好两次：一次定义、一次调用（多于两次＝有人在别处又调了一遍，那反思的就不是"这一轮"）
        assertEquals(2, countOf(engine, "reflectOnSettled("))
        val settleAt = engine.indexOf("private fun settle(")
        val callAt = engine.indexOf("reflectOnSettled()")
        assertTrue(settleAt >= 0)
        assertTrue("调用点要在 settle 之后（它是 settle 的最后一句）", callAt > settleAt)
        // 生产代码里认得这个名字的文件只有两个：接线层自己 ＋ 引擎
        val users = mainFiles().filter { it.readText(Charsets.UTF_8).contains("ReflectionWiring") }
            .map { it.name }.sorted()
        assertEquals(listOf("ReflectionWiring.kt", "RunEngine.kt"), users)
    }

    @Test
    fun `C2_负例②_轮次开始时不许调反思`() {
        val slice = codeSlice(ENGINE, "verifyTouched.clear()", "while (!stopRequested)")
        assertFalse("轮次开始就反思＝反思残缺状态（本轮一行轨迹都还没写）", slice.contains("reflectOnSettled"))
        assertFalse(slice.contains("ReflectionWiring"))
        // 起点与 runId 在轮次开始只许"记下来"，留给收工那次用
        assertTrue(slice.contains("roundStartedAtMs = System.currentTimeMillis()"))
        assertTrue(slice.contains("roundRunId = trace.newRun(sessionId, roundStartedAtMs)"))
    }

    @Test
    fun `C3_负例③_反思结果不许进上下文`() {
        val slice = codeSlice(ENGINE, "private fun settle(", "/** 步迁移")
        // `turnNotes`／`extraNotes` 是引擎真往提示词里塞东西的两个口（2472 那行 `extraNotes = extraNotes + …`），
        // 少禁一个，负例③就能从那儿溜回去。
        for (banned in listOf("extraNotes", "promptNotes", "turnNotes", "history", "ContextAssembler", "assemble(")) {
            assertFalse("settle 里出现了 $banned：反思结果只许落档案＋出日志", slice.contains(banned))
        }
        val w = codeOf(WIRING)
        for (banned in listOf("extraNotes", "promptNotes", "ContextAssembler", "MediatorWiring", "AssembledPrompt")) {
            assertFalse("接线层出现了 $banned：账读进上下文，模型下一轮就会照着账自我表功", w.contains(banned))
        }
        // 这条纪律要写在文件头（不写，下一个人就会照 258 的 W3 顺手加回灌）
        assertTrue(moduleText(WIRING).contains("不塞回上下文"))
    }

    @Test
    fun `C4_负例⑤_判据常量一个都没动`() {
        assertEquals(3, ReflectionRunner.MAX_CARDS)
        assertEquals(7L, ReflectionDedup.REPEAT_WINDOW_DAYS)
        assertEquals(30L, ReflectionDedup.REJECT_WINDOW_DAYS)
        assertEquals(3, ReflectionExtract.FAILURE_KIND_MIN)
        assertEquals(2, ReflectionExtract.FAILURE_DAYS_MIN)
        assertEquals(14L * 24 * 60 * 60 * 1000, ReflectionExtract.FAILURE_WINDOW_MS)
        // 236 那张档位表：接线不许加行、不许改档（5 A／5 B／6 C）
        assertEquals(16, AutonomyChangeKind.entries.size)
        assertEquals(5, AutonomyChangeKind.entries.count { it.tier == AutonomyTier.A_SELF_EDIT })
        assertEquals(5, AutonomyChangeKind.entries.count { it.tier == AutonomyTier.B_HUMAN_APPROVAL })
        assertEquals(6, AutonomyChangeKind.entries.count { it.tier == AutonomyTier.C_NEVER_AUTO })
        // 205 的出卡闸仍是 ≥2 条可点开（本批用自己的 writable，没去改它）
        assertFalse(candidateOf(ReflectionChangeKind.EDIT_FLOW).presentable)
        // 236 的承诺档案文件名与列数原样（本批不新增落盘状态）
        assertEquals("进化档案-承诺.md", CommitmentArchive.FILE_NAME)
        assertEquals(7, CommitmentArchive.header().lines()[1].split("|").size - 2)
    }

    @Test
    fun `C5_红线_接线不自改也不记208`() {
        val w = codeOf(WIRING)
        for (banned in listOf("AutonomySelfEdit", "DecisionLedger", "DecisionDraft", "DecisionKind", "SkillStore.apply", "applyProposal")) {
            assertFalse("接线里出现了 $banned：本批只判档位，不写正文、不记 208（红线）", w.contains(banned))
        }
        val engineSlice = codeSlice(ENGINE, "private fun reflectOnSettled()", "/** 步迁移")
        assertFalse(engineSlice.contains("DecisionLedger"))
        assertFalse(engineSlice.contains("AutonomySelfEdit"))
    }

    @Test
    fun `C6_接入点在落库与切通道之后_反思读得到本轮轨迹`() {
        val slice = codeSlice(ENGINE, "private fun settle(", "/** 步迁移")
        val persist = slice.indexOf("persistStep(")
        val drain = slice.indexOf("drainChannelSwitch(")
        val reflect = slice.indexOf("reflectOnSettled()")
        assertTrue(persist >= 0 && drain >= 0)
        assertTrue("反思要在切通道之后（UI 先翻状态，不为反思卡住收尾）", reflect > drain)
        assertTrue("反思要在落库之后（不等 .get() 就读，读到的是上一轮的轨迹）", reflect > persist)
        assertTrue(slice.contains("persistStep(sessionId, step, runLogState, err).get()"))
    }

    @Test
    fun `C7_接线层不碰Android_引擎侧才能在任何线程上调它`() {
        val imports = moduleText(WIRING).lines().filter { it.startsWith("import ") }
        assertTrue(imports.isNotEmpty())
        assertTrue("接线层不许出现 Android 依赖：$imports", imports.none { it.contains("android.") || it.contains("androidx.") })
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private fun newDir(): File = Files.createTempDirectory("zd265").toFile().also { dirs += it }

    private fun row(
        seq: Int,
        status: TraceStatus,
        tool: String = "browser_navigate",
        failureKind: String = "",
    ) = TraceRow(
        runId = RUN_ID,
        sessionId = 7L,
        step = 1,
        seq = seq,
        phase = TracePhase.ACT,
        status = status,
        tool = tool,
        failureKind = failureKind,
        startedAt = START,
        endedAt = NOW,
    )

    private fun failedOnce(): List<TraceRow> = listOf(row(1, TraceStatus.FAILED, failureKind = "TOOL_TIMEOUT"))

    private fun auditLine(ts: Long, name: String, lineNo: Int) = SkillAuditLine(
        file = File("技能/_变更.jsonl"),
        lineNo = lineNo,
        ts = ts,
        scope = "project",
        name = name,
        action = "update",
        reason = "补例子",
        indexUpdated = true,
    )

    private fun round(
        trace: List<TraceRow> = emptyList(),
        ledger: List<SkillAuditLine> = emptyList(),
        startedAtMs: Long = START,
    ) = SettledRound(RUN_ID, startedAtMs, NOW, trace, ledger)

    private fun candidateOf(kind: ReflectionChangeKind, target: String = "browser_navigate") = ReflectionCandidate(
        shape = ReflectionShape.REPEAT_FAILURE,
        target = target,
        changeKind = kind,
        claim = "本轮「$target」失败 2 次（TOOL_TIMEOUT）——把这次的失败条件写进它的用法说明",
        evidences = listOf(
            ReflectionEvidence("run_trace $RUN_ID#1", "run_trace:$RUN_ID", "#1", ReflectionEvidenceKind.TRACE, true),
        ),
        benefit = "下一轮同名工具少走一次失败",
        risk = "只改说明文字；必须人点",
        landing = "工具「$target」的用法说明",
        createdAt = NOW,
    )

    /** 接线（假轨迹＋临时工作区；日志全收进 [logs] 给断言看）。 */
    private fun wiring(ws: File, rows: List<TraceRow>, logs: MutableList<String>) = ReflectionWiring(
        wsRoot = ws,
        globalRoot = newDir(),
        store = CommitmentStore(CommitmentStore.fileOf(ws), { logs += it }, { NOW }),
        traceOf = { rows },
        log = { logs += it },
        clock = { NOW },
    )

    private fun writeLedger(ws: File, name: String, ts: Long, lines: Int) {
        val dir = SkillRules.projectSkillDir(ws)
        dir.mkdirs()
        val text = (1..lines).joinToString("\n") { i ->
            JSONObject()
                .put("ts", ts)
                .put("scope", "project")
                .put("name", name)
                .put("action", "update")
                .put("reason", "补例子（第 $i 行）")
                .put("indexUpdated", true)
                .toString()
        }
        SkillAudit.fileIn(dir).writeText(text + "\n", Charsets.UTF_8)
    }

    private fun writeBody(ws: File, name: String) {
        val d = File(SkillRules.projectSkillDir(ws), name)
        d.mkdirs()
        File(d, SkillRules.SKILL_FILE).writeText("# $name\n\n做法：先探再动。\n", Charsets.UTF_8)
    }

    // ── 源码门禁的四把小刀（照 PlanContractWiringTest 的房）─────────────────────

    private fun moduleText(rel: String): String {
        val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
        assertTrue("文件不见了：${f.absolutePath}（门禁按它找接线点）", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** 剥掉纯注释行后的全文（门禁只认代码；注释里提到某个名字不许算接线）。 */
    private fun codeOf(rel: String): String = codeOfText(moduleText(rel))

    /** 从 [start] 到 [end] 的切片（剥注释）：窄范围钉"这一段"的形状。 */
    private fun codeSlice(rel: String, start: String, end: String): String {
        val t = moduleText(rel)
        val a = t.indexOf(start)
        assertTrue("切片起点不在 $rel：$start", a >= 0)
        val b = t.indexOf(end, a + start.length)
        assertTrue("切片终点不在 $rel：$end", b > a)
        return codeOfText(t.substring(a, b))
    }

    private fun codeOfText(t: String): String = t.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .joinToString("\n")

    private fun countOf(text: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return n
            n++
            from = i + needle.length
        }
    }

    private fun mainRoot(): File {
        val f = File("src/main/java").let { if (it.isDirectory) it else File("app/src/main/java") }
        assertTrue("找不到 main 源码根：${f.absolutePath}", f.isDirectory)
        return f
    }

    private fun mainFiles(): List<File> =
        mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
