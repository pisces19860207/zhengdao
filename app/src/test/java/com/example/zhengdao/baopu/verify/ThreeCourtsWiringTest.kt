// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十五批 · 三司会审**生产接线**的判据与负例（派工单 325，逐条对应）：
//   ① 四触发各有测试（造条件 ⇒ 会审一次）；② 日常交付不进三司（调用次数 0）；
//   ③ 任一 FAIL ⇒ 结论 FAIL 并交人（UNVERIFIABLE 同罪）；
//   ④ 源级门禁：convene 至少一个非测试调用点（全仓唯一，在 ChancellorWiring），
//     且三个生产调用点（引擎收尾／SkillPromote／公开方法）删了即红。
// 负例（各红）：只写方法没人调／日常也进三司／多数决放行（311 已钉判定层，这里钉接线层
// 不另开口子）／convene 里偷偷执行动作（verify 包源级门禁自动扫到本文件：零写盘零执行）。
// 扫源码的门禁先证「会咬人」（照 ChancellorSourceGateTest 房型）。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.core.RunLog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreeCourtsWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var logRoot: File

    private companion object {
        const val NOW = 1_780_000_000_000L
        const val SESSION = 42L
        const val RUN_START = NOW - 60_000L
    }

    @Before
    fun setUp() {
        FileOpLog.resetForTest()
        ChancellorWiring.resetCourtStateForTest()
        RunLog.resetForTest()
        logRoot = tmp.newFolder("logs-root")
        RunLog.initForTest(logRoot)
        assertTrue("RunLog 没就绪，日志断言会假绿", RunLog.awaitPrepared())
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
        ChancellorWiring.resetCourtStateForTest()
        RunLog.resetForTest()
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 在文件账上记一笔（账行以 sessionId 分账，先切会话身份再记）。 */
    private fun logOp(tool: String, kind: FileOpKind, path: String, at: Long = NOW, session: Long = SESSION) {
        FileOpLog.enterSession(session)
        FileOpLog.record(tool, kind, path, "接线测试", at)
    }

    /** 纯文本交付：右相处直通 PASS。 */
    private fun textOnlySub(): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(ChancellorDeliverable(ChancellorClaimKind.TEXT_ONLY, "交付已完成")),
        opinions = emptyList(),
        ledger = ChancellorLedger(),
    )

    /** 落盘产物但文件不在：右相处 FAIL（C1）。 */
    private fun missingFileSub(): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(
            ChancellorDeliverable(kind = ChancellorClaimKind.FILE, summary = "写了个报告", path = "产物/缺.md"),
        ),
        opinions = emptyList(),
        ledger = ChancellorLedger(),
        root = tmp.root,
    )

    /** 学习成果且零样本零契约：右相处 UNVERIFIABLE（NO_SAMPLES）——判不了也交人。 */
    private fun unverifiableSub(): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(
            ChancellorDeliverable(
                kind = ChancellorClaimKind.LEARNING,
                summary = "学到一条",
                contractText = null,
                evidence = emptyList(),
            ),
        ),
        opinions = emptyList(),
        ledger = ChancellorLedger(),
    )

    private fun runLogText(): String {
        val f = File(File(logRoot, "logs"), "zhengdao-log.txt")
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    // ── 判据①：四个触发，各造条件 ⇒ 会审一次 ─────────────────────────────

    @Test
    fun `触发①_本run动过删移_交付时刻送审一次且账行核对通过`() {
        logOp("delete_path", FileOpKind.DELETE, "产物/旧报告.md")
        logOp("move_path", FileOpKind.MOVE, "产物/移走.md")
        val r = ChancellorWiring.conveneIrreversibleDelivery("r42@x", SESSION, RUN_START, NOW)
        assertNotNull("删/移动作的交付必须进三司", r)
        assertEquals(CourtTrigger.IRREVERSIBLE, r!!.trigger)
        assertEquals("右相直通＋账行核对全过 ⇒ 三司全过", CourtStatus.PASS, r.status)
        assertFalse(r.escalate)
        assertTrue("裁定书要整行进运行日志", runLogText().contains(ChancellorWiring.COURT_REPORT_HEAD))
    }

    @Test
    fun `触发②_同一交付连续两次右相FAIL_第二次按连续FAIL开审_一次FAIL不算`() {
        val fail = ChancellorGate.verify(missingFileSub())
        assertEquals(ChancellorVerdict.FAIL, fail.verdict)
        val first = ChancellorWiring.conveneAtLearningPromotion(tmp.newFile("s1.md"), "project", "技能甲", fail, NOW)
        assertNotNull(first)
        assertEquals("只 FAIL 一次：按学习成果入库触发", CourtTrigger.LEARNING_INGEST, first!!.trigger)
        val second = ChancellorWiring.conveneAtLearningPromotion(tmp.newFile("s2.md"), "project", "技能甲", fail, NOW)
        assertNotNull(second)
        assertEquals("连续第二次 FAIL：按触发②开审", CourtTrigger.REPEAT_FAIL, second!!.trigger)
        val pass = ChancellorGate.verify(textOnlySub())
        val third = ChancellorWiring.conveneAtLearningPromotion(tmp.newFile("s3.md"), "project", "技能甲", pass, NOW)
        assertEquals("PASS 归零：连续计数不作数了", CourtTrigger.LEARNING_INGEST, third!!.trigger)
    }

    @Test
    fun `触发②_连续计数按交付键分账_别的技能不沾光`() {
        val fail = ChancellorGate.verify(missingFileSub())
        ChancellorWiring.conveneAtLearningPromotion(tmp.newFile("a.md"), "project", "技能甲", fail, NOW)
        val other = ChancellorWiring.conveneAtLearningPromotion(tmp.newFile("b.md"), "project", "技能乙", fail, NOW)
        assertEquals("另一技能首 FAIL：还是学习入库触发", CourtTrigger.LEARNING_INGEST, other!!.trigger)
    }

    @Test
    fun `触发③_学习成果入库_入库即送审一次`() {
        val skill = tmp.newFile("技能乙.md")
        skill.writeText("正文", Charsets.UTF_8)
        val pass = ChancellorGate.verify(textOnlySub())
        val r = ChancellorWiring.conveneAtLearningPromotion(skill, "global", "技能乙", pass, NOW)
        assertNotNull("入库必须送审", r)
        assertEquals(CourtTrigger.LEARNING_INGEST, r!!.trigger)
    }

    @Test
    fun `触发④_用户点名_公开方法送审一次`() {
        val r = ChancellorWiring.conveneUserNamed("d-手审", textOnlySub(), emptyList(), NOW)
        assertNotNull("用户点名必须开审", r)
        assertEquals(CourtTrigger.USER_NAMED, r!!.trigger)
        assertEquals(CourtStatus.PASS, r.status)
    }

    // ── 判据②：日常交付不进三司（调用次数 0） ────────────────────────────

    @Test
    fun `日常交付_纯读写收尾_不进三司`() {
        logOp("write_file", FileOpKind.WRITE, "产物/日常.md")
        logOp("edit_file", FileOpKind.WRITE, "产物/日常2.md")
        logOp("read_file", FileOpKind.READ, "资料/素材.md")
        val r = ChancellorWiring.conveneIrreversibleDelivery("r42@x", SESSION, RUN_START, NOW)
        assertNull("普通收尾必须零会审", r)
        assertFalse("运行日志里不得出现会审读数", runLogText().contains(ChancellorWiring.COURT_REPORT_HEAD))
    }

    @Test
    fun `日常交付_别的会话或run之前的删移_不算这一轮的交付`() {
        logOp("delete_path", FileOpKind.DELETE, "产物/别人会话删的.md", at = RUN_START - 1, session = 99L)
        logOp("move_path", FileOpKind.MOVE, "产物/上一轮移的.md", at = RUN_START - 1, session = SESSION)
        assertNull(
            "会话与开跑时刻圈不住的删移，不是本 run 的不可逆交付",
            ChancellorWiring.conveneIrreversibleDelivery("r42@x", SESSION, RUN_START, NOW),
        )
    }

    // ── 判据③：任一 FAIL ⇒ 结论 FAIL 并交人（UNVERIFIABLE 同罪） ─────────

    @Test
    fun `右相FAIL_接线层原样上报_状态FAIL并交人`() {
        val r = ChancellorWiring.conveneUserNamed("d-手审", missingFileSub(), emptyList(), NOW)
        assertNotNull(r)
        assertEquals(CourtStatus.FAIL, r!!.status)
        assertTrue(r.escalate)
        assertTrue(r.render().contains("FAIL（交人）"))
    }

    @Test
    fun `右相UNVERIFIABLE_也交人_不当通过`() {
        val r = ChancellorWiring.conveneUserNamed("d-手审", unverifiableSub(), emptyList(), NOW)
        assertNotNull(r)
        assertEquals(CourtStatus.FAIL, r!!.status)
        assertTrue(r.escalate)
    }

    // ── 判据④：源级门禁（先证会咬人，再扫生产源码） ──────────────────────

    private val mainDir = File("src/main/java/com/example/zhengdao")

    /** 只扫代码行（照 ChancellorSourceGateTest 的手法）。 */
    private fun codeLines(f: File): List<String> = f.readText(Charsets.UTF_8).lineSequence()
        .map { it.trimStart() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("*/") }
        .map { it.substringBefore("//") }
        .toList()

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `门禁_合成违规行会被抓出来`() {
        val hit = codeLines(File("src/main/java/com/example/zhengdao/baopu/verify/ChancellorWiring.kt"))
            .firstOrNull { it.contains("ThreeCourts.convene") }
        assertNotNull("自检基准：现有生产调用点必须能被扫到（扫不到＝门禁没牙）", hit)
    }

    @Test
    fun `源级门禁_convene全仓唯一非测试调用点在ChancellorWiring`() {
        val callers = kotlinFiles(mainDir)
            .map { it to codeLines(it).filter { line -> line.contains("ThreeCourts.convene") } }
            .filter { it.second.isNotEmpty() }
        assertTrue("convene 在生产代码里零调用（零引用即死模块）", callers.isNotEmpty())
        val strays = callers.filterNot {
            it.first.absolutePath.replace('\\', '/').endsWith("baopu/verify/ChancellorWiring.kt")
        }
        assertTrue(
            "convene 的调用点必须收拢在装配层一处：\n${strays.joinToString("\n") { it.first.name }}",
            strays.isEmpty(),
        )
    }

    @Test
    fun `源级门禁_三个生产调用点_删了即红`() {
        val promote = File("src/main/java/com/example/zhengdao/baopu/SkillPromote.kt")
        assertTrue(
            "触发②③的调用点被删：SkillPromote 必须调用 ChancellorWiring.conveneAtLearningPromotion",
            codeLines(promote).any { it.contains("ChancellorWiring.conveneAtLearningPromotion") },
        )
        val engine = File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue(
            "触发①的调用点被删：引擎收尾必须调用 ChancellorWiring.conveneIrreversibleDelivery",
            codeLines(engine).any { it.contains("ChancellorWiring.conveneIrreversibleDelivery") },
        )
        val wiring = File("src/main/java/com/example/zhengdao/baopu/verify/ChancellorWiring.kt")
        assertTrue(
            "触发④的公开方法被删：conveneUserNamed 是 322 治理面板的唯一入口",
            codeLines(wiring).any { it.contains("fun conveneUserNamed") },
        )
    }

    // ── 集成：真调用链（SkillPromote.auditPromote ⇒ 会审进日志） ──────────

    @Test
    fun `集成_技能转正真调用链_右相与三司两行读数都进日志`() {
        val f = tmp.newFile("技能丙.md")
        f.writeText("正文", Charsets.UTF_8)
        assertTrue(SkillPromote.auditPromote(f, "global", "技能丙", SkillPromote.REASON_MANUAL, nowMs = NOW))
        val log = runLogText()
        assertTrue("右相读数照旧", log.contains(ChancellorWiring.REPORT_HEAD))
        assertTrue("三司读数必须跟着入库进日志", log.contains(ChancellorWiring.COURT_REPORT_HEAD))
    }
}
