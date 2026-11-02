// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十一批的行为门禁：影子**重做基底**（工单判据①）与两处灾异事件的**触发**（判据②）。
//
// ## 判据①（双盲重做：只比指纹与结论）
//   · 同材同结论 ⇒ CONCUR，finding=null（一致不追责）；
//   · **改坏一个产物**（副本字节与主执行体指纹对不上）⇒ DISAGREEMENT ⇒ L32 认定：
//     提出位自动真（raisedByBlindReview）、御批确认位留给人（confirmedByThrone=false）
//     ⇒ decide 判认定无效（findingValid=false、violationRecorded=false——程序不许自定罪）；
//   · 结论不同（产物全同、conclusion 键不同）⇒ 同样 DISAGREEMENT——结论盖进指纹；
//   · 影子没跑成／没有落盘产物 ⇒ 缺位＝没做，**不许读成一致**（review=null）。
//   盲的形状：redo 的输入只有重做事实＋主执行体指纹，过程与自我报告进不了比对。
//
// ## 判据②（两处事件：只发事实、无出处不发、不判决）
//   · INTEGRITY_DAMAGED（账实核对）：影子判 MISMATCH ⇒ 灾异源有 LEDGER_MISMATCH 事件
//     （出处＝影子账行锚 sh:<opId>；日志有 L29 判定行——判决归 AnomalyGate）；
//   · CASCADE_FAILURE：同任务第 3 次 FAIL 且本任务段含不可逆 ⇒ 灾异源发 FAIL_STREAK
//     （机器键 task:/streak:3/irreversible:true）；2 次不发、全可逆不发、跨任务不共池、发过归零。
//
// 为什么 Fake 沙盒的 discard **记账不清盘**：重做的 CONCUR 路径里，判据层说一致就收副本，
// 而产物指纹要从副本里读字节——真沙盒清完就没材料可比了；Fake 把"清"记成计数、不真删，
// 正例还顺手断言"判成一致确实调了清副本"这一格。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.law.AccountabilityKind
import com.example.zhengdao.baopu.law.AccountabilityStanding
import com.example.zhengdao.baopu.verify.BlindReview
import com.example.zhengdao.baopu.verify.BlindReviewOutcome
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShadowRedoTest {

    private val now = 1_793_289_600_000L // 2026-10-30 00:00 +08:00（写死，与 296 同一口径）

    private lateinit var ledger: File
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        AnomalyFeed.resetForTest()
        ledger = Files.createTempDirectory("zd361-ledger-").toFile()
        DecisionLedger.install(ledger)
    }

    @After
    fun tearDown() {
        AnomalyFeed.resetForTest()
        DecisionLedger.uninstallForTest()
        ledger.deleteRecursively()
    }

    private fun workspace(): File = Files.createTempDirectory("zd-361-ws-").toFile()

    /** 判据①要**真的有产物可指纹**：Fake 的 discard 只记账不删盘（见文件头"为什么"）。 */
    private class Fake(private val run: ShadowRun) : ShadowSandbox {
        var discarded = 0

        override fun rehearse(input: ShadowInput): ShadowRun = run

        override fun discard(copyRoot: String) {
            discarded += 1
        }
    }

    private fun input(
        ws: File,
        opId: String = "op-296",
        reversible: Boolean = false,
        tool: String = "write_file",
    ) = ShadowInput(
        opId = opId,
        tool = tool,
        workspaceRoot = ws.absolutePath,
        declaredPaths = listOf("notes/a.md"),
        content = "改后的内容\n",
        overwrite = true,
        risk = RiskClass.R2,
        reversible = reversible,
        sessionId = 7L,
        step = 3,
        wallMs = now,
        ledgerRoot = ledger.absolutePath,
    )

    /** 一份实测：observedPaths 各格可调；trialOk=false 用来喂 CASCADE 口径。 */
    private fun runIn(
        ws: File,
        observedPaths: List<String>,
        trialOk: Boolean = true,
        existed: List<String> = listOf("notes/a.md"),
    ): ShadowRun {
        val copy = "${ws.absolutePath}\\.shadow\\op-296"
        return ShadowRun(
            built = true,
            note = "副本 ${observedPaths.size} 个对象",
            copyRoot = copy,
            existedBefore = existed,
            observedPaths = observedPaths,
            observed = ImpactShape(
                newFiles = (observedPaths.size - existed.size).coerceAtLeast(0),
                overwriteTotal = existed.size.coerceAtMost(observedPaths.size),
                touchedPaths = observedPaths,
            ),
            rollback = RollbackPoint(
                path = "$copy/.rollback/a.bak",
                exists = existed.isNotEmpty(),
                bytes = if (existed.isEmpty()) 0L else 128L,
                originalBytes = if (existed.isEmpty()) null else 128L,
                sha256 = if (existed.isEmpty()) "" else "aaaaaaaa11111111",
                originalSha256 = if (existed.isEmpty()) "" else "aaaaaaaa11111111",
                expectsAbsent = existed.isEmpty(),
            ),
            realUntouched = true,
            landed = observedPaths.isNotEmpty(),
            trialOk = trialOk,
            trialNote = if (trialOk) "已覆盖 a.md（1 行）" else "工具回执 FAIL",
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun executorDigestOf(entries: List<Pair<String, String>>, conclusion: String): String =
        ShadowAdapter.digestOfArtifacts(
            entries.map { ShadowArtifactFingerprint(it.first, it.second) },
            conclusion,
        )

    /** 在副本里落一个真产物（重做的指纹从副本字节来，纸上谈兵不算重做）。 */
    private fun plantArtifact(ws: File, content: String): String {
        val copy = "${ws.absolutePath}\\.shadow\\op-296"
        File(ws, ".shadow/op-296/notes").apply { mkdirs() }
        val f = File(copy, "notes/a.md")
        f.writeText(content, Charsets.UTF_8)
        return f.absolutePath
    }

    private fun failStreaks(): List<AnomalyFactEvent> =
        AnomalyFeed.replay().filter { it.kind == AnomalyFactKind.FAIL_STREAK }

    // ── 判据①：同材同结论 ⇒ CONCUR，一致不追责 ─────────────────────────────────

    @Test
    fun `判据①_同材同结论_CONCUR_产物指纹齐全_finding为null`() {
        val ws = workspace()
        val path = plantArtifact(ws, "改后的内容\n")
        val digest = executorDigestOf(listOf("notes/a.md" to sha256("改后的内容\n".toByteArray())), "consistent")
        val fake = Fake(runIn(ws, listOf(path)))
        val report = ShadowAdapter.redo(input(ws), digest, override = fake, log = logs::add)

        assertEquals(BlindReviewOutcome.CONCUR, report.review?.outcome)
        assertEquals("consistent", report.conclusion)
        assertTrue("影子指纹要过形状关", BlindReview.isDigest(report.shadowDigest))
        assertEquals(digest, report.review?.executorDigest)
        assertEquals(listOf("notes/a.md"), report.artifacts.map { it.path })
        assertEquals(sha256("改后的内容\n".toByteArray()), report.artifacts.single().sha256)
        assertNull("一致不追责：CONCUR 没有认定可提", report.finding)
        assertNull(report.standing)
        assertEquals("判成一致确实调了清副本（Fake 只记账不删盘）", 1, fake.discarded)
    }

    // ── 判据①：改坏一个产物 ⇒ DISAGREEMENT ⇒ L32 认定，御批位留给人 ─────────────

    @Test
    fun `判据①_改坏一个产物_DISAGREEMENT_御批位留给人_未御批不许动权限`() {
        val ws = workspace()
        val path = plantArtifact(ws, "被改坏的产物\n") // 副本里这一份与主执行体对不上
        val digest = executorDigestOf(listOf("notes/a.md" to sha256("改后的内容\n".toByteArray())), "consistent")
        val report = ShadowAdapter.redo(input(ws), digest, override = Fake(runIn(ws, listOf(path))), log = logs::add)

        assertEquals(BlindReviewOutcome.DISAGREEMENT, report.review?.outcome)
        assertEquals("影子报的是自己实测到的产物", sha256("被改坏的产物\n".toByteArray()), report.artifacts.single().sha256)
        val finding = report.finding
        assertNotNull("不一致必须有 L32 认定", finding)
        assertTrue("提出位自动真（影子就是双盲任一方）", finding!!.raisedByBlindReview)
        assertFalse("御批确认位留给人", finding.confirmedByThrone)
        assertEquals(AccountabilityKind.OVER_WHITELIST, finding.kind)
        assertEquals("write_file", finding.category)
        val standing = report.standing
        assertNotNull("认定要过 AccountabilityGate.decide", standing)
        assertFalse("没御批的认定无效（程序不许自定罪）", standing!!.findingValid)
        assertFalse("无效认定连违规都不记", standing.violationRecorded)
        assertFalse(standing.tightened)
        assertEquals(AccountabilityStanding.OPEN, standing.standing)
        assertTrue("日志要说清认定已喂闸：${logs.joinToString("｜")}", logs.joinToString("").contains("L32 认定已喂闸"))
    }

    // ── 判据①：结论不同也 DISAGREEMENT（结论盖进指纹）＋ 缺位＝没做 ─────────────

    @Test
    fun `判据①_产物全同但结论不同_照样DISAGREEMENT`() {
        val ws = workspace()
        val path = plantArtifact(ws, "改后的内容\n")
        val digest = executorDigestOf(listOf("notes/a.md" to sha256("改后的内容\n".toByteArray())), "mismatch")
        val report = ShadowAdapter.redo(input(ws), digest, override = Fake(runIn(ws, listOf(path))), log = logs::add)

        assertEquals(BlindReviewOutcome.DISAGREEMENT, report.review?.outcome)
        assertEquals("影子自己的结论不被主执行体的说法带跑", "consistent", report.conclusion)
        assertNotNull(report.finding)
    }

    @Test
    fun `判据①_影子没跑成_缺位不比_不许读成一致`() {
        val ws = workspace()
        val report = ShadowAdapter.redo(
            input(ws, tool = "unknown_tool"),
            executorDigestOf(listOf("notes/a.md" to sha256("x".toByteArray())), "consistent"),
            override = Fake(runIn(ws, emptyList())),
            log = logs::add,
        )
        assertNull("没跑成就没有比对结论", report.review)
        assertTrue(report.artifacts.isEmpty())
        assertEquals("", report.shadowDigest)
        assertTrue("缺位＝没做：${report.note}", report.note.contains("缺位"))
    }

    @Test
    fun `判据①_没有落盘产物可指纹_不比`() {
        val ws = workspace()
        val report = ShadowAdapter.redo(
            input(ws),
            executorDigestOf(listOf("notes/a.md" to sha256("x".toByteArray())), "consistent"),
            override = Fake(runIn(ws, emptyList())), // 实测落点为空 ⇒ 副本里没有可指纹的东西
            log = logs::add,
        )
        assertNull(report.review)
        assertTrue(report.artifacts.isEmpty())
        assertTrue("没有材料就不比：${report.note}", report.note.contains("没有落盘产物"))
    }

    // ── 判据② INTEGRITY_DAMAGED：MISMATCH ⇒ 灾异源有账实不符事件 ────────────────

    @Test
    fun `判据②_INTEGRITY_DAMAGED_MISMATCH发LEDGER_MISMATCH事件_出处与机器键齐全`() {
        val ws = workspace()
        val copy = "${ws.absolutePath}\\.shadow\\op-296"
        val fake = Fake(
            runIn(ws, listOf("$copy/notes/a.md", "$copy/notes/b.md"), existed = listOf("notes/a.md")),
        )
        val r = ShadowAdapter.rehearse(input(ws), override = fake, log = logs::add)
        assertEquals(ShadowVerdict.MISMATCH, r.outcome.verdict)

        val events = AnomalyFeed.replay().filter { it.kind == AnomalyFactKind.LEDGER_MISMATCH }
        assertEquals("账实核对只发一件事实", 1, events.size)
        val e = events.single()
        assertEquals("出处＝影子账行锚（无出处不发的那一格交灾异源拒）", "sh:op-296", e.provenance)
        assertTrue("机器键带所称：${e.evidence}", e.evidence.any { it.startsWith("claimed:") })
        assertTrue("机器键带实测：${e.evidence}", e.evidence.any { it.startsWith("observed:") })
        assertTrue("机器键带路径差：${e.evidence}", e.evidence.any { it.startsWith("paths:") })
        assertTrue("只发事实不判决：${e.gist}", !e.gist.contains("灾异"))
        assertTrue("日志要有 L29 判定行（判决归判定层）", logs.joinToString("").contains("L29 判定"))
    }

    @Test
    fun `判据②_影子一致_不发任何灾异事件`() {
        val ws = workspace()
        val path = plantArtifact(ws, "改后的内容\n")
        ShadowAdapter.rehearse(input(ws), override = Fake(runIn(ws, listOf(path))), log = logs::add)
        assertTrue("一致就是一致，灾异源不许有动静", AnomalyFeed.replay().isEmpty())
    }

    // ── 判据② CASCADE_FAILURE：同任务第 3 次 FAIL 且含不可逆才发 ─────────────────

    @Test
    fun `判据②_CASCADE_FAILURE_同任务三连FAIL含不可逆_第3次发事件`() {
        val ws = workspace()
        val path = plantArtifact(ws, "改后的内容\n")
        val fake = Fake(runIn(ws, listOf(path), trialOk = false))
        repeat(2) { ShadowAdapter.rehearse(input(ws), override = fake, log = logs::add) }
        assertTrue("同任务 2 次 FAIL 不发", failStreaks().isEmpty())
        ShadowAdapter.rehearse(input(ws), override = fake, log = logs::add)
        val streaks = failStreaks()
        assertEquals("第 3 次到线才发", 1, streaks.size)
        val e = streaks.single()
        assertTrue("机器键盖任务锚：${e.evidence}", e.evidence.contains("task:op-296"))
        assertTrue(e.evidence.contains("streak:3"))
        assertTrue(e.evidence.contains("irreversible:true"))
        assertTrue("日志要有 L29 判定行", logs.joinToString("").contains("L29 判定"))
    }

    @Test
    fun `判据②_三连全可逆不发_跨任务不共池_发过归零`() {
        val ws = workspace()
        val path = plantArtifact(ws, "改后的内容\n")
        // 三连全是可逆动作 ⇒ 口径"含不可逆"不成立，不发
        repeat(3) { ShadowAdapter.rehearse(input(ws, opId = "op-rev", reversible = true), override = Fake(runIn(ws, listOf(path), trialOk = false)), log = logs::add) }
        assertTrue("全可逆的三连不发", failStreaks().isEmpty())
        // 跨任务不共池：A 攒 2 次、B 才 1 次 ⇒ 谁都不到线
        ShadowAdapter.rehearse(input(ws, opId = "op-x1"), override = Fake(runIn(ws, listOf(path), trialOk = false)), log = logs::add)
        ShadowAdapter.rehearse(input(ws, opId = "op-x1"), override = Fake(runIn(ws, listOf(path), trialOk = false)), log = logs::add)
        ShadowAdapter.rehearse(input(ws, opId = "op-x2"), override = Fake(runIn(ws, listOf(path), trialOk = false)), log = logs::add)
        assertTrue("别的任务的成功/失败不替它计数", failStreaks().isEmpty())
        // 发过归零：op-296 三连发一件之后，再来 2 次也只还是那一件
        val fake = Fake(runIn(ws, listOf(path), trialOk = false))
        repeat(5) { ShadowAdapter.rehearse(input(ws), override = fake, log = logs::add) }
        assertEquals("发过即归零（5 次 FAIL 只发第 3 次那一件）", 1, failStreaks().size)
    }
}
