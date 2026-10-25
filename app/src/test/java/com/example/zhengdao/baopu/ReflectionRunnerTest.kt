// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零五批 · 反思器 v1 的判据（派工单 §4 正例＋六条负例逐条红）：
//   正例：3 天假日记＋一段重复写技能痕迹 ⇒ 出 ≤3 卡、每条 ≥2 可点开证据、进化档案多一行；
//   负例：①无证据出卡 ②证据不可点开 ③确认前写盘 ④7 天内重复提 ⑤四源全空却弹卡
//        ⑥日记空不降级 ⑦自动改技能或索引——各红（红＝这些行为被闸挡住，用例断言「挡住了」）。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReflectionRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 固定「现在」：2026-10-25 12:00 本地时区——判据按日算，钟要钉死。
    private val now: Long = ts(2026, 10, 25, 12)
    private val logs = mutableListOf<String>()

    private lateinit var ws: File
    private lateinit var global: File

    private fun runner(): ReflectionRunner =
        ReflectionRunner(
            wsRoot = ws,
            globalRoot = global,
            db = null,
            log = { logs += it },
            clock = { now },
        )

    private fun ts(y: Int, m: Int, d: Int, h: Int = 12): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun setUp() {
        ws = tmp.newFolder("ws")
        global = tmp.newFolder("global")
        logs.clear()
    }

    /** 假日记：直接走 [DiaryStore.upsert]（与生产同一条写路径，格式不另造）。 */
    private fun writeDiary(date: String, title: String, session: Long, hour: Int) {
        val store = DiaryStore(File(ws, "记忆"), db = null)
        val at = ts(date.substring(0, 4).toInt(), date.substring(5, 7).toInt(), date.substring(8, 10).toInt(), hour)
        store.upsert(
            DiaryEntry(
                id = "s$session-$date",
                date = date,
                sessionId = session,
                timeFromMs = at,
                timeToMs = at + 60_000,
                title = title,
                summary = "工具成功 2 次 · 失败 0 次",
                evidence = DiaryEvidence.OBSERVED,
                okSteps = 2,
                createdAt = at,
            ),
        )
    }

    /** 技能变更账：与 [SkillAudit.record] 同一 JSON 形状（不调它——那要真技能文件）。 */
    private fun appendLedgerLine(name: String, action: String, reason: String, atMs: Long) {
        val dir = File(ws, "技能")
        dir.mkdirs()
        val row = JSONObject()
            .put("ts", atMs)
            .put("scope", "project")
            .put("name", name)
            .put("action", action)
            .put("reason", reason)
            .put("beforeSha", "-")
            .put("afterSha", "abc123def456")
            .put("indexUpdated", true)
        File(dir, SkillAudit.FILE_NAME).appendText(row.toString() + "\n", Charsets.UTF_8)
    }

    private fun appendProduct(name: String) {
        val dir = File(ws, "产物")
        dir.mkdirs()
        File(dir, name).writeText("正文", Charsets.UTF_8)
        val ledger = File(dir, "台账.md")
        ledger.appendText("- ☐ $name（2 字）\n", Charsets.UTF_8)
    }

    /** 失败轨迹行（JVM 无库——喂给判据层的是同形状的数据）。 */
    private fun failure(kind: String, atMs: Long, runId: String, seq: Int): ReflectionFailureRow =
        ReflectionFailureRow(kind = kind, atMs = atMs, runId = runId, seq = seq, fromTrace = true)

    /** 直接构造判据层输入（负例②要「文件不在盘上」的假证据，装配层做不到）。 */
    private fun rawInputs(
        diary: List<DiaryEntry> = emptyList(),
        diaryFiles: Map<String, File> = emptyMap(),
        skillLedger: List<SkillAuditLine> = emptyList(),
        productFiles: List<File> = emptyList(),
        productLedgerText: String? = null,
        failures: List<ReflectionFailureRow> = emptyList(),
        skillDirs: List<SkillDirRef> = emptyList(),
    ): ReflectionInputs =
        ReflectionInputs(
            nowMs = now,
            wsRoot = ws,
            globalRoot = global,
            diary = diary,
            diaryFiles = diaryFiles,
            diaryRootExists = File(ws, "记忆/日记").isDirectory,
            skillLedger = skillLedger,
            productLedgerFile = File(ws, "产物/台账.md"),
            productLedgerText = productLedgerText,
            productFiles = productFiles,
            failures = failures,
            skillDirs = skillDirs,
        )

    /** 负例③的门：工作区＋全局两棵树的逐文件摘要（产物/进化档案.md 除外——它是合法产出）。 */
    private fun treeDigest(root: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (rel.endsWith("进化档案.md")) return@forEach
            out[rel] = SkillAudit.shaOf(f)
        }
        return out
    }

    // ── 正例 ────────────────────────────────────────────────────────────────

    @Test
    fun `正例_三天假日记加重复写技能痕迹_出卡带证据且档案多一行`() {
        setUp()
        // 3 天日记：两天同一诉求（「微头条写稿」）——形状①的日记半边
        writeDiary("2026-10-23", "微头条写稿", 1, 10)
        writeDiary("2026-10-24", "微头条写稿", 2, 11)
        writeDiary("2026-10-25", "查资料整理", 3, 9)
        // 同名技能同理由改了 2 次——形状①的变更账半边
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 24, 10))
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 25, 9))
        // 同赛道产物 3 篇跨 3 天——形状①的产物半边
        appendProduct("20261023_微头条_1_a.md")
        appendProduct("20261024_微头条_2_b.md")
        appendProduct("20261025_微头条_3_c.md")

        val r = runner()
        val scan = r.scan()
        val cards = r.present(scan)

        assertTrue("有源 ⇒ 至少一条候选", cards.isNotEmpty())
        assertTrue("≤3 条，宁少不滥", cards.size <= 3)
        cards.forEach { c ->
            assertTrue("每条 ≥2 条证据（${c.target}）", c.evidences.size >= 2)
            assertTrue("证据条条可点开（${c.target}）", c.evidences.all { it.openable })
            assertTrue("证据带点开锚（路径＋行号/日期）", c.evidences.all { it.path.isNotBlank() && it.anchor.isNotBlank() })
        }
        // 进化档案：每张卡恰一行「待定」
        val archive = ReflectionRunner.archiveFile(ws)
        assertTrue(archive.isFile)
        val lines = ReflectionArchive.parse(archive.readText(Charsets.UTF_8))
        assertEquals(cards.size, lines.size)
        assertTrue(lines.all { it.decision == ReflectionArchive.DECISION_PENDING })
        // 卡的六字段齐
        cards.forEach { c ->
            assertTrue(c.claim.isNotBlank() && c.benefit.isNotBlank() && c.risk.isNotBlank() && c.landing.isNotBlank())
        }
    }

    @Test
    fun `正例_四源可得性表_有货有空都写明`() {
        setUp()
        writeDiary("2026-10-25", "查资料整理", 3, 9)
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 25, 9))
        val scan = runner().scan()
        assertEquals(4, scan.availability.size)
        assertEquals(ReflectionSourceName.DIARY, scan.availability[0].name)
        assertEquals(ReflectionSourceLevel.OK, scan.availability[0].level)
        assertEquals(ReflectionSourceLevel.OK, scan.availability[1].level)
        assertEquals(ReflectionSourceLevel.EMPTY, scan.availability[2].level)
        assertEquals(ReflectionSourceLevel.EMPTY, scan.availability[3].level)
    }

    // ── 负例①：无证据不出卡（痕迹不足 ⇒ 该形状无卡；有源无卡 ⇒ 只记日志）────────

    @Test
    fun `负例1_单条痕迹凑不出两条证据_不出卡`() {
        setUp()
        // 变更账只有一行（同名同理由只出现 1 次，也到不了累计 3 次）
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 25, 9))
        val scan = runner().scan()
        assertFalse(scan.allEmpty) // 有源
        assertTrue("证据不足 ⇒ 无卡", scan.candidates.isEmpty())
        val cards = runner().present(scan)
        assertTrue(cards.isEmpty())
        assertFalse("无卡 ⇒ 不写进化档案", ReflectionRunner.archiveFile(ws).isFile)
        assertTrue(logs.any { it.contains("本轮无候选") })
    }

    // ── 负例②：证据不可点开 ⇒ 卡不出（点开是生成时点上的事实，不是承诺）──────────

    @Test
    fun `负例2_证据指向不存在的文件_卡不出`() {
        setUp()
        val e1 = DiaryEntry(
            id = "a", date = "2026-10-24", sessionId = 1,
            timeFromMs = 0, timeToMs = 1, title = "微头条写稿", summary = "s",
            evidence = DiaryEvidence.OBSERVED,
        )
        val e2 = e1.copy(id = "b", date = "2026-10-25")
        // 日记条目在、但文件路径指向不存在的盘上位置（模拟「账说有、盘上没有」）
        val inputs = rawInputs(
            diary = listOf(e1, e2),
            diaryFiles = mapOf(
                "2026-10-24" to File(ws, "记忆/日记/2026-10-24.md"),
                "2026-10-25" to File(ws, "记忆/日记/2026-10-25.md"),
            ),
        )
        val scan = ReflectionScanRules.scan(inputs, archiveText = null)
        assertTrue("证据点不开 ⇒ 这张卡不许出", scan.candidates.isEmpty())
        assertFalse(scan.allEmpty)
    }

    // ── 负例③：确认前零写盘（技能目录/索引/人设/日记逐字节不变；只多进化档案）──────

    @Test
    fun `负例3_扫描与出卡后_技能索引人设日记逐字节不变`() {
        setUp()
        writeDiary("2026-10-24", "微头条写稿", 1, 10)
        writeDiary("2026-10-25", "微头条写稿", 2, 11)
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 24, 10))
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 25, 9))
        appendProduct("20261024_微头条_1_a.md")
        appendProduct("20261025_微头条_2_b.md")
        appendProduct("20261023_微头条_3_c.md")

        val r = runner()
        val before = treeDigest(ws) + treeDigest(global)
        val scan = r.scan()
        assertEquals("扫描零写盘", before, treeDigest(ws) + treeDigest(global))
        val cards = r.present(scan)
        assertTrue(cards.isNotEmpty())
        val after = treeDigest(ws) + treeDigest(global)
        assertEquals("出卡后除进化档案外一个字节不变", before, after)
        assertTrue("进化档案确实多了（这是产出，不是偷写）", ReflectionRunner.archiveFile(ws).isFile)
    }

    // ── 负例④：同技能同改法 7 天内不重复提（键写死 target::改法）──────────────────

    @Test
    fun `负例4_七天内重复提_第二轮同键被压`() {
        setUp()
        writeDiary("2026-10-24", "微头条写稿", 1, 10)
        writeDiary("2026-10-25", "微头条写稿", 2, 11)
        val r = runner()
        val first = r.scanAndPresent().second
        assertTrue(first.isNotEmpty())
        val archiveLinesAfterFirst = ReflectionArchive.parse(r.archiveText())
        assertEquals(first.size, archiveLinesAfterFirst.size)

        val second = r.scanAndPresent().second
        assertTrue("7 天内同键不重提 ⇒ 第二轮无卡", second.isEmpty())
        assertEquals("无卡 ⇒ 档案不再多行", archiveLinesAfterFirst.size, ReflectionArchive.parse(r.archiveText()).size)
    }

    // ── 负例⑤：四源全空 ⇒ 只记一行日志、不弹卡、不写档案 ────────────────────────

    @Test
    fun `负例5_四源全空_只记日志不弹卡`() {
        setUp()
        val r = runner()
        val scan = r.scan()
        assertTrue(scan.allEmpty)
        val cards = r.present(scan)
        assertTrue(cards.isEmpty())
        assertFalse("全空 ⇒ 不写进化档案", ReflectionRunner.archiveFile(ws).isFile)
        assertEquals("恰好一行无候选日志", 1, logs.size)
        assertTrue(logs[0].contains("本轮无候选"))
        assertTrue("空源清单写进日志", logs[0].contains(ReflectionSourceName.DIARY.name))
    }

    // ── 负例⑥：日记空 ⇒ 标 DIARY_EMPTY 降级走兜底（不许直接说「无候选」了事）──────

    @Test
    fun `负例6_日记空_标DIARY_EMPTY且兜底三源仍出卡`() {
        setUp()
        // 不写任何日记；变更账有重复痕迹 ⇒ 兜底要接住
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 24, 10))
        appendLedgerLine("微头条排版", "update", "补排版规则", ts(2026, 10, 25, 9))
        val scan = runner().scan()
        assertFalse(scan.allEmpty)
        assertTrue("日记空 ⇒ 降级标记", scan.diaryDegraded)
        val diaryRow = scan.availability[0]
        assertEquals(ReflectionSourceLevel.DEGRADED, diaryRow.level)
        assertTrue("说明里写明 DIARY_EMPTY", diaryRow.note.contains(DIARY_EMPTY_MARK))
        assertTrue("兜底接住 ⇒ 仍有卡", scan.candidates.isNotEmpty())
        val cards = runner().present(scan)
        assertTrue(cards.isNotEmpty())
    }

    // ── 负例⑦：自动改技能或索引——源码门禁（本类没有那条路）──────────────────────

    @Test
    fun `负例7_反思器没有改技能改索引的路_源码门禁`() {
        // 写技能/改索引/删文件的路一个都不许有（读口可以：DiaryStore.loadAll、PersonaFiles.read）
        val forbidden = listOf(
            "SkillStore.apply(" to "写技能唯一口是 184/192 的确认通道",
            "SkillStore.archive(" to "软删也是写盘，反思器不碰",
            "applyProposal" to "索引写入走人设层提案，反思器不碰",
            ".delete(" to "反思器不删任何文件",
            ".writeText(" to "正文/人设不许重写（进化档案是 appendText）",
        )
        val src = repoFile("src/main/java/com/example/zhengdao/baopu/ReflectionRunner.kt").readText()
        forbidden.forEach { (needle, why) ->
            assertTrue("IO 壳不许出现 $needle（$why）", !src.contains(needle))
        }
        val rules = repoFile("src/main/java/com/example/zhengdao/baopu/ReflectionRules.kt").readText()
        forbidden.forEach { (needle, why) ->
            assertTrue("判据层不许出现 $needle（$why）", !rules.contains(needle))
        }
    }

    // ── 规则细目：否决 30 天／采纳不压制／同类失败跨天出卡／触发 ─────────────────

    @Test
    fun `规则_否决30天内不再提_采纳过期不压制`() {
        setUp()
        val c = ReflectionCandidate(
            shape = ReflectionShape.REPEAT_LABOR,
            target = "微头条排版",
            changeKind = ReflectionChangeKind.EDIT_SKILL,
            claim = "测试主张",
            evidences = listOf(
                ReflectionEvidence("a", "技能/_变更.jsonl", "L1", ReflectionEvidenceKind.SKILL_LEDGER, true),
                ReflectionEvidence("b", "技能/_变更.jsonl", "L2", ReflectionEvidenceKind.SKILL_LEDGER, true),
            ),
            benefit = "b", risk = "r", landing = "l",
            createdAt = now,
        )
        val today = LocalDate.of(2026, 10, 25)
        val line = { daysAgo: Long, decision: String ->
            ReflectionArchiveLine(
                date = today.minusDays(daysAgo),
                target = "微头条排版",
                changeKind = ReflectionChangeKind.EDIT_SKILL,
                claim = "旧主张",
                evidences = "e",
                decision = decision,
            )
        }
        assertTrue("否决 10 天前 ⇒ 30 天窗内压住", ReflectionDedup.suppressed(c, listOf(line(10, ReflectionArchive.DECISION_REJECTED)), today))
        assertFalse("否决 40 天前 ⇒ 出窗，可再提", ReflectionDedup.suppressed(c, listOf(line(40, ReflectionArchive.DECISION_REJECTED)), today))
        assertFalse("采纳 10 天前 ⇒ 不是否决、过 7 天窗 ⇒ 可再提", ReflectionDedup.suppressed(c, listOf(line(10, ReflectionArchive.DECISION_ADOPTED)), today))
        assertTrue("待定 3 天前 ⇒ 7 天窗内压住（提过就算）", ReflectionDedup.suppressed(c, listOf(line(3, ReflectionArchive.DECISION_PENDING)), today))
    }

    @Test
    fun `规则_同类失败跨两天_出反复踩坑卡`() {
        setUp()
        val failures = listOf(
            failure("NETWORK", ts(2026, 10, 24, 10), "r1@100", 3),
            failure("NETWORK", ts(2026, 10, 24, 11), "r2@200", 4),
            failure("NETWORK", ts(2026, 10, 25, 9), "r3@300", 2),
            failure("TOOL_ERROR", ts(2026, 10, 25, 9), "r3@300", 5), // 只 1 次 ⇒ 不该出卡
        )
        val scan = ReflectionScanRules.scan(rawInputs(failures = failures), archiveText = null)
        val cards = scan.candidates.filter { it.shape == ReflectionShape.REPEAT_FAILURE }
        assertEquals(1, cards.size)
        assertEquals("NETWORK", cards[0].target)
        assertTrue(cards[0].evidences.all { it.openable })
    }

    @Test
    fun `规则_索引悬空与未用技能_合并一张账实不符卡`() {
        setUp()
        // 技能目录有索引行、目录整个不存在（悬空的另一半：目录被手删）；
        // 另一条 active uses=0（未用）
        val persona = PersonaFiles(globalRoot = global, projectRoot = ws)
        val index = persona.projectFile()
        index.parentFile?.mkdirs()
        index.writeText(
            SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "punct3", "残留场景")) + "\n" +
                SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "unused1", "没人用的技能")) + "\n",
            Charsets.UTF_8,
        )
        val skillDir = File(File(ws, "技能"), "unused1")
        skillDir.mkdirs()
        File(skillDir, SkillRules.SKILL_FILE).writeText(
            SkillStore.draftHeader(status = "active", uses = 0) + "\n# unused1\n正文",
            Charsets.UTF_8,
        )
        val r = runner()
        val scan = r.scan()
        val mismatch = scan.candidates.filter { it.shape == ReflectionShape.ACCOUNT_MISMATCH }
        assertEquals(1, mismatch.size)
        assertTrue("两类都得报：悬空（目录全无）与未用", mismatch[0].claim.contains("悬空") && mismatch[0].claim.contains("未用"))
        assertTrue(mismatch[0].evidences.size >= 2)
        assertTrue(mismatch[0].evidences.all { it.openable })
    }

    @Test
    fun `触发_复盘话术与每天一次判据`() {
        assertTrue(ReflectionTrigger.isReviewRequest("帮我复盘一下最近的活"))
        assertTrue(ReflectionTrigger.isReviewRequest("最近有什么该改的"))
        assertFalse(ReflectionTrigger.isReviewRequest("今天天气如何"))
        val today = ts(2026, 10, 25, 8)
        assertFalse("今天跑过 ⇒ 不再跑", ReflectionTrigger.dueToday(now, today))
        assertTrue("昨天跑的 ⇒ 今天该跑", ReflectionTrigger.dueToday(ts(2026, 10, 24, 22), now))
        assertTrue("从没跑过 ⇒ 该跑", ReflectionTrigger.dueToday(0L, now))
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
}
