// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 判据①②⑤：触发判定 + 分级硬上限 + 三病对照夹具。
// 三行一律用 722 真夹具（FormFixtures + BodyReadback.render），本单不重造格式。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.readback.BodyReadback
import com.example.zhengdao.baopu.state.readback.ReadbackLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationInjectTest {

    private fun readback(): ReadbackLines =
        BodyReadback.render(FormFixtures.state(), FormFixtures.anatomy).lines

    private fun entries(n: Int, src: Boolean = true): List<SourceEntry> =
        (1..n).map {
            SourceEntry(
                id = "OBS-$it",
                text = "可观察描述第${it}条：肩线形态与胸腰比例的可观察写法".take(60),
                source = if (src) "observable-index.json:OBS-$it" else "",
                asOf = if (src) "2026-11-18" else "",
            )
        }

    private fun decision(text: String) = CalibrationTrigger.decide(text)

    // ── ① 触发判定：轻量、命中/未命中 ────────────────────────────────────

    @Test
    fun `判据一_未命中calibrate假_空决策零条`() {
        val d = decision("今天天气不错，我们去散步吧")
        assertFalse(d.calibrate)
        assertEquals(CalibrationLevel.OFF, d.level)
        val inj = CalibrationInject.build(d, readback(), entries(8), emptyList())
        assertEquals(0, inj.notes.size)
        assertEquals(0, inj.tokenDelta)
    }

    @Test
    fun `判据一_部位名命中真_级别至少L1`() {
        val d = decision("描写一下龟头冠和冠状沟的样子")
        assertTrue(d.calibrate)
        assertTrue(d.level.rank >= CalibrationLevel.L1.rank)
        assertTrue("命中部位须带回匹配词", d.matchedZones.isNotEmpty())
    }

    // ── ② 分级硬上限 ──────────────────────────────────────────────────────

    @Test
    fun `判据二_L0三行不超200token且逐行等于722原文`() {
        val rb = readback()
        // 仅风格命中 ⇒ L0（无部位/状态/请求），不注入索引条目。
        val d = decision("一柱擎天般巍峨")
        assertEquals(CalibrationLevel.L0, d.level)
        val inj = CalibrationInject.build(d, rb, entries(20), emptyList())
        assertEquals("L0 条目数必须 0", 0, inj.entryCount)
        assertEquals("L0 三行逐行等于 722", rb.asContextNotes(), inj.notes.take(3))
        assertTrue("L0 token ${inj.tokenDelta}", inj.tokenDelta <= ReadbackLines.MAX_TOTAL_TOKENS)
        assertEquals("L0 token 须等于唯一尺子实算", TokenEstimator.estimate(inj.notes.joinToString("\n")), inj.tokenDelta)
    }

    @Test
    fun `判据二_L1条目不超3条`() {
        val d = decision("写一下龟头的形态")
        assertEquals(CalibrationLevel.L1, d.level)
        val inj = CalibrationInject.build(d, readback(), entries(50), emptyList())
        // 阈值来自派工单硬上限，不引用被测常量（防"实现改坏、期望跟着改"的自证陷阱）。
        assertTrue("L1 实到 ${inj.entryCount} 条，须 ≤3", inj.entryCount <= 3)
        assertTrue("L1 必须真的带条目（负例护栏）", inj.entryCount > 0)
    }

    @Test
    fun `判据二_L2显式详细条目不超8条`() {
        val d = decision("详细描述一下阴囊和精索，要具体")
        assertEquals(CalibrationLevel.L2, d.level)
        val inj = CalibrationInject.build(d, readback(), entries(99), emptyList())
        assertTrue("L2 实到 ${inj.entryCount} 条，须 ≤8", inj.entryCount <= 8)
        assertEquals("L2 应吃满 8 条", 8, inj.entryCount)
    }

    @Test
    fun `判据二_每条正文不超60字且全文不注入`() {
        val d = decision("详细描述根部，要具体")
        val long = listOf(
            SourceEntry("OBS-01", "面".repeat(2000), "observable-index.json", "2026-11-18"),
        )
        val inj = CalibrationInject.build(d, readback(), long, emptyList())
        val entryLine = inj.notes.first { it.startsWith("[校准·") }
        val body = entryLine.substringAfter("] ").substringBefore("｜来源:")
        assertTrue("条目正文 ${body.length} 字须 ≤60", body.length <= 60)
        assertFalse("不得出现全文（2000 字）", inj.notes.any { it.length > 200 })
    }

    // ── ③ 来源齐：无 source/asOf 丢弃 ────────────────────────────────────

    @Test
    fun `判据三_无源条目被丢弃且计数`() {
        val d = decision("详细描述根部，要具体")
        val mix = entries(4, src = true) + entries(3, src = false)
        val inj = CalibrationInject.build(d, readback(), mix, emptyList())
        assertEquals("丢弃数须为 3", 3, inj.droppedNoSource)
        assertTrue("注入条目全带 asOf", inj.notes.filter { it.startsWith("[校准·") }.all { "asOf:20" in it })
        assertTrue("注入条目全带来源", inj.notes.filter { it.startsWith("[校准·") }.all { "｜来源:" in it })
    }

    @Test
    fun `判据三_全无源则零条目注入_but三行仍在`() {
        val d = decision("写一下龟头的形态")
        val inj = CalibrationInject.build(d, readback(), entries(10, src = false), emptyList())
        assertEquals("无源 ⇒ 0 条", 0, inj.entryCount)
        assertEquals("无源不影响 L0 三行", 3, inj.notes.size)
    }

    // ── ⑤ 三病对照夹具 ────────────────────────────────────────────────────

    @Test
    fun `判据五_网文腔_词档行出现且带禁用默认词提醒`() {
        val d = decision("写得像一柱擎天那样")
        assertEquals(CalibrationStyle.WEBNOVEL, d.style)
        val inj = CalibrationInject.build(d, readback(), emptyList(), emptyList())
        assertTrue("词档行须出现", inj.hasVocabLine)
        assertTrue("须带禁用默认词提醒", inj.vocabReminder)
        assertTrue("提醒文案含禁用默认词", inj.notes.any { "禁用默认词" in it })
    }

    @Test
    fun `判据五_医学腔_输出带非医疗判断提示`() {
        val d = decision("这个症状要怎么治疗和预防")
        assertEquals(CalibrationStyle.MEDICAL, d.style)
        val inj = CalibrationInject.build(d, readback(), emptyList(), emptyList())
        assertTrue("须带非医疗判断提示", inj.medicalNotice)
        assertTrue("提示文案含非医疗判断", inj.notes.any { "非医疗判断" in it })
    }

    @Test
    fun `判据五_塌缩请求_变体档出现且不全给同一档`() {
        val d = decision("描述一下")
        assertTrue("塌缩须命中", d.collapse)
        val variants = listOf("上翘", "下弯", "左偏", "右偏")
        val inj = CalibrationInject.build(d, readback(), emptyList(), variants)
        assertEquals("变体档全部出现", variants, inj.variantLevelsShown)
        assertTrue("至少两档（不全同一档）", inj.variantLevelsShown.distinct().size >= 2)
        assertTrue("notes 里含变体行", inj.notes.any { it.startsWith("[变体] ") })
    }

    @Test
    fun `判据五_三档风格互串负例_中性无提醒`() {
        val inj = CalibrationInject.build(decision("根部"), readback(), emptyList(), emptyList())
        assertFalse("中性不应带禁用默认词提醒", inj.vocabReminder)
        assertFalse("中性不应带非医疗判断", inj.medicalNotice)
    }
}
