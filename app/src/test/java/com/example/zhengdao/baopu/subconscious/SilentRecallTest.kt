// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十五批 · 判据直测。每条判据一个用例，另带负例自检（判据若恒真就等于没有）。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerState
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class SilentRecallTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DAY0: LocalDate = LocalDate.of(2026, 11, 25)
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24L * HOUR_MS

        /** 催办/建议/安慰词（中英都禁）；判别器先喂违规样本自证，再扫真文案。 */
        val BANNED_COPY = listOf(
            "建议", "应该", "记得", "别忘了", "尽快", "抓紧", "要不要", "试试",
            "加油", "别担心", "辛苦了", "请处理", "提醒你",
            "should", "suggest", "remind", "don't forget", "hurry", "cheer up",
        )
    }

    private fun at(day: Long, hour: Long = 10L): Long =
        DAY0.plusDays(day).atStartOfDay(ZONE).toInstant().toEpochMilli() + hour * HOUR_MS

    private fun ledgerItem(id: String = "L2-1", atMs: Long = at(0)): LedgerItem = LedgerItem(
        id = id,
        title = "账行 $id 的一句话标题",
        sourceMsgId = "msg-$id",
        createdAtMs = atMs,
        status = LedgerState.FLYING,
    )

    private fun inspiration(id: String = "insp-1", atMs: Long = at(0)): InspirationRecord =
        InspirationRecord(id = id, createdAtMs = atMs, tags = emptyList(), body = "一句原文")

    private fun bannedIn(text: String): String? = BANNED_COPY.firstOrNull { text.contains(it, ignoreCase = true) }

    private fun event(atMs: Long, why: String): DiaryEvent = DiaryEvent(
        kind = DiaryKind.ACTION, atMs = atMs, why = why, what = "一句话", result = "一项",
    )

    // ── 判据①：候选逐条指回既有账行；无 id ⇒ 拒 ─────────────────────────────

    @Test
    fun `候选只能取自传入的既有账行与L0_自造id拒`() {
        val item = ledgerItem()
        val rec = inspiration()
        val candidates = recallCandidate(listOf(item), listOf(rec), at(0, 12))
        assertEquals(listOf("L2-1", "insp-1"), candidates.map { it.ledgerId })
        assertTrue(candidates.all { it.ledgerId in setOf(item.id, rec.id) })
        checkCandidateRefs(candidates, setOf(item.id, rec.id))
        try {
            checkCandidateRefs(candidates, emptySet())
            fail("指不到既有账行的候选必须让整轮拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("指不到既有账行"))
        }
    }

    @Test
    fun `候选构造期就拒空账行id`() {
        try {
            SilentCandidate(" ", "项目账", "标题", at(0), "缘由")
            fail("空账行 id 不该被构造出来")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("账行 id"))
        }
    }

    // ── 判据②：每天 ≤1（计数断言） ─────────────────────────────────────────

    @Test
    fun `同日再算不浮_计数恒不超过一`() {
        val candidates = recallCandidate(
            listOf(ledgerItem("L2-1"), ledgerItem("L2-2", at(0, 9))), emptyList(), at(1),
        )
        val known = candidates.map { it.ledgerId }.toSet()
        val first = selectSilentLine(candidates, known, at(1), null, emptySet())
        assertNotNull(first)
        val second = selectSilentLine(candidates, known, at(1, 15), first!!.dayKey, emptySet())
        assertNull(second)
        assertEquals(1, listOfNotNull(first, second).size)
    }

    @Test
    fun `次日可再浮一行`() {
        val candidates = recallCandidate(listOf(ledgerItem()), emptyList(), at(1))
        val known = candidates.map { it.ledgerId }.toSet()
        val first = selectSilentLine(candidates, known, at(1), null, emptySet())
        val next = selectSilentLine(candidates, known, at(2), first!!.dayKey, emptySet())
        assertNotNull(next)
        assertTrue(next!!.dayKey != first.dayKey)
    }

    // ── 判据⑤：与 676/677 共享 24h 同账行去重 ───────────────────────────────

    @Test
    fun `三账键归一_认得676与677与自己的行`() {
        assertEquals("L2-9", SharedRecallDedupe.keyOf("676 回响 · echo:L2-9"))
        assertEquals("insp-9", SharedRecallDedupe.keyOf("534 灵感闪回·浮出·insp-9"))
        assertEquals("L2-9", SharedRecallDedupe.keyOf("625 静默回想·浮出·L2-9"))
        assertNull("别家的why不该算成共享键", SharedRecallDedupe.keyOf("别的账·浮出·L2-9"))
        assertNull("空键不该算", SharedRecallDedupe.keyOf("625 静默回想·浮出· "))
    }

    @Test
    fun `共享去重_24小时内同账行被挡_25小时后放行`() {
        val now = at(2, 12)
        val events = listOf(
            event(now - HOUR_MS, "676 回响 · echo:L2-1"),
            event(now - 2 * HOUR_MS, "534 灵感闪回·浮出·insp-1"),
        )
        val keys = SharedRecallDedupe.recentKeys(events, now)
        assertEquals(setOf("L2-1", "insp-1"), keys)

        val candidates = recallCandidate(
            listOf(ledgerItem("L2-1", now - HOUR_MS)),
            listOf(inspiration("insp-1", now - 2 * HOUR_MS)),
            now,
        )
        val known = candidates.map { it.ledgerId }.toSet()
        assertNull("24h 内出现过的两条都该被挡", selectSilentLine(candidates, known, now, null, keys))

        val oldKeys = SharedRecallDedupe.recentKeys(
            events.map { it.copy(atMs = now - 25 * HOUR_MS) }, now,
        )
        assertTrue(oldKeys.isEmpty())
        assertNotNull(selectSilentLine(candidates, known, now, null, oldKeys))
    }

    @Test
    fun `三账只读_读完两本账一字未改`() {
        TempSandbox.use("silent-dedupe") { dir ->
            val echo = File(dir, "回响.jsonl")
            val insp = File(dir, "灵感闪回.jsonl")
            echo.writeText(DiaryCodec.encode(event(at(2, 10), "676 回响 · echo:L2-1")) + "\n")
            insp.writeText(DiaryCodec.encode(event(at(2, 11), "534 灵感闪回·浮出·insp-1")) + "\n")
            val before = listOf(echo.readText(), insp.readText())
            assertEquals(setOf("L2-1", "insp-1"), SharedRecallDedupe.readKeys(listOf(echo, insp), at(2, 12)))
            assertEquals(before, listOf(echo.readText(), insp.readText()))
        }
    }

    // ── 判据③：不点不进上下文（未交互增量 = 0） ─────────────────────────────

    @Test
    fun `不点_信箱空_上下文增量逐字节为零`() {
        SilentMailbox.clear()
        SilentLineStore.clearForTest()
        val base = listOf("固定前缀一", "固定前缀二")
        val noInteraction = base + SilentMailbox.drain()
        assertEquals("未点开 ⇒ drain 空表 ⇒ 纯追加后与接线前逐字节一致", base, noInteraction)
        assertEquals(0, SilentMailbox.drain().size)
    }

    @Test
    fun `浮出但不点_仍不进上下文_二次点不重放`() {
        SilentMailbox.clear()
        SilentLineStore.clearForTest()
        val line = SilentLine("L2-1", "项目账", "标题", "账行 L2-1 的结论是 FAIL", at(0), at(0))
        SilentLineStore.publish(line)
        assertTrue(SilentMailbox.drain().isEmpty())

        val opened = SilentLineStore.consumeOpen()
        assertNotNull(opened)
        SilentMailbox.offer("【静默回想·用户点开】账行 ${line.ledgerId}\n${line.reason}")
        val notes = SilentMailbox.drain()
        assertEquals(1, notes.size)
        assertTrue(notes[0].contains("L2-1"))
        assertTrue(SilentMailbox.drain().isEmpty())
        assertNull("同一行再点不再放行", SilentLineStore.consumeOpen())
    }

    // ── 判据④：不许催办/建议；不做情绪安慰 ───────────────────────────────────

    @Test
    fun `禁词判别器先自证咬得住_再扫全部真实文案`() {
        assertNotNull("判别器必须咬住催办样本", bannedIn("你可以尽快处理这条账"))
        assertNotNull("判别器必须咬住安慰样本", bannedIn("别担心，加油"))
        assertNull("事实陈述不该被误判", bannedIn("账行 L2-1 立于 2026-11-25，状态在飞"))

        val items = listOf(
            ledgerItem(),
            ledgerItem("L2-2").copy(status = LedgerState.FROZEN),
            ledgerItem("L2-3").copy(status = LedgerState.WITHDRAWN),
            ledgerItem("L2-4").copy(verdict = "FAIL"),
        )
        val reasons = recallCandidate(items, listOf(inspiration()), at(1)).map { it.reason }
        assertEquals(5, reasons.size)
        reasons.forEach { assertNull("缘由出现禁词：$it", bannedIn(it)) }
    }

    // ── 判据⑥：开关关逐字节一致 ─────────────────────────────────────────────

    @Test
    fun `开关默认开可关_关时附注仍为空表`() {
        assertTrue(SilentRecallSwitch.resolve(null))
        assertFalse(SilentRecallSwitch.resolve(false))
        SilentMailbox.clear()
        val baseline = "固定提示词"
        assertEquals(baseline, baseline + SilentMailbox.drain().joinToString("") { it })
    }

    // ── ③的账侧：点开一行必带账行 id 与缘由 ─────────────────────────────────

    @Test
    fun `浮出与点开两行账都带账行id_点开行带缘由`() {
        val line = SilentLine("L2-7", "项目账", "标题", "账行 L2-7 的结论是 FAIL", at(0), at(0))
        val floated = SilentRecallLedger.floatedEvent(at(0, 10), line)
        val opened = SilentRecallLedger.openedEvent(at(0, 11), line)
        assertTrue(floated.why.endsWith("L2-7"))
        assertTrue(opened.why.endsWith("L2-7"))
        assertEquals(line.reason, opened.result)
        assertTrue(SilentRecallLedger.lastFloatedDay(listOf(floated)) != null)
    }
}
