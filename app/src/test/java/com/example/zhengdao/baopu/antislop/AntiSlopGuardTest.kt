// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十九批 · 三化抑制判据夹具。
// 三条规则各有正例（合格文本，须干净）＋反例（违规文本，须判红）；A/B 按派工单 §二②③
// 点名用例；C 三项读数各给数字。全部纯内存断言，零写盘、零临时目录（水位 607 口径不受影响）。
// 夹具为本单自撰的教材级短句，非素材正文。
package com.example.zhengdao.baopu.antislop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiSlopGuardTest {

    private val cfg = AntiSlopBaselines.parse(
        listOf(File(AntiSlopBaselines.REL_PATH), File("../${AntiSlopBaselines.REL_PATH}"))
            .first { it.isFile }.readText(),
    )
    private val guard = AntiSlopGuard(cfg)

    /** 判据③的"全局一句非医疗判断"来自配置，不在测试里写死字面量。 */
    private fun withDisclaimer(body: String) = "$body${cfg.b.globalDisclaimer}。"

    /** 构造带来源 id 的合格夸大句：稀有度＋来源＋夸大词同句。 */
    private fun rareInflation() =
        "${cfg.a.inflationMarkers.first()}，${cfg.a.rarityMarkers.first()}（约1/600），${cfg.a.sourceMarkers.first()} facts-body-p50。"

    // ── A 尺寸通胀 ────────────────────────────────────────────────────────

    @Test
    fun `判据2_A反例_18cm无标记_判红且拦`() {
        val hits = guard.scanA("据测量约${cfg.a.inflationMarkers.first()}。")
        assertEquals(setOf("A-NO-SOURCE-EXAGGERATION"), hits.map { it.code }.toSet())
        assertTrue("无来源夸大必须是 BLOCK", hits.all { it.severity == SlopSeverity.BLOCK })
        val span = hits.first().let { "据测量约${cfg.a.inflationMarkers.first()}。".substring(it.start, it.end) }
        assertEquals(cfg.a.inflationMarkers.first(), span)
    }

    @Test
    fun `判据2_A正例_夸大词带稀有度与来源_判绿`() {
        val text = rareInflation()
        assertTrue("三者齐全不得咬：$text", guard.scanA(text).isEmpty())
    }

    @Test
    fun `判据3_A反例_默认值写成夸大词_判红提示`() {
        val cue = cfg.a.defaultCues.first()
        val hits = guard.scanA("${cue}${cfg.a.inflationMarkers.first()}。")
        assertTrue(hits.any { it.code == "A-DEFAULT-INFLATION" })
        assertTrue("默认值越界只提示不拦", hits.filter { it.code.startsWith("A-DEFAULT") }.all { it.severity == SlopSeverity.WARN })
    }

    @Test
    fun `A正例_默认值落665区间_判绿`() {
        val mid = (cfg.a.defaultBandLowMm + cfg.a.defaultBandHighMm) / 2
        val text = "默认长度 ${java.lang.String.format("%.1f", mid / cfg.a.mmPerCm)}cm，属常见区间。"
        assertTrue("P50 附近默认值须干净：$text", guard.scanA(text).isEmpty())
    }

    @Test
    fun `A反例_默认值越出665区间_判红提示`() {
        val over = cfg.a.defaultBandHighMm + cfg.window.hedgeChars
        val hits = guard.scanA("默认长度 ${java.lang.String.format("%.1f", over / cfg.a.mmPerCm)}cm。")
        assertTrue(hits.any { it.code == "A-DEFAULT-OUT-OF-BAND" })
        assertTrue(hits.all { it.severity == SlopSeverity.WARN })
    }

    // ── B 医学免责腔 ────────────────────────────────────────────────────────

    @Test
    fun `判据3_B反例_三处hedge_判红提示`() {
        val h = cfg.b.hedges
        val text = "${h[0]}如此，${h[1]}这般，${h[2]}看来，${h[3]}再确认。"
        val hits = guard.scanB(text)
        assertTrue("三处以上 hedge 须判红", hits.any { it.code == "B-HEDGE-DENSITY" })
        assertTrue("hedge 只提示不拦", hits.all { it.severity == SlopSeverity.WARN })
    }

    @Test
    fun `判据3_B正例_零hedge加全局一句非医疗判断_判绿`() {
        val text = withDisclaimer("形态描述到此为止。")
        assertTrue("0 hedge＋一句免责须干净：$text", guard.scanB(text).isEmpty())
    }

    @Test
    fun `B反例_连续两段都带hedge_判红`() {
        val h = cfg.b.hedges
        val text = "${h[0]}如此。\n${h[1]}那般。"
        assertTrue(guard.scanB(text).any { it.code == "B-CONSECUTIVE-HEDGE" })
    }

    @Test
    fun `B反例_建议禁词_判红且拦`() {
        val text = "建议${cfg.b.adviceBanWords.first()}一下。"
        val hits = guard.scanB(text)
        assertTrue(hits.any { it.code == "B-ADVICE-BAN" })
        assertTrue("建议禁词必须 BLOCK", hits.any { it.severity == SlopSeverity.BLOCK })
    }

    @Test
    fun `B反例_全局免责重复_判红提示`() {
        val d = cfg.b.globalDisclaimer
        val text = "$d。$d。$d。"
        assertTrue(guard.scanB(text).any { it.code == "B-DISCLAIMER-REPEAT" })
    }

    // ── C 模板腔 ────────────────────────────────────────────────────────────

    @Test
    fun `判据1_C反例_黑名单连发_判红提示`() {
        val b = cfg.c.blacklist
        val text = "${b[0]}，${b[1]}，${b[2]}，${b[3]}，${b[4]}。"
        val hits = guard.scanC(text)
        assertTrue("黑名单超密度须判红", hits.any { it.code == "C-BLACKLIST-RATE" })
        assertTrue("C 一律只提示不拦", hits.all { it.severity == SlopSeverity.WARN })
    }

    @Test
    fun `判据1_C正例_白话描述_判绿`() {
        val text = "他走进房间，把书放在桌上，然后坐下翻开第一页。"
        assertTrue("白话不得咬：$text", guard.scanC(text).isEmpty())
    }

    @Test
    fun `判据4_C三项读数各给数字`() {
        val b = cfg.c.blacklist
        val f = cfg.c.fourCharLexicon
        val m = cfg.c.summaryMarkers.first()
        // 段 1：黑名单 2 词（b0/b1）＋黑名单外的四字词 2 个（f5/f6）；三段都以总结词收尾 ⇒ 100%
        val text = "${b[0]}${b[1]}${f[5]}${f[6]}，${m}。\n细节一段${m}。\n再一段${m}。"
        val read = guard.metrics(text)
        val chars = text.count { !it.isWhitespace() }
        val expectBlack = 2.0 * cfg.window.blacklistChars / chars
        val expectFour = 4.0 * cfg.window.fourCharChars / chars
        assertEquals("黑名单每 ${cfg.window.blacklistChars} 字读数", expectBlack, read.blacklistPerWindow, 1e-9)
        assertEquals("四字词每 ${cfg.window.fourCharChars} 字读数", expectFour, read.fourCharPerWindow, 1e-9)
        assertEquals("段尾总结率读数", 1.0, read.summaryRate, 1e-9)
        assertTrue("黑名单命中率须为正", read.blacklistPerWindow > 0)
    }

    @Test
    fun `判据1_三规则正例全文干净`() {
        val clean = "他走进房间，把书放在桌上，形态描述到此为止。${cfg.b.globalDisclaimer}。"
        assertTrue(guard.analyze(clean).findings.isEmpty())
    }

    // ── 纯函数・确定性・只报不改写 ──────────────────────────────────────────

    @Test
    fun `只报不改写_原文逐字节不变`() {
        val text = rareInflation() + cfg.b.adviceBanWords.first()
        val before = text.toByteArray()
        guard.analyze(text)
        assertTrue(before.contentEquals(text.toByteArray()))
    }

    @Test
    fun `确定性_同输入两次分析逐字节一致`() {
        val text = rareInflation() + cfg.c.blacklist.first()
        assertEquals(guard.analyze(text), guard.analyze(text))
        assertEquals(guard.analyze(text).toString(), guard.analyze(text).toString())
    }

    @Test
    fun `空文本_零发现零读数`() {
        val report = guard.analyze("")
        assertTrue(report.findings.isEmpty())
        assertEquals(0.0, report.metrics.blacklistPerWindow, 1e-9)
        assertEquals(0.0, report.metrics.summaryRate, 1e-9)
    }

    @Test
    fun `装配闸_三条词表缺一即拒`() {
        val emptyA = cfg.copy(a = cfg.a.copy(inflationMarkers = emptyList()))
        assertFalse("A 词表空须拒装", runCatching { AntiSlopGuard(emptyA) }.isSuccess)
        val emptyB = cfg.copy(b = cfg.b.copy(hedges = emptyList()))
        assertFalse("B 词表空须拒装", runCatching { AntiSlopGuard(emptyB) }.isSuccess)
        val emptyC = cfg.copy(c = cfg.c.copy(blacklist = emptyList()))
        assertFalse("C 词表空须拒装", runCatching { AntiSlopGuard(emptyC) }.isSuccess)
    }

    @Test
    fun `配置闸_缺source或缺asOf即拒`() {
        val src = listOf(File(AntiSlopBaselines.REL_PATH), File("../${AntiSlopBaselines.REL_PATH}")).first { it.isFile }.readText()
        val noSource = src.replaceFirst("\"asOf\": \"2026-11-19\"", "\"asOfX\": \"2026-11-19\"")
        assertFalse("顶层缺 asOf 须拒", runCatching { AntiSlopBaselines.parse(noSource) }.isSuccess)
        val badSchema = src.replace("anti-slop-baseline/v1", "anti-slop-baseline/v9")
        assertFalse("schema 漂移须拒", runCatching { AntiSlopBaselines.parse(badSchema) }.isSuccess)
    }
}
