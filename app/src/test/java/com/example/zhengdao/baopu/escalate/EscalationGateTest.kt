// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 475 可靠升级的闸（派工单 §二 ③④⑤ ＋ §三 判据③④⑤）。
//
// 这一层的规矩是"三件事缺一件就不叫升级"，所以测法照这三件事排：
// · **停**：`StopFact` 少了 runId 或怎么收场的一句话 ⇒ 构造期就拦（不是运行时才发现说不清）；
// · **说**：`SayFact` 的 要什么／为什么／建议 三格缺一格 ⇒ 同样构造期拦（判据④的"结构断言"就是这个）；
// · **等**：进等待态、到点**只记账不放行**、人回复是唯一的出口（负例 M6 咬"到点当默许"）。
//
// 可靠性要**测得出来**（判据⑤后半）：到达＝这行落进了账（落盘成功才算到达），
// 被看到＝它被渲染进体检单（幂等、没列出的不假装有记）——两笔分开，"升级丢了"
// 才分得清是没递到还是递到了没人看。
//
// 「拒绝升级 ⇒ 该能力本轮禁用」判据⑤与「本轮」的边界（endRun 出清）在这里；
// 越界那一格由 `TraceBlackboxGateTest` 从喂入口那侧接过来递，本侧只管收到后怎么办。
//
// 两条源级红线（§四「不做实时告警弹卡」与 §二④「不许自行绕过」）不许靠自觉：
// `escalate/Escalation.kt` 代码面零 UI 引用，且 [Escalation.tick] 里没有一条移除等待件或
// 自答的语句——到点只做"记一行没人答"这一件事。
package com.example.zhengdao.baopu.escalate

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.law.LawIo
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EscalationGateTest {

    private lateinit var ledger: File

    /** 一个固定的现场时刻（账上的 at 全用它，读数才可比）。 */
    private val t0 = 1_700_000_000_000L

    private val runId = "run-505-es"

    @Before
    fun setUp() {
        ledger = Files.createTempDirectory("zd505-es-ledger").toFile()
        DecisionLedger.install(ledger)
        Escalation.resetForTest()
    }

    @After
    fun tearDown() {
        Escalation.resetForTest()
        DecisionLedger.uninstallForTest()
        ledger.deleteRecursively()
    }

    private fun stop(run: String = runId, mode: StopMode = StopMode.SAFE_ABORT, note: String = "② 段红线拦下，动作未发生") =
        StopFact(mode, run, note)

    private fun say(
        what: String = "要一次越界授权：目标在「写盘」边界之外",
        why: String = "第②段路径守卫把它拦下了，动作没发生",
        suggestion: String = "换个边界内的目标，或明确授权这一次写到哪儿",
    ) = SayFact(what, why, suggestion)

    private fun raise(
        cap: CapabilityKind = CapabilityKind.FILE_WRITE,
        trigger: EscalationTrigger = EscalationTrigger.OUT_OF_SCOPE,
        run: String = runId,
        atMs: Long = t0,
        timeoutMs: Long = 60_000L,
        what: String = "要一次越界授权：目标在「写盘」边界之外",
    ): EscalationRequest = Escalation.raise(
        capability = cap,
        trigger = trigger,
        stop = stop(run),
        say = say(what),
        nowMs = atMs,
        replyTimeoutMs = timeoutMs,
    )

    /** 只取某一类事件的账行（k＝raised／seen／answered／no_reply）。 */
    private fun rows(event: String): List<EscalationRow> = Escalation.replay().filter { it.event == event }

    private fun expectIllegalConstruction(block: () -> Any?) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue(
            "三要素缺一就要在构造期拦下，拿到的是 ${e?.let { it::class.simpleName }}：${e?.message}",
            e is IllegalArgumentException,
        )
    }

    // ── 判据④（前半）：三要素齐了才构造得出来 ──────────────────────────────────

    @Test
    fun `停_说_等各缺一格_构造期就拦`() {
        expectIllegalConstruction { StopFact(StopMode.SAFE_ABORT, "  ", "有句话") }
        expectIllegalConstruction { StopFact(StopMode.SAFE_ABORT, runId, "  ") }
        expectIllegalConstruction { SayFact("  ", "为什么", "建议") }
        expectIllegalConstruction { SayFact("要什么", "  ", "建议") }
        expectIllegalConstruction { SayFact("要什么", "为什么", "  ") }
        expectIllegalConstruction { WaitFact(t0, 0L) }
        // 齐了才造得出来（防空转）
        assertNotNull(SayFact("要什么", "为什么", "建议"))
        assertNotNull(StopFact(StopMode.SAFE_ABORT, runId, "怎么收的场"))
    }

    @Test
    fun `递一件_三格俱在_且进了等待态`() {
        val req = raise()
        val open = Escalation.open()
        assertEquals("升级＝有东西在等人，不是丢一句话就走", 1, open.size)
        val r = open[0]
        assertEquals(req.id, r.id)
        assertEquals("停：这一轮怎么收场，要带着停在哪一轮", runId, r.stop.runId)
        assertEquals(StopMode.SAFE_ABORT, r.stop.mode)
        val said = listOf(r.say.what, r.say.why, r.say.suggestion)
        assertEquals("说：三格都得有内容（判据④的结构断言）", 3, said.count { it.isNotBlank() })
        assertEquals("等：进等待的时刻＋到线口径", t0 + 60_000L, r.wait.dueAtMs())
        assertEquals(CapabilityKind.FILE_WRITE.wire, r.capability)
        assertEquals(EscalationTrigger.OUT_OF_SCOPE, r.trigger)
    }

    @Test
    fun `到线口径用既有授权超时_不许另立一把尺`() {
        val req = raise(timeoutMs = BaopuKernel.Auth.CONFIRM_TIMEOUT_MS)
        assertEquals(
            "「等人」到多久的默认口径＝授权那一侧已有的 CONFIRM_TIMEOUT_MS",
            BaopuKernel.Auth.CONFIRM_TIMEOUT_MS,
            req.wait.dueAtMs() - req.wait.enteredAtMs,
        )
    }

    // ── 幂等：一件事不许攒成 N 件 ───────────────────────────────────────────────

    @Test
    fun `同轮同能力同触发_重复递只算一件_账上也只一行`() {
        val first = raise()
        repeat(3) { assertEquals(first.id, raise().id) }
        assertEquals(1, Escalation.open().size)
        assertEquals("raised 行只许一条（攒行＝把同一件事说成三件）", 1, rows(Escalation.EVENT_RAISED).size)
    }

    @Test
    fun `不同触发算两件_越界与轨迹达阈各自成件`() {
        val a = raise(trigger = EscalationTrigger.OUT_OF_SCOPE)
        val b = raise(trigger = EscalationTrigger.TRACE_OVER_LIMIT, atMs = t0 + 1)
        assertTrue(a.id != b.id)
        assertEquals(2, Escalation.open().size)
        assertEquals(setOf(EscalationTrigger.OUT_OF_SCOPE.wire, EscalationTrigger.TRACE_OVER_LIMIT.wire),
            Escalation.open().map { it.trigger.wire }.toSet())
    }

    // ── 判据⑤（可靠性可测）：到达与被看到是两笔账 ──────────────────────────────

    @Test
    fun `到达_落盘成功才算到达_账行有出处有读数`() {
        val req = raise()
        val raised = rows(Escalation.EVENT_RAISED)
        assertEquals(1, raised.size)
        val r = raised[0]
        assertEquals(req.id, r.id)
        assertEquals(runId, r.runId)
        assertEquals(CapabilityKind.FILE_WRITE.wire, r.capability)
        assertEquals(EscalationTrigger.OUT_OF_SCOPE.wire, r.trigger)
        assertTrue("note＝「要什么」那句原文（不另写一套说法）", r.note.isNotBlank())
        assertTrue("落盘真写上去了", Escalation.fileOrNull()!!.isFile)
        assertEquals(t0, r.atMs)
    }

    @Test
    fun `没渲染之前_只有到达没有看到`() {
        raise()
        assertEquals(1, rows(Escalation.EVENT_RAISED).size)
        assertTrue("体检单还没念过它 ⇒ 账上不该有 seen", rows(Escalation.EVENT_SEEN).isEmpty())
    }

    @Test
    fun `被看到_渲染进体检单就记seen_且一件只记一次`() {
        val req = raise()
        val sheet = Escalation.sheetLines(nowMs = t0 + 1_000)
        assertTrue("单子上要有这一件：$sheet", sheet.first().contains("${req.capability}/${req.trigger.wire}"))
        val seen = rows(Escalation.EVENT_SEEN)
        assertEquals(1, seen.size)
        assertEquals(req.id, seen[0].id)
        // 再渲染一次：单子照出，账不重复
        Escalation.sheetLines(nowMs = t0 + 2_000)
        assertEquals("被看到是幂等的（刷行＝把一次看见说成两次）", 1, rows(Escalation.EVENT_SEEN).size)
    }

    @Test
    fun `超过列宽_说另有N件_没念到的不假装有被看到`() {
        val caps = listOf(
            CapabilityKind.FILE_WRITE,
            CapabilityKind.FILE_READ,
            CapabilityKind.BROWSER,
            CapabilityKind.NETWORK,
        )
        caps.forEachIndexed { i, c -> raise(cap = c, atMs = t0 + i) }
        val sheet = Escalation.sheetLines(nowMs = t0 + 1_000).first()
        assertEquals("单子要报总数", "4", Regex("在等 (\\d+) 件").find(sheet)!!.groupValues[1])
        assertTrue("单子不撑爆：超过的就说「另有 N 件」：$sheet", sheet.contains("另有 ${4 - Escalation.SHEET_ITEM_MAX} 件"))
        val seen = rows(Escalation.EVENT_SEEN).map { it.id }.toSet()
        assertEquals("列出来的才配记被看到", Escalation.SHEET_ITEM_MAX, seen.size)
        val notShown = Escalation.open().takeLast(4 - Escalation.SHEET_ITEM_MAX).map { it.id }
        assertTrue("没列出来的没被记成 seen：$notShown", notShown.none { it in seen })
    }

    // ── 判据④（后半）：超时无人应答 ⇒ 保持等待 ＋ 记「无人应答」 ────────────────

    @Test
    fun `到线_记一行无人应答_并且仍在等`() {
        val req = raise()
        val newly = Escalation.tick(nowMs = req.wait.dueAtMs())
        assertEquals(listOf(req.id), newly)
        val nr = rows(Escalation.EVENT_NO_REPLY)
        assertEquals(1, nr.size)
        assertEquals(req.id, nr[0].id)
        val note = nr[0].note
        assertTrue("note 要说清是「没人答」不是「办完了」：$note", note.contains("无人应答") && note.contains("仍在等待"))
    }

    @Test
    fun `负例M6_到点不当默许_没有answered行_等待件也不被移除`() {
        val req = raise()
        Escalation.tick(nowMs = req.wait.dueAtMs() + 1)
        assertEquals("到点之后还在等（不许自行绕过）", 1, Escalation.open().size)
        assertTrue("到点不算答复：${Escalation.replay()}", rows(Escalation.EVENT_ANSWERED).isEmpty())
        assertFalse("到点也不禁用能力（禁用只认人回复那一条口）", Escalation.isDisabled(runId, req.capability))
    }

    @Test
    fun `无人应答只记一次_反复tick不刷行`() {
        val req = raise()
        assertEquals(1, Escalation.tick(nowMs = req.wait.dueAtMs() + 1).size)
        assertTrue("第二次到线不再新记：${Escalation.tick(nowMs = req.wait.dueAtMs() + 9_999)}",
            Escalation.tick(nowMs = req.wait.dueAtMs() + 9_999).isEmpty())
        assertEquals(1, rows(Escalation.EVENT_NO_REPLY).size)
    }

    @Test
    fun `未到线_不记无人应答`() {
        val req = raise()
        assertTrue(Escalation.tick(nowMs = req.wait.dueAtMs() - 1).isEmpty())
        assertTrue(rows(Escalation.EVENT_NO_REPLY).isEmpty())
        assertEquals(1, Escalation.open().size)
    }

    // ── 判据⑤：拒绝升级 ⇒ 该能力本轮禁用 ────────────────────────────────────────

    @Test
    fun `人回复不许_该能力本轮禁用_并从等待里出`() {
        val req = raise()
        assertTrue(Escalation.respond(req.id, EscalationAnswer.REJECT, nowMs = t0 + 5_000))
        assertTrue("拒绝＝这一轮这条路封了", Escalation.isDisabled(runId, CapabilityKind.FILE_WRITE.wire))
        assertTrue(Escalation.open().isEmpty())
        val ans = rows(Escalation.EVENT_ANSWERED)
        assertEquals(1, ans.size)
        assertEquals(EscalationAnswer.REJECT.wire, ans[0].note)
        assertEquals(req.id, ans[0].id)
    }

    @Test
    fun `人回复照做_不禁用能力`() {
        val req = raise()
        assertTrue(Escalation.respond(req.id, EscalationAnswer.ACCEPT, nowMs = t0 + 5_000))
        assertFalse("点头不是禁用理由", Escalation.isDisabled(runId, CapabilityKind.FILE_WRITE.wire))
        assertEquals(EscalationAnswer.ACCEPT.wire, rows(Escalation.EVENT_ANSWERED)[0].note)
    }

    @Test
    fun `本轮禁用跨轮出清_endRun之后照做`() {
        val req = raise()
        Escalation.respond(req.id, EscalationAnswer.REJECT, nowMs = t0 + 5_000)
        assertTrue(Escalation.isDisabled(runId, CapabilityKind.FILE_WRITE.wire))
        Escalation.endRun(runId)
        assertFalse("禁的是本轮，不是这台机器", Escalation.isDisabled(runId, CapabilityKind.FILE_WRITE.wire))
    }

    @Test
    fun `禁用按能力类分格_拒绝写盘不牵连读盘`() {
        val w = raise(cap = CapabilityKind.FILE_WRITE)
        raise(cap = CapabilityKind.FILE_READ, atMs = t0 + 1)
        Escalation.respond(w.id, EscalationAnswer.REJECT, nowMs = t0 + 5_000)
        assertTrue(Escalation.isDisabled(runId, CapabilityKind.FILE_WRITE.wire))
        assertFalse(Escalation.isDisabled(runId, CapabilityKind.FILE_READ.wire))
        assertEquals("答的只有被点名的那一件", 1, rows(Escalation.EVENT_ANSWERED).size)
        assertEquals("另一件照旧在等", 1, Escalation.open().size)
    }

    @Test
    fun `不存在的id_答复与被看到都不成立_也不落账`() {
        raise()
        assertFalse(Escalation.respond("es:nope:1", EscalationAnswer.REJECT, nowMs = t0 + 1))
        assertFalse(Escalation.markSeen("es:nope:1", nowMs = t0 + 1))
        assertTrue(rows(Escalation.EVENT_ANSWERED).isEmpty())
        assertTrue(rows(Escalation.EVENT_SEEN).isEmpty())
        assertFalse("没答复过 ⇒ 没禁用", Escalation.isDisabled(runId, CapabilityKind.FILE_WRITE.wire))
    }

    // ── 没装配落点：不抛、不编历史（金吾卫装配账同一条纪律） ────────────────────

    @Test
    fun `没装配落点_递升级不抛_账表空_不编历史`() {
        DecisionLedger.uninstallForTest()
        val req = raise()
        assertEquals("内存里照样在等", 1, Escalation.open().size)
        assertEquals(req.id, Escalation.open()[0].id)
        assertTrue("落点没装配 ⇒ 回放空表（不拿内存当账）", Escalation.replay().isEmpty())
        assertNull(Escalation.fileOrNull())
    }

    @Test
    fun `重启后还在等_靠的是那本账_内存清了盘上行照在`() {
        val req = raise()
        val before = Escalation.replay()
        assertEquals(1, before.size)
        Escalation.resetForTest()
        assertTrue("resetForTest 只清内存：盘上还有一条", Escalation.open().isEmpty())
        val onDisk = Escalation.replay()
        assertEquals(1, onDisk.size)
        assertEquals(req.id, onDisk[0].id)
        assertEquals(Escalation.EVENT_RAISED, onDisk[0].event)
    }

    // ── 闭集与源级红线 ──────────────────────────────────────────────────────────

    @Test
    fun `事件名闭集_四个键_文案改了键不许改`() {
        assertEquals(
            setOf("raised", "seen", "answered", "no_reply"),
            setOf(Escalation.EVENT_RAISED, Escalation.EVENT_SEEN, Escalation.EVENT_ANSWERED, Escalation.EVENT_NO_REPLY),
        )
        assertEquals(setOf("wrap_up", "safe_abort"), StopMode.entries.map { it.wire }.toSet())
        assertEquals(setOf("accept", "reject"), EscalationAnswer.entries.map { it.wire }.toSet())
        // 「怎么收场」只有两格；「答复」只有两格——没有"没答当默认"那一格
        assertNull(EscalationAnswer.entries.firstOrNull { it.wire == "default" })
    }

    @Test
    fun `源级_升级层不碰UI_tick里没有放行分支`() {
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        val code = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/baopu/escalate/Escalation.kt"))
        listOf("import android.", "Toast", "Dialog", "Notification", "PendingIntent", "R.layout").forEach { bad ->
            assertFalse("§四「不做实时告警弹卡」：升级层引了 $bad", code.contains(bad))
        }
        val tick = code.substringAfter("fun tick(").substringBefore("\n    }")
        assertFalse("到点不许自答（绕过唯一出口）", tick.contains("respond("))
        assertFalse("到点不许把等待件移除（不许自行绕过）", tick.contains("requests.remove"))
        assertFalse("到点不许出禁用表（禁用只认人回复）", tick.contains("disableForRound"))
        // 防空转：三要素的构造期 `require` 还在
        assertEquals(
            "三要素的 require 恰好在停/说/等三格上（少一格＝结构断言失牙）",
            6,
            Regex("require\\(").findAll(code).count(),
        )
        assertTrue("等待态出口在（防空转）", code.contains("fun open("))
    }
}
