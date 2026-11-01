// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十一批 · 自决账的判据测试。
//
// 五组判据（派工单 §②③）：每笔有自决单（五字段齐全）、自决不落单（空依据落不下来）、
// 摘要 ≤1 行且数字对得上账（含「外来摘要对不上」的负例）、误自决可被推翻（原单不改写）、
// 三读数可读到（分母是账行、样本不足不印 %、跨 208 账对不齐要报）。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.inspect.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfDecideLogTest {

    private fun selfRuling(
        action: String = "改文案",
        rollback: Rollback = Rollback.Ready("git checkout"),
        irreversible: Boolean = true,
    ): SelfJudgement = SelfRule.judge(
        SelfRequest(
            actionId = "a-$action",
            action = action,
            authorization = Authorization.IN_SCOPE,
            irreversible = irreversible,
            rollback = rollback,
            destructive = true,
        ),
    )

    private fun askRuling(action: String = "删库"): SelfJudgement = SelfRule.judge(
        SelfRequest(
            actionId = "a-$action",
            action = action,
            authorization = Authorization.IN_SCOPE,
            irreversible = true,
        ),
    )

    private fun logOf(slips: Int, asks: Int, overturns: Int = 0): SelfDecideLog {
        var log = SelfDecideLog.empty()
        var at = 1_000L
        repeat(slips) { i ->
            log = log.record(selfRuling("改文案$i"), at)
            at += 1
        }
        repeat(asks) { i ->
            log = log.record(askRuling("删库$i"), at)
            at += 1
        }
        repeat(overturns) { i ->
            log = log.overturn(log.slips[i].id, "误判：那台是生产机", at)
            at += 1
        }
        return log
    }

    // ── 每笔有自决单 ────────────────────────────────────────────────────

    @Test
    fun `每笔自决都有一张单_五个字段齐全`() {
        val log = SelfDecideLog.empty().record(selfRuling("删临时缓存"), 1_700_000_000_000L)
        assertEquals(1, log.size)
        val slip = log.slips.single()
        assertEquals("时间", 1_700_000_000_000L, slip.atMs)
        assertEquals("动作", "删临时缓存", slip.action)
        assertTrue("依据", slip.basis.isNotBlank())
        assertTrue("可否回退", slip.rollbackable)
        assertEquals("回退方式", "git checkout", slip.rollbackHow)
        assertEquals("五个字段一个不多", 5, slip.columns().size)
        assertEquals(slip, log.slip(slip.id))
    }

    @Test
    fun `不可回退的单_回退方式写固定词而不是留空`() {
        val log = SelfDecideLog.empty()
            .record(selfRuling("改 settings 里的开关", rollback = Rollback.None, irreversible = false), 1_000L)
        val slip = log.slips.single()
        assertFalse(slip.rollbackable)
        assertEquals(SelfSlip.NO_ROLLBACK, slip.rollbackHow)
        assertEquals("—", slip.columns()[4])
    }

    @Test
    fun `负例_空依据不许落单`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SelfDecideLog.empty().record(selfRuling(), 1_000L, basis = "  ")
        }
        assertTrue(e.message!!.contains("空单"))
    }

    @Test
    fun `该问的不许落成自决单`() {
        val log = SelfDecideLog.empty().record(askRuling(), 1_000L)
        assertEquals("必须问只落必须问的账", 0, log.slips.size)
        val ask = log.asks.single()
        assertEquals(AskClass.IRREVERSIBLE, ask.askClass)
        assertTrue(ask.why.isNotBlank())
    }

    @Test
    fun `时间为0不许落单`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfDecideLog.empty().record(selfRuling(), 0L)
        }
    }

    // ── 摘要一行 ＋ 数字对账 ────────────────────────────────────────────

    @Test
    fun `收工摘要只有一行_数字与账逐格对平`() {
        val log = logOf(slips = 3, asks = 2, overturns = 1)
        val line = log.summaryLine()
        assertFalse("摘要必须是一行：$line", line.contains('\n') || line.contains('\r'))
        assertTrue(line.startsWith("[自决] 收工："))
        assertEquals("摘要与账对平：${log.auditSummary(line)}", emptyList<String>(), log.auditSummary(line))
        assertEquals(6, log.counts().total)
        assertTrue(line.contains("推翻 1 笔"))
    }

    @Test
    fun `负例_摘要与账不符会被逐格抓出来`() {
        val log = logOf(slips = 2, asks = 1)
        val tampered = log.summaryLine().replace("自决 2 笔", "自决 3 笔")
        val problems = log.auditSummary(tampered)
        assertTrue("改过的摘要必须被抓：$problems", problems.any { it.contains("摘要与账不符") })
        assertTrue("要点名是哪一格：$problems", problems.any { it.contains("摘要写 3") })

        val foreign = logOf(slips = 4, asks = 0).summaryLine()
        assertTrue("别人账上的摘要也必须对不上", log.auditSummary(foreign).isNotEmpty())

        assertTrue("多行摘要不认", log.auditSummary(log.summaryLine() + "\n第二行").isNotEmpty())
        assertTrue("不认识的格式不认", log.auditSummary("自决 3 笔").isNotEmpty())
    }

    @Test
    fun `对账_行数与分类和都合得上`() {
        val log = logOf(slips = 2, asks = 3, overturns = 1)
        val rec = log.reconcile()
        assertTrue("对账要过：${rec.text()}", rec.ok)
        assertEquals(3, rec.counts.asks)
        assertEquals(3, rec.counts.byClass.values.sum())
        assertEquals(2, rec.counts.rollbackable + rec.counts.unrollbackable)
        assertEquals(1, rec.counts.overturned)
    }

    // ── 误自决可被推翻 ──────────────────────────────────────────────────

    @Test
    fun `推翻之后误自决率动起来_原单一个字节都不改`() {
        val log = logOf(slips = 3, asks = 0)
        val target = log.slips.first()
        val after = log.overturn(target.id, "其实该问：那台是生产机", 9_000L)

        assertEquals("原单还在（可查）", 3, after.slips.size)
        assertEquals("原单内容不变", target, after.slip(target.id))
        assertEquals("推翻本身也是一笔账", 1, after.overturns.size)
        assertEquals("被推翻的单", target.id, after.overturns.single().slipId)
        assertEquals(1, after.readings(ledgerTotal = 3).misSelfRate.num)
        assertEquals(3, after.readings(ledgerTotal = 3).misSelfRate.denom)
    }

    @Test
    fun `推翻_未知单_重复推翻_空理由都拒`() {
        val log = logOf(slips = 2, asks = 0)
        val id = log.slips.first().id
        assertThrows(IllegalArgumentException::class.java) { log.overturn("不存在", "理由", 9_000L) }
        assertThrows(IllegalArgumentException::class.java) { log.overturn(id, "  ", 9_000L) }

        val once = log.overturn(id, "理由", 9_000L)
        val e = assertThrows(IllegalArgumentException::class.java) { once.overturn(id, "又推翻一次", 9_001L) }
        assertTrue("重复推翻会把误自决率算重：${e.message}", e.message!!.contains("算重"))
        assertEquals(1, once.readings(ledgerTotal = 2).misSelfRate.num)
    }

    // ── 落盘与读回 ──────────────────────────────────────────────────────

    @Test
    fun `落盘一行一条_竖线与换行都不许把账劈开`() {
        val log = SelfDecideLog.empty()
            .record(selfRuling("删 临时|缓存"), 1_000L, basis = "依据含|竖线\n和第二行")
        val lines = log.encode()
        assertEquals("一笔一行", 1, lines.size)
        assertFalse("换行要压平", lines.single().contains('\n'))
        val back = SelfDecideLog.parse(lines).slips.single()
        assertEquals("删 临时|缓存", back.action)
        assertTrue("竖线要原样回来：${back.basis}", back.basis.contains("|"))
        assertEquals(log.slips.single().rollbackHow, back.rollbackHow)
        assertEquals(log.slips.single().atMs, back.atMs)
    }

    @Test
    fun `三类行都能落盘再读回_坏行丢弃但不会变成别的行`() {
        val log = logOf(slips = 2, asks = 1, overturns = 1)
        val back = SelfDecideLog.parse(log.encode())
        assertEquals(log.size, back.size)
        assertEquals(2, back.slips.size)
        assertEquals(1, back.asks.size)
        assertEquals(1, back.overturns.size)
        assertEquals("读回之后摘要还对得上", emptyList<String>(), back.auditSummary(back.summaryLine()))

        val dirty = SelfDecideLog.parse(log.encode() + listOf("这不是一行账", "slip|只有两列"))
        assertEquals("坏行丢弃，好行照读", log.size, dirty.size)
    }

    // ── 三读数 ──────────────────────────────────────────────────────────

    @Test
    fun `三读数_分母是账行_样本不足不印百分比`() {
        val log = logOf(slips = 8, asks = 2)
        val r = log.readings(ledgerTotal = 10)
        assertTrue("自决率 8/10：${r.selfRate.text()}", r.selfRate.text().contains("80%"))
        assertTrue("打扰比 2/10：${r.disturbRate.text()}", r.disturbRate.text().contains("20%"))
        assertTrue("误自决率分母 8 < 10：${r.misSelfRate.text()}", r.misSelfRate.text().contains("样本不足"))
        assertFalse("样本不足时不许印 %：${r.misSelfRate.text()}", r.misSelfRate.text().contains("%"))
        assertEquals(emptyList<String>(), r.gaps)
        assertEquals(3, r.lines().size)
    }

    @Test
    fun `一笔都没有时写无数据_不印百分比`() {
        val r = SelfDecideLog.empty().readings(ledgerTotal = 0)
        assertTrue(r.selfRate.text().contains("无数据"))
        assertFalse(r.selfRate.text().contains("%"))
        assertTrue(r.disturbRate.text().contains("无数据"))
    }

    @Test
    fun `账实不符_自决比208账还多就报出来`() {
        val log = logOf(slips = 3, asks = 0)
        val bad = log.readings(ledgerTotal = 2)
        assertTrue("有自决没进 208：${bad.gaps}", bad.gaps.any { it.contains("账实不符") })
        assertTrue("未取样也单独说：${log.readings(ledgerTotal = null).gaps}", log.readings(ledgerTotal = null).gaps.any { it.contains("未取样") })
    }

    @Test
    fun `最小分母与读数层同口径`() {
        assertNotNull(SelfReading.MIN_DENOM)
        assertEquals("两处判据迟早打架，所以钉成相等", Metrics.MIN_DENOM, SelfReading.MIN_DENOM)
    }
}
