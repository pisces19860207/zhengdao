// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十六批 · 进化档案的**闭合**判据（派工单 §3 硬要求 1＋负例⑤⑥⑦逐条红）。
//
// 这一层只回答一个问题：**反思能不能连着上一次**。所以用例的形状是
// 「三问齐不齐」「旧账接没接」「没批算不算不同意」，不是"档案写得好看吗"。
//
// 红＝被闸挡住：
//   负例⑤ 只有感想 ⇒ 断言**没落盘**（文件都没创建）；
//   负例⑥ 上一轮承诺这一轮不接 ⇒ 断言**报了违规**；接住了 ⇒ 断言违规为空；
//   负例⑦ 把"人没批"当"不同意" ⇒ 断言"没人应答"既不重摆也不进冷档，
//         只有**明确否决**才进冷档（两态混在一起才是骚扰的来源）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReflectionCommitmentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private val logs = mutableListOf<String>()

    /** 钟钉在 2026-10-27 12:00：档案只有日期粒度，判据按天算，钟必须钉死。 */
    private val today: LocalDate = LocalDate.of(2026, 10, 27)
    private val nowMs: Long = LocalDateTime.of(2026, 10, 27, 12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val key = "整理台账::技能补示例"

    @Before
    fun setUp() {
        val ws = tmp.newFolder("ws")
        file = CommitmentStore.fileOf(ws)
        logs.clear()
    }

    private fun store(): CommitmentStore = CommitmentStore(file, { logs += it }, { nowMs })

    /** 直接落一行"历史"（多日场景不走钟——钟钉着，日期要按行给）。 */
    private fun row(
        day: Int,
        k: String = key,
        stage: CommitmentStage,
        verdict: String,
        evidence: String = "",
        tier: String = "A",
    ): Boolean = store().append(
        CommitmentLine(today.withDayOfMonth(day), k, stage, verdict, evidence, tier = tier),
    )

    private fun text(): String = if (file.isFile) file.readText(Charsets.UTF_8) else ""

    // ── 正例：三问齐＋和 205 同键形 ──────────────────────────────────────────

    @Test
    fun `正例_承诺到有没有变好_三问齐了才算闭合`() {
        val s = store()
        val shape = PromiseShape(
            key = key,
            target = "技能/整理台账/SKILL.md",
            verifyBy = "206 对拍：台账样例用例 c-tidy-01",
            action = "补一条示例（输入「补 10 月」⇒ 只补当月）",
        )
        val findings = s.promise(shape, "A")
        assertEquals("可执行的承诺不该被拒：$findings", emptyList<String>(), findings)
        assertTrue(s.noteResult(key, CommitmentResult.DONE, "已落盘 技能/整理台账/SKILL.md", "A").isEmpty())
        assertTrue(s.noteOutcome(key, CommitmentOutcome.BETTER, "对拍 ADOPT｜产物清单一致"))

        val books = s.read()
        assertEquals(3, CommitmentArchive.rowCount(text()))
        assertEquals(1, books.groupBy { it.key }.size)
        val book = ClosureRules.books(books).getValue(key)
        assertTrue("三问齐了还判没闭合：${ClosureTell.closureState(book)}", ClosureRules.closed(book))
        assertEquals("闭合：改成＋变好", ClosureTell.closureState(book))
        assertTrue("闭合了还挂在欠账里", s.openBooks().isEmpty())
        assertTrue("接住了旧账还报违规", s.followUpFindings(setOf(key), emptySet()).isEmpty())
    }

    @Test
    fun `正例_承诺键与205去重键同形_下一轮才对得上账`() {
        // 205 的键写死在 `ReflectionCandidate.dedupKey`（`落点::改法label`）。本层不许另造
        // 第二种拼法：档案里的账要对上"反思器不再重复提的那条"，否则两套键迟早分叉。
        val candidate = ReflectionCandidate(
            shape = ReflectionShape.REPEAT_LABOR,
            target = "整理台账",
            changeKind = ReflectionChangeKind.EDIT_SKILL,
            claim = "补一条示例",
            evidences = listOf(
                ReflectionEvidence("会话痕迹", "记忆/日记/2026-10-26.md", "L3", ReflectionEvidenceKind.DIARY, true),
            ),
            benefit = "少重跑一次",
            risk = "无",
            landing = "技能/整理台账/SKILL.md",
            createdAt = nowMs,
        )
        assertEquals("整理台账::改技能", candidate.dedupKey)
        // 反思器把 205 的键原样带进来（`candidateKey`），档案与去重就同一个键
        val carried = AutonomyProposal(
            kind = AutonomyChangeKind.SKILL_EXAMPLE_ADD,
            target = candidate.target,
            currentText = "旧正文",
            newText = "新正文",
            candidateKey = candidate.dedupKey,
        )
        assertEquals(candidate.dedupKey, carried.dedupKey)
        // 没有 205 的键时，本层自己按**同一形状**拼（不许换成别的分隔符或顺序）
        val own = AutonomyProposal(
            kind = AutonomyChangeKind.SKILL_EXAMPLE_ADD,
            target = "整理台账",
            currentText = "旧正文",
            newText = "新正文",
        )
        assertEquals("整理台账::${AutonomyChangeKind.SKILL_EXAMPLE_ADD.label}", own.dedupKey)
        assertEquals(own.dedupKey.split("::").size, candidate.dedupKey.split("::").size)
    }

    @Test
    fun `正例_没验的账不算闭合_自治不等于免检`() {
        val s = store()
        assertTrue(s.promise(PromiseShape(key, "技能/整理台账/SKILL.md", "206 对拍", "补一条示例"), "A").isEmpty())
        assertTrue(s.noteResult(key, CommitmentResult.DONE, "已落盘", "A").isEmpty())
        // 只有"改了没"、没有"有没有变好"⇒ 欠验；连"没验"这一态都得照记，否则假装闭合
        assertTrue(s.noteOutcome(key, CommitmentOutcome.UNCHECKED, "对拍没跑成：当日额度已满"))
        val book = ClosureRules.books(s.read()).getValue(key)
        assertFalse("没验的账被判闭合了", ClosureRules.closed(book))
        assertEquals("改了，但没验（自治≠免检）", ClosureTell.closureState(book))
        assertEquals(1, s.openBooks().size)
    }

    // ── 负例⑤：只有感想 ⇒ 不落盘 ────────────────────────────────────────────

    @Test
    fun `负例5_只有感想没有可执行项_一个字节都不写`() {
        val vague = PromiseShape(key, "技能/整理台账/SKILL.md", "", "以后要注意细节")
        val findings = store().promise(vague, "A")
        assertTrue("感想被当承诺记进档案了（负例⑤）", findings.isNotEmpty())
        assertTrue("要说清缺的是验法：$findings", findings.any { it.contains("验法") })
        assertFalse("拒收还建了文件", file.isFile)
        assertEquals(0, CommitmentArchive.rowCount(text()))
    }

    @Test
    fun `负例5_有验法但动作是感想_照样不收`() {
        // 有具体对象（带路径）才救得回"提升"这类词；光有验法不够——动作还是一句口号
        val findings = store().promise(PromiseShape(key, "技能/整理台账/SKILL.md", "206 对拍", "要注意"), "A")
        assertTrue("空泛动作收了：$findings", findings.any { it.contains("感想") || it.contains("落点") })
        assertFalse(file.isFile)
    }

    @Test
    fun `负例5_缺落点或缺键_也收不进`() {
        assertTrue(store().promise(PromiseShape(key, "", "206 对拍", "补一条示例"), "A").any { it.contains("落点") })
        assertTrue(store().promise(PromiseShape("", "技能/整理台账/SKILL.md", "206 对拍", "补一条示例"), "A")
            .any { it.contains("承诺键") })
        assertFalse(file.isFile)
    }

    // ── 负例⑥：上一轮的账这一轮必须接 ───────────────────────────────────────

    @Test
    fun `负例6_第二次反思不读第一次_报违规`() {
        val s = store()
        assertTrue(s.promise(PromiseShape(key, "技能/整理台账/SKILL.md", "206 对拍", "补一条示例"), "A").isEmpty())
        // 第二轮只出新东西，旧账一条不接
        val missed = s.followUpFindings(setOf("查物流::新增技能"), emptySet())
        assertEquals("旧账没接却没报违规（负例⑥）：$missed", 1, missed.size)
        assertTrue(missed.first().contains(key))
        // 接住了 ⇒ 没违规（同一个函数两种答案，才说明它真在比对）
        assertTrue(s.followUpFindings(setOf(key), emptySet()).isEmpty())
        assertTrue(s.followUpFindings(emptySet(), setOf(key)).isEmpty())
    }

    @Test
    fun `负例6_放弃必须写为什么_没写就还欠着`() {
        val s = store()
        assertTrue(s.promise(PromiseShape(key, "技能/整理台账/SKILL.md", "206 对拍", "补一条示例"), "A").isEmpty())
        val rejected = s.noteResult(key, CommitmentResult.ABANDONED, "", "A")
        assertTrue("无理由的放弃落了盘：$rejected", rejected.isNotEmpty())
        assertEquals("拒收不该写盘", 1, CommitmentArchive.rowCount(text()))
        assertTrue(s.noteResult(key, CommitmentResult.ABANDONED, "该技能已被 226 的录制路径取代，示例没有读者", "A")
            .isEmpty())
        val book = ClosureRules.books(s.read()).getValue(key)
        assertTrue("放弃＋理由应当算闭合", ClosureRules.closed(book))
        assertEquals("放弃（已闭合）", ClosureTell.closureState(book))
        assertTrue("放弃（带理由）后这条账还挂在欠账里", s.openBooks().isEmpty())
    }

    @Test
    fun `负例6_手改的无理由放弃_由abandonedWithoutReason露出`() {
        row(20, stage = CommitmentStage.PROMISE, verdict = "补一条示例", evidence = "206 对拍")
        row(25, stage = CommitmentStage.RESULT, verdict = CommitmentResult.ABANDONED.wire) // 依据空着
        assertEquals("空理由的放弃没被点名", listOf(key), ClosureRules.abandonedWithoutReason(store().read()))
        assertTrue(ClosureTell.debtLine(ClosureRules.books(store().read()).getValue(key)).contains("放弃，没写为什么"))
    }

    @Test
    fun `正例_欠账挂够7天才催_3天不算逾期`() {
        row(20, stage = CommitmentStage.PROMISE, verdict = "补一条示例", evidence = "206 对拍")
        val s = store()
        assertEquals(1, s.openBooks().size)
        // 10-20 到 10-27 整 7 天 ⇒ 该正面回答"接着改还是放弃"
        assertEquals(listOf(key), ClosureRules.overdue(s.read(), today).map { it.key })
        row(24, k = "查物流::改措辞", stage = CommitmentStage.PROMISE, verdict = "改一句措辞", evidence = "206 对拍")
        val open = ClosureRules.openBooks(s.read()).map { it.key }.sorted()
        assertEquals("只挂 3 天的也被催了", listOf(key, "查物流::改措辞"), open)
        assertEquals(listOf(key), ClosureRules.overdue(s.read(), today).map { it.key })
    }

    // ── 负例⑦：人没批 ≠ 人不同意 ────────────────────────────────────────────

    @Test
    fun `负例7_没人应答不重摆也不进冷档_页面上只留一行`() {
        val s = store()
        assertTrue(s.noteAsk(key, CommitmentAsk.UNANSWERED))
        assertFalse("同键又追加了一行（＝骚扰一次）", s.noteAsk(key, CommitmentAsk.UNANSWERED))
        assertEquals(1, s.read().count { it.stage == CommitmentStage.ASKED })
        val book = ClosureRules.books(s.read()).getValue(key)
        assertFalse("没人应答却被判成该再摆卡", ClosureRules.shouldAskAgain(book, today))
        // 关键一句：没批**不是**不同意。混成同态才会"反复骚扰"或"永远不再提"。
        assertFalse("把没批当成了不同意", ClosureRules.treatUnansweredAsDisagreement(book))
        assertTrue("没人应答竟进了冷档", ClosureRules.cooledOut(s.read(), today).isEmpty())
        // 人哪天点了同意，就照办（静默等待 ≠ 永久否决；应答是**记录**，不受骚扰闸拦）
        assertTrue("人的应答被骚扰闸拦掉了", s.noteAsk(key, CommitmentAsk.AGREED))
        assertEquals(2, s.read().count { it.stage == CommitmentStage.ASKED })
        assertTrue(ClosureRules.shouldAskAgain(ClosureRules.books(s.read()).getValue(key), today))
    }

    @Test
    fun `负例7_明确否决才进冷档_30天窗过期后再提`() {
        val s = store()
        row(20, stage = CommitmentStage.ASKED, verdict = CommitmentAsk.DISAGREED.wire, tier = "B")
        val book = ClosureRules.books(s.read()).getValue(key)
        assertTrue("否决没被当成否决", ClosureRules.treatUnansweredAsDisagreement(book))
        assertEquals("否决 7 天就被催第二次", listOf(key), ClosureRules.cooledOut(s.read(), today))
        assertFalse(ClosureRules.shouldAskAgain(book, today))
        // 冷档窗从**摆卡那天**算，不从承诺那天算
        val expired = CommitmentLine(
            today.minusDays(ClosureRules.DISAGREE_COOLDOWN_DAYS + 1),
            "过期技能::改契约",
            CommitmentStage.ASKED,
            CommitmentAsk.DISAGREED.wire,
        )
        val all = s.read() + expired
        val out = ClosureRules.books(all).getValue("过期技能::改契约")
        assertTrue("冷档过期还压着", ClosureRules.shouldAskAgain(out, today))
        assertFalse(ClosureRules.cooledOut(all, today).contains("过期技能::改契约"))
        assertEquals(30L, ClosureRules.DISAGREE_COOLDOWN_DAYS)
        // 冷档天数**沿用 205 的那个数**，不是本层另立（两个数迟早分叉）
        assertEquals(ReflectionDedup.REJECT_WINDOW_DAYS, ClosureRules.DISAGREE_COOLDOWN_DAYS)
    }

    @Test
    fun `正例_没问过的键照样能摆卡`() {
        row(25, stage = CommitmentStage.PROMISE, verdict = "补一条示例", evidence = "206 对拍")
        val s = store()
        val book = ClosureRules.books(s.read()).getValue(key)
        assertTrue(ClosureRules.shouldAskAgain(book, today))
        assertTrue("从没问过却被判成不能再问", ClosureRules.shouldAppendAskRow(s.read(), key))
        assertTrue(s.noteAsk(key, CommitmentAsk.UNANSWERED))
        assertFalse(ClosureRules.shouldAppendAskRow(store().read(), key))
    }

    // ── 档案本身读写（坏行不猜；不碰 205 那张表）─────────────────────────────

    @Test
    fun `正例_表格往返_列里的竖线换成全角不影响读`() {
        val s = store()
        assertTrue(s.append(CommitmentLine(today, key, CommitmentStage.PROMISE, "补示例 | 两条", "206 | 对拍", "落点：a|b", "A")))
        val line = s.read().single()
        assertEquals("补示例 ／ 两条", line.verdict)
        assertEquals("206 ／ 对拍", line.evidence)
        assertEquals("落点：a／b", line.note)
        assertEquals(0, CommitmentArchive.unreadableCount(text()))
        assertTrue(CommitmentArchive.hasHeader(text()))
    }

    @Test
    fun `正例_认不出的行整行丢_但数量要露出来`() {
        file.parentFile!!.mkdirs()
        file.writeText(
            CommitmentArchive.header() + "\n" +
                "| 2026-10-27 | $key | 承诺 | 补示例 |  | A | 落点：x |\n" +
                "| 2026-10-27 | $key | 瞎写的段 | 没这个结论 |  | A | 落点：x |\n" +
                "| 不是日期 | $key | 承诺 | 补示例 |  | A | 落点：x |\n",
            Charsets.UTF_8,
        )
        assertEquals(1, CommitmentArchive.rowCount(text()))
        assertEquals("坏行静默丢就是档案说假话", 2, CommitmentArchive.unreadableCount(text()))
    }

    @Test
    fun `正例_另开一个文件_不动205那张6列表`() {
        // 派工单 §3 要求档案闭合；205 的 parse 按位置取 1..6 且 `fields.size < 7` 跳过，
        // 给它加第 7 列**不报错、静默错位**。所以本层另开文件——这条用例钉住这个决定。
        assertEquals("进化档案.md", ReflectionArchive.FILE_NAME)
        assertEquals("进化档案-承诺.md", CommitmentArchive.FILE_NAME)
        assertFalse("两个档案不能是同一个文件", CommitmentArchive.FILE_NAME == ReflectionArchive.FILE_NAME)
        assertEquals(6, ReflectionArchive.header().lines()[1].count { it == '|' } - 1)
        assertEquals(7, CommitmentArchive.header().lines()[1].count { it == '|' } - 1)
        // 两张表互读不成：把承诺表喂给 205 的 parse（或反之）都得是空——
        // "加一列复用旧表"不是省事，是**静默错位**（205 按位置取 1..6）。
        assertEquals(0, ReflectionArchive.parse(CommitmentArchive.header() + "\n").size)
        assertEquals(0, CommitmentArchive.parse(ReflectionArchive.header() + "\n").size)
    }

    @Test
    fun `正例_落盘失败只记日志_不抛`() {
        // 落点是个已存在的**目录**：appendText 必失败，走的正是 runCatching 那条路
        val dir = tmp.newFolder("as-file")
        val s = CommitmentStore(dir, { logs += it }, { nowMs })
        val findings = s.promise(PromiseShape(key, "技能/整理台账/SKILL.md", "206 对拍", "补一条示例"), "A")
        assertTrue("写不进去却说成了：$findings", findings.isNotEmpty())
        assertTrue("得留一句日志：$logs", logs.any { it.contains("承诺档案") })
    }

    @Test
    fun `正例_表头只在第一次写_历史行一个字节不改`() {
        val s = store()
        assertTrue(s.append(CommitmentLine(today, key, CommitmentStage.PROMISE, "补一条示例", "206 对拍", tier = "A")))
        val afterFirst = text()
        assertTrue(s.append(CommitmentLine(today, key, CommitmentStage.RESULT, CommitmentResult.DONE.wire, "已落盘", tier = "A")))
        val afterSecond = text()
        // append-only：新文件=表头+1 行；第二次只在末尾追加，前缀逐字节相同
        assertTrue(afterFirst.startsWith(CommitmentArchive.header()))
        assertEquals(1, afterSecond.lines().count { it.startsWith("# 进化档案·承诺") })
        assertTrue("历史行被改写了（不是 append-only）", afterSecond.startsWith(afterFirst))
        assertEquals(2, CommitmentArchive.rowCount(afterSecond))
    }
}
