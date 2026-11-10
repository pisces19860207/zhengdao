// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批 · 项目账本**判据层**的直测（纯函数：零 IO、零时钟、零 Android）。
//
// 判据逐条（派工单 §判据）：
//   ① sourceMsgId **可选**（574）：空/纯空白放行（工具层自动补本轮消息号，没有就落 null），
//      超长仍拒（拒因要说清怎么办）；
//   ② 「已收」只认右相 PASS —— 重放层面就堵死：没有 verdict=PASS 的 done 行，谁写都到不了已收；
//   ③ append-only 可回放 —— 五态全部由 [LedgerRules.fold] 从行里折出来；
//   ④ 「已收」不可退回「在飞」—— 迁移表 + 重放两处都拒；
//   ⑥ 计数与重放一致 —— [LedgerRules.counts] 只从 items 算，不另记计数器。
package com.example.zhengdao.baopu.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerRulesTest {

    private fun add(id: String, title: String, src: String = "msg-1", at: Long = 1_000L) =
        LedgerRow(kind = LedgerKind.ADD, id = id, atMs = at, title = title, status = "flying", sourceMsgId = src)

    private fun status(id: String, target: LedgerState, at: Long = 2_000L) =
        LedgerRow(kind = LedgerKind.STATUS, id = id, atMs = at, status = target.wire)

    private fun freeze(id: String, at: Long = 2_000L) =
        LedgerRow(kind = LedgerKind.FREEZE, id = id, atMs = at, status = LedgerState.FROZEN.wire)

    private fun doneRow(id: String, verdict: String, at: Long = 3_000L) =
        LedgerRow(
            kind = LedgerKind.DONE,
            id = id,
            atMs = at,
            status = if (verdict == LedgerRules.VERDICT_PASS) "accepted" else "pending",
            verdict = verdict,
            reason = "理由",
        )

    private fun archive(id: String, at: Long = 4_000L) =
        LedgerRow(kind = LedgerKind.ARCHIVE, id = id, atMs = at, status = "accepted")

    // ── ① 来源可选（574）与标题上限 ──────────────────────────────────────────

    @Test
    fun `空来源放行_574改可选_超长仍拒`() {
        listOf("", "   ", "\n\t ").forEach { src ->
            assertNull("空来源不再拒（工具层自动补本轮消息号，后台轮落 null）：$src", LedgerRules.sourceRefusal(src))
        }
        assertNull("有来源照样放行", LedgerRules.sourceRefusal("msg-2026-11-09-001"))
    }

    @Test
    fun `来源太长拒_它是凭证号不是正文`() {
        val refusal = LedgerRules.sourceRefusal("m".repeat(LedgerRules.MAX_SOURCE_CHARS + 1))
        assertNotNull(refusal)
        assertTrue("拒因要说清上限：$refusal", refusal!!.contains(LedgerRules.MAX_SOURCE_CHARS.toString()))
    }

    @Test
    fun `四十字边界_40收_41拒`() {
        assertNull("40 字是边界内的值", LedgerRules.titleRefusal("一".repeat(40)))
        val refusal = LedgerRules.titleRefusal("一".repeat(41))
        assertNotNull("41 字必须拒", refusal)
        assertTrue("拒因要带「现在多少 / 上限多少」：$refusal", refusal!!.contains("41") && refusal.contains("40"))
    }

    @Test
    fun `两条闸先标题后来源_标题空了先报标题`() {
        val both = LedgerRules.addRefusal("", "")
        assertNotNull(both)
        assertTrue("先报标题：$both", both!!.contains("标题"))
        // 574：来源空不再拦——标题过了就放行（来源由工具层自动补/落 null）
        assertNull("标题过了、来源空 ⇒ 放行", LedgerRules.addRefusal("正常标题", ""))
        // 对照：来源有值但超长照样拦（可选 ≠ 不设防）
        val longSrc = LedgerRules.addRefusal("正常标题", "m".repeat(LedgerRules.MAX_SOURCE_CHARS + 1))
        assertTrue("超长来源仍要拦：$longSrc", longSrc!!.contains("sourceMsgId"))
    }

    @Test
    fun `换行被压成一行_进不了账`() {
        val raw = "第一行：做甲\n第二行：做乙"
        assertEquals("第一行：做甲 第二行：做乙", LedgerRules.normalize(raw))
        val item = LedgerItem("L1", LedgerRules.normalize(raw), "msg", 1L, LedgerState.FLYING)
        assertFalse(LedgerRules.rowText(item).contains('\n'))
    }

    // ── ③/⑤ 五态 replay ──────────────────────────────────────────────────────

    @Test
    fun `五态_在飞_待验收_已收`() {
        val rows = listOf(add("L1", "写交付报告"), status("L1", LedgerState.PENDING), doneRow("L1", LedgerRules.VERDICT_PASS))
        val item = LedgerRules.fold(rows).single()
        assertEquals(LedgerState.ACCEPTED, item.status)
        assertEquals(LedgerRules.VERDICT_PASS, item.verdict)
    }

    @Test
    fun `冻结与解冻_冻结不进待验收`() {
        val frozen = LedgerRules.fold(listOf(add("L1", "先放着"), freeze("L1"))).single()
        assertEquals(LedgerState.FROZEN, frozen.status)
        // 冻结不能直接去待验收（它压根没在做）——重放层面就不认这种行
        val jumped = LedgerRules.fold(listOf(add("L1", "先放着"), freeze("L1"), status("L1", LedgerState.PENDING, 3_000L)))
        assertEquals("冻结→待验收 的行不生效", LedgerState.FROZEN, jumped.single().status)
        // 解冻回在飞是合法的
        val thawed = LedgerRules.fold(
            listOf(add("L1", "先放着"), freeze("L1"), status("L1", LedgerState.FLYING, 3_000L)),
        )
        assertEquals(LedgerState.FLYING, thawed.single().status)
    }

    @Test
    fun `撤是终点_撤了之后的行不再改它`() {
        val rows = listOf(
            add("L1", "不做了"),
            status("L1", LedgerState.WITHDRAWN),
            status("L1", LedgerState.FLYING, 3_000L),
            doneRow("L1", LedgerRules.VERDICT_PASS, 4_000L),
        )
        val item = LedgerRules.fold(rows).single()
        assertEquals("撤之后不许复活", LedgerState.WITHDRAWN, item.status)
        assertEquals("也不许被追认已收", "", item.verdict)
    }

    // ── ②「已收」只认右相 PASS（重放层面的结构保证） ──────────────────────────

    @Test
    fun `负例_done行没有PASS_到不了已收`() {
        listOf(LedgerRules.VERDICT_UNVERIFIABLE, "FAIL", "").forEach { verdict ->
            val rows = listOf(add("L1", "做了一半"), doneRow("L1", verdict))
            val item = LedgerRules.fold(rows).single()
            assertEquals("verdict=[$verdict] 不许落到已收", LedgerState.PENDING, item.status)
        }
    }

    @Test
    fun `负例_手写add就写accepted_重放出来仍在飞`() {
        val forged = LedgerRow(kind = LedgerKind.ADD, id = "L1", atMs = 1_000L, title = "伪造的", status = "accepted", sourceMsgId = "msg")
        assertEquals("没有 done+PASS 那一行，status=accepted 只是一句话", LedgerState.FLYING, LedgerRules.fold(listOf(forged)).single().status)
    }

    @Test
    fun `负例_status行想直接写accepted_不生效`() {
        val rows = listOf(add("L1", "想抄近路"), status("L1", LedgerState.ACCEPTED))
        assertEquals("已收只能由 done+PASS 产生", LedgerState.FLYING, LedgerRules.fold(rows).single().status)
    }

    // ── ④ 已收不可退回在飞 ────────────────────────────────────────────────────

    @Test
    fun `已收不可退回在飞_迁移表与拒因都拒`() {
        assertFalse("迁移表：已收出去的路是空集", LedgerRules.allows(LedgerState.ACCEPTED, LedgerState.FLYING))
        val accepted = LedgerItem("L1", "做完了", "msg", 1L, LedgerState.ACCEPTED)
        val refusal = LedgerRules.transitionRefusal(accepted, LedgerState.FLYING)
        assertNotNull("已收→在飞 必须拒", refusal)
        assertTrue("拒因要说清是终点：$refusal", refusal!!.contains("终点"))
        // 重放层面也拒：先已收、再来一行飞行
        val rows = listOf(add("L1", "做完了"), doneRow("L1", LedgerRules.VERDICT_PASS), status("L1", LedgerState.FLYING, 5_000L))
        assertEquals("历史里真有这种行也不认", LedgerState.ACCEPTED, LedgerRules.fold(rows).single().status)
    }

    @Test
    fun `在飞能去的四个目标_冻结只能去两个`() {
        assertEquals(
            listOf(LedgerState.PENDING, LedgerState.ACCEPTED, LedgerState.FROZEN, LedgerState.WITHDRAWN),
            LedgerRules.targetsFrom(LedgerState.FLYING),
        )
        assertEquals(
            listOf(LedgerState.FLYING, LedgerState.WITHDRAWN),
            LedgerRules.targetsFrom(LedgerState.FROZEN),
        )
        assertTrue("终点没有出口", LedgerRules.targetsFrom(LedgerState.ACCEPTED).isEmpty())
        assertTrue("撤也是终点", LedgerRules.targetsFrom(LedgerState.WITHDRAWN).isEmpty())
    }

    // ── ⑸ 归档：默认视图隐藏，账行一条不少 ────────────────────────────────────

    @Test
    fun `归档后默认视图不露面_但条目还在`() {
        val rows = listOf(add("L1", "做完了"), doneRow("L1", LedgerRules.VERDICT_PASS), archive("L1"))
        val items = LedgerRules.fold(rows)
        assertTrue("默认视图（live）里没有它", LedgerRules.live(items).isEmpty())
        assertEquals("账上那条还在（只是归档了）", 1, LedgerRules.archived(items).size)
        assertEquals("账本行一行没少", 3, rows.size)
    }

    @Test
    fun `归档之后的行不再改它`() {
        val rows = listOf(
            add("L1", "做完了"),
            doneRow("L1", LedgerRules.VERDICT_PASS),
            archive("L1"),
            status("L1", LedgerState.WITHDRAWN, 9_000L),
        )
        val item = LedgerRules.fold(rows).single()
        assertEquals("归档后状态就冻在那儿", LedgerState.ACCEPTED, item.status)
        assertTrue(item.archivedAtMs > 0L)
    }

    // ── ⑥ 计数与 headerLine ───────────────────────────────────────────────────

    @Test
    fun `计数只从重放算_归档单列`() {
        val items = listOf(
            LedgerItem("L1", "a", "m", 1L, LedgerState.FLYING),
            LedgerItem("L2", "b", "m", 1L, LedgerState.PENDING),
            LedgerItem("L3", "c", "m", 1L, LedgerState.ACCEPTED),
            LedgerItem("L4", "d", "m", 1L, LedgerState.FROZEN),
            LedgerItem("L5", "e", "m", 1L, LedgerState.WITHDRAWN),
            LedgerItem("L6", "f", "m", 1L, LedgerState.ACCEPTED, archivedAtMs = 9L),
        )
        val counts = LedgerRules.counts(items)
        assertEquals(1, counts.flying)
        assertEquals(1, counts.pending)
        assertEquals("归档的不算在「已收」里（默认视图看不见）", 1, counts.accepted)
        assertEquals(1, counts.frozen)
        assertEquals(1, counts.withdrawn)
        assertEquals(1, counts.archived)
        assertEquals("在飞 1 · 待验收 1 · 已收 1 · 冻结 1 · 撤 1", LedgerRules.headerLine(counts))
    }

    @Test
    fun `id不复用_归档之后接着往后编`() {
        val rows = listOf(add("L1", "a"), add("L2", "b"), archive("L2"))
        assertEquals("L3", LedgerRules.nextId(rows))
    }

    @Test
    fun `坏行不影响别的行`() {
        val rows = listOf(add("L1", "正常"), LedgerRow(LedgerKind.STATUS, "L1", 2_000L, status = "不存在的状态"))
        assertEquals("坏值不改状态", LedgerState.FLYING, LedgerRules.fold(rows).single().status)
    }
}
