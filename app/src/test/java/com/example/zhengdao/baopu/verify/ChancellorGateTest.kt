// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 右相验收闸的单测：钉死三态与 fail-closed（判据①）——
//   删掉一条账行 ⇒ 声称完成转 FAIL（判据②）；
//   超时 >800ms ⇒ UNVERIFIABLE 且早停不阻塞（判据③）；
//   紧急卡走本闸 ⇒ 红（判据④）。
// 防止以后为了少报红把判据改松：UNVERIFIABLE 永远不许被当成 PASS。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChancellorGateTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd-chancellor").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private fun write(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, StandardCharsets.UTF_8)
        return f
    }

    private fun writeRow(rel: String) = FileOpEntry(
        at = 1_700_000_000_000L,
        sessionId = 1L,
        tool = "write_file",
        kind = FileOpKind.WRITE,
        path = rel,
        purpose = "新建写",
    )

    private fun decision(evidence: String) = DecisionLedger.build(
        DecisionDraft(
            kind = DecisionKind.AUTO_ACT,
            actor = DecisionActor.AUTO,
            reason = "测试样本",
            evidence = evidence,
            before = "无",
            after = "有",
            atMs = 1_700_000_000_000L,
        ),
    )!!

    /** 合法契约文本（三段齐：appliesWhen／success／protected）。 */
    private fun contractBody() = buildString {
        append("# 技能\n")
        append("<!-- contract appliesWhen url_domain=example.com -->\n")
        append("<!-- contract success element_appears=提交按钮 -->\n")
        append("<!-- contract protected 不点支付类按钮 -->\n")
        append("正文。\n")
    }

    private fun submission(
        vararg deliverables: ChancellorDeliverable,
        fileOps: List<FileOpEntry> = emptyList(),
        decisions: List<DecisionRecord> = emptyList(),
        opinions: List<Opinion> = emptyList(),
        urgent: Boolean = false,
    ) = ChancellorSubmission(
        deliverables = deliverables.toList(),
        opinions = opinions,
        ledger = ChancellorLedger(fileOps = fileOps, decisions = decisions),
        root = root,
        urgent = urgent,
    )

    // ── PASS（判据①）────────────────────────────────────────────────────

    @Test
    fun `产物交付_文件在_账行对上_PASS`() {
        write("inbox/rep.md", "第一章")
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/rep.md"),
                fileOps = listOf(writeRow("inbox/rep.md")),
            ),
        )
        assertEquals(ChancellorVerdict.PASS, report.verdict)
        assertTrue(report.findings.isEmpty())
        assertFalse(report.directPass)
        assertTrue(report.notes.any { it.contains("sha256") && it.contains("inbox/rep.md") })
    }

    @Test
    fun `账行记的是绝对路径_也能对上_PASS`() {
        val f = write("inbox/rep.md", "正文")
        val absRow = writeRow("inbox/rep.md").copy(path = f.absolutePath)
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/rep.md"),
                fileOps = listOf(absRow),
            ),
        )
        assertEquals(ChancellorVerdict.PASS, report.verdict)
    }

    @Test
    fun `纯文本交付_直通_PASS_报告写明直通`() {
        val report = ChancellorGate.verify(
            submission(ChancellorDeliverable(ChancellorClaimKind.TEXT_ONLY, "生成一段摘要文本")),
        )
        assertEquals(ChancellorVerdict.PASS, report.verdict)
        assertTrue(report.directPass)
        assertTrue(report.render().contains("直通"))
    }

    // ── FAIL（判据①②④）─────────────────────────────────────────────────

    @Test
    fun `删掉账行_声称完成转_FAIL_且注明未完成`() {
        write("inbox/rep.md", "第一章")
        val report = ChancellorGate.verify(
            submission(ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/rep.md")),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        val finding = report.findings.single()
        assertEquals(ChancellorGate.C2_LEDGER, finding.criterion)
        assertTrue(finding.note.contains("未完成"))
    }

    @Test
    fun `文件不存在_FAIL`() {
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "报告", path = "inbox/没写.md"),
                fileOps = listOf(writeRow("inbox/没写.md")),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertEquals(ChancellorGate.C1_USABLE, report.findings.single().criterion)
    }

    @Test
    fun `空文件_FAIL`() {
        write("inbox/空.md", "")
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "报告", path = "inbox/空.md"),
                fileOps = listOf(writeRow("inbox/空.md")),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertTrue(report.findings.single().note.contains("0 字节"))
    }

    @Test
    fun `路径越界_FAIL_不是判不了`() {
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "报告", path = "../越界.md"),
                fileOps = listOf(writeRow("../越界.md")),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertEquals(ChancellorGate.C1_USABLE, report.findings.single().criterion)
    }

    @Test
    fun `技能交付_契约缺失_FAIL`() {
        write("技能/x/SKILL.md", "正文，没有契约行")
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.SKILL, "技能交付",
                    path = "技能/x/SKILL.md", contractText = "正文，没有契约行",
                ),
                fileOps = listOf(writeRow("技能/x/SKILL.md")),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertEquals(ChancellorGate.C3_CONTRACT, report.findings.single().criterion)
    }

    @Test
    fun `技能交付_契约类型不认_FAIL`() {
        write("技能/x/SKILL.md", "正文")
        val bad = "<!-- contract appliesWhen browser=chrome -->\n正文"
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.SKILL, "技能交付",
                    path = "技能/x/SKILL.md", contractText = bad,
                ),
                fileOps = listOf(writeRow("技能/x/SKILL.md")),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertTrue(report.findings.single().note.contains("解析不过"))
    }

    @Test
    fun `技能交付_契约不齐_缺success_FAIL`() {
        write("技能/x/SKILL.md", "正文")
        val partial = "<!-- contract appliesWhen url_domain=example.com -->\n正文"
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.SKILL, "技能交付",
                    path = "技能/x/SKILL.md", contractText = partial,
                ),
                fileOps = listOf(writeRow("技能/x/SKILL.md")),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertTrue(report.findings.single().note.contains("不齐"))
    }

    @Test
    fun `紧急卡走本闸_红_不许自动批`() {
        write("inbox/rep.md", "第一章")
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/rep.md"),
                fileOps = listOf(writeRow("inbox/rep.md")),
                urgent = true,
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertEquals(ChancellorGate.C0_URGENT, report.findings.single().criterion)
        assertTrue(report.render().contains("人批"))
    }

    @Test
    fun `四器非建议级异议_一票打回_FAIL`() {
        write("inbox/rep.md", "第一章")
        val opinion = Opinion(
            source = Source.CLASSIFIER,
            severity = Severity.MAJOR,
            gist = "越界删档",
            action = ActionCode.REVIEW,
            lawRef = LawRef.L6,
            evidence = listOf("file_op:1"),
        )
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/rep.md"),
                fileOps = listOf(writeRow("inbox/rep.md")),
                opinions = listOf(opinion),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertEquals(ChancellorGate.C5_OPINION, report.findings.single().criterion)
    }

    @Test
    fun `建议级意见_不拦_PASS`() {
        write("inbox/rep.md", "第一章")
        val minor = Opinion(
            source = Source.PATROL,
            severity = Severity.MINOR,
            gist = "文案偏长",
            action = ActionCode.SUGGEST_PAUSE,
            lawRef = LawRef.NONE,
            evidence = listOf("patrol:1"),
        )
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/rep.md"),
                fileOps = listOf(writeRow("inbox/rep.md")),
                opinions = listOf(minor),
            ),
        )
        assertEquals(ChancellorVerdict.PASS, report.verdict)
    }

    // ── 学习成果（判据④）────────────────────────────────────────────────

    @Test
    fun `学习成果_契约加样本证据_不撞法条_PASS`() {
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.LEARNING, "技能入库：demo/示例",
                    contractText = contractBody(),
                    evidence = listOf("skill:demo/示例"),
                ),
                decisions = listOf(decision("skill:demo/示例")),
            ),
        )
        assertEquals(ChancellorVerdict.PASS, report.verdict)
        assertTrue(report.notes.any { it.contains("对上账") })
    }

    @Test
    fun `学习成果_没带样本证据_UNVERIFIABLE_NO_SAMPLES_不当FAIL`() {
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.LEARNING, "技能入库：demo/示例",
                    contractText = contractBody(),
                ),
            ),
        )
        // 蓝总管 2026-10-31 口径：没有样本可判 ≠ 判了不合格——都报 FAIL 人就不看红了。
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.verdict)
        assertTrue(report.missing.single().contains("NO_SAMPLES"))
        assertTrue(report.missing.single().contains("等 298/304 供样本后自动转 FAIL/PASS"))
    }

    @Test
    fun `学习成果_证据引用对不上账_UNVERIFIABLE_NO_SAMPLES_不当FAIL`() {
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.LEARNING, "技能入库：demo/示例",
                    contractText = contractBody(),
                    evidence = listOf("skill:demo/不存在"),
                ),
                decisions = listOf(decision("skill:别的/技能")),
            ),
        )
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.verdict)
        assertTrue(report.missing.single().contains("NO_SAMPLES"))
    }

    @Test
    fun `学习成果_契约坏_确定FAIL_压过空样本的判不了_缺什么照进报告`() {
        // 契约缺失是「判了不合格」（FAIL）；样本为空是「判不了」（NO_SAMPLES）。
        // 两者同现 ⇒ FAIL 优先，缺什么的行照进报告——坏契约不许被「等样本」盖住。
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.LEARNING, "技能入库：demo/示例",
                    contractText = "正文，没有契约行",
                ),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertTrue(report.findings.single().criterion == ChancellorGate.C4_LEARNING)
        assertTrue(report.missing.single().contains("NO_SAMPLES"))
    }

    @Test
    fun `学习成果_撞法条_FAIL`() {
        val opinion = Opinion(
            source = Source.AUDIT,
            severity = Severity.CRITICAL,
            gist = "撞L6边界",
            action = ActionCode.PROPOSE_REVISION,
            lawRef = LawRef.L6,
            evidence = listOf("audit:1"),
        )
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.LEARNING, "技能入库：demo/示例",
                    contractText = contractBody(),
                    evidence = listOf("skill:demo/示例"),
                ),
                decisions = listOf(decision("skill:demo/示例")),
                opinions = listOf(opinion),
            ),
        )
        assertEquals(ChancellorVerdict.FAIL, report.verdict)
        assertTrue(report.findings.any { it.criterion == ChancellorGate.C4_LEARNING && it.note.contains("撞法条") })
    }

    // ── UNVERIFIABLE（判据①③）──────────────────────────────────────────

    @Test
    fun `缺交付路径_判不了_UNVERIFIABLE_不当通过`() {
        val report = ChancellorGate.verify(
            submission(ChancellorDeliverable(ChancellorClaimKind.FILE, "只有摘要没有路径")),
        )
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.verdict)
        assertTrue(report.missing.isNotEmpty())
        assertFalse(report.render().contains("PASS"))
    }

    @Test
    fun `缺契约文本_判不了_UNVERIFIABLE_不猜`() {
        write("技能/x/SKILL.md", "正文")
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(
                    ChancellorClaimKind.SKILL, "技能交付",
                    path = "技能/x/SKILL.md", contractText = null,
                ),
                fileOps = listOf(writeRow("技能/x/SKILL.md")),
            ),
        )
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.verdict)
        assertTrue(report.missing.single().contains("契约"))
    }

    @Test
    fun `超时_UNVERIFIABLE_早停_不死等_不阻塞`() {
        write("inbox/rep.md", "第一章")
        var calls = 0
        // 假钟：第一跳 0（起表），之后每一跳都 >800ms。
        val report = ChancellorGate.verify(
            submission(ChancellorDeliverable(ChancellorClaimKind.FILE, "报告", path = "inbox/rep.md")),
            now = { if (++calls == 1) 0L else 900_000_000L },
        )
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.verdict)
        // 早停证据：账是空的，真跑下去会是 FAIL(C2)；超时降级就不该带着这条 FAIL 继续算。
        assertTrue(report.findings.isEmpty())
        assertTrue(report.missing.single().contains("超"))
        assertTrue(report.render().contains("UNVERIFIABLE"))
        assertFalse(report.render().contains("PASS"))
    }

    @Test
    fun `超时_判到一半_半程读数保留_终态仍是UNVERIFIABLE`() {
        write("a.md", "甲")
        write("b.md", "乙")
        var calls = 0
        val report = ChancellorGate.verify(
            submission(
                ChancellorDeliverable(ChancellorClaimKind.FILE, "甲", path = "a.md"),
                ChancellorDeliverable(ChancellorClaimKind.FILE, "乙", path = "b.md"),
            ),
            now = { if (++calls <= 3) 0L else 900_000_000L },
        )
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.verdict)
        // 第一件已判出 FAIL（无账行），半程读数保留；第二件没判到 ⇒ 终态降级，不冒充结论。
        assertEquals(1, report.findings.size)
        assertEquals(ChancellorGate.C2_LEDGER, report.findings.single().criterion)
        assertTrue(report.missing.single().contains("超"))
    }

    // ── 报告形状 ─────────────────────────────────────────────────────────

    @Test
    fun `FAIL_报告带判据与证据行`() {
        val report = ChancellorGate.verify(
            submission(ChancellorDeliverable(ChancellorClaimKind.FILE, "交付报告", path = "inbox/不在.md")),
        )
        val text = report.render()
        assertTrue(text.contains("右相验收：FAIL"))
        assertTrue(text.contains("[${ChancellorGate.C1_USABLE}]"))
        assertTrue(text.contains("inbox/不在.md"))
    }
}
