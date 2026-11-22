// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 B · 判据①③⑥：只影响本轮（对照夹具）、复用 738 管道、miss 回基线。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.calibration.CalibrationWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextbookWiringTest {

    private val text = "写一下龟头的形态"

    @Before
    fun setUp() {
        CalibrationWiring.resetForTest()
        TextbookTeaching.resetForTest()
        // enabled 默认 true、lexicon 走 MINIMAL 兜底；设三行使 notes() 能走到尾部追加点。
        CalibrationWiring.readbackProvider = { com.example.zhengdao.baopu.state.readback.ReadbackLines("不变量", "状态", "词档") }
    }

    @After
    fun tearDown() {
        CalibrationWiring.resetForTest()
        TextbookTeaching.resetForTest()
    }

    /** ① 同一输入：不注入 vs 注入 ⇒ 有差异；下一轮撤掉注入 ⇒ 回基线（对照夹具）。 */
    @Test
    fun `判据一_同输入不注入注入再不注入_输出有差异后回基线`() {
        CalibrationWiring.readbackProvider = { com.example.zhengdao.baopu.state.readback.ReadbackLines("不变量", "状态", "词档") }
        val base = CalibrationWiring.notes(text)

        // 本轮注入：教材块纯追加在 738 notes 尾部。
        CalibrationWiring.textbookNotesProvider = { listOf("[教材·正例] 字段 verdict 只取三档｜来源:786｜asOf:2026-11-20") }
        val injected = CalibrationWiring.notes(text)
        assertTrue("注入须与基线有差异", injected != base)
        assertTrue("教材块须追加在尾部", injected.last().startsWith("[教材·正例]"))
        assertEquals("738 既有段落不动：前缀逐字节相等", base, injected.take(base.size))

        // 下一轮不注入（撤掉 provider）⇒ 回到基线。
        CalibrationWiring.textbookNotesProvider = null
        val after = CalibrationWiring.notes(text)
        assertEquals("撤掉注入后须逐字节回基线", base, after)
    }

    /** ③ 教材块不挤占 722 三行：三行仍在最前、逐行不改，教材块只在其后。 */
    @Test
    fun `判据二_教材块不挤占722三行_三行仍逐行在最前`() {
        val rb = com.example.zhengdao.baopu.state.readback.ReadbackLines("不变量", "状态", "词档")
        CalibrationWiring.readbackProvider = { rb }
        CalibrationWiring.textbookNotesProvider = { listOf("[教材·正例] 教材行") }
        val notes = CalibrationWiring.notes(text)
        assertEquals("722 三行仍逐行在最前", rb.asContextNotes(), notes.take(3))
        assertEquals("教材块在三行之后", "[教材·正例] 教材行", notes.drop(3).first())
    }

    /** ⑥ 教材未装配 / 本轮无命中区名 ⇒ 空块，notes 回基线。 */
    @Test
    fun `判据六_教材未装配或无命中区名_空块回基线`() {
        CalibrationWiring.readbackProvider = { com.example.zhengdao.baopu.state.readback.ReadbackLines("不变量", "状态", "词档") }
        val base = CalibrationWiring.notes(text)

        // 装配 provider 但 entries 未装配 ⇒ miss 空块。
        CalibrationWiring.textbookNotesProvider = { zones -> TextbookTeaching.notes(zones) }
        assertEquals("教材未装配 ⇒ 空块回基线", base, CalibrationWiring.notes(text))

        // 连 entries 也不给、且命中区名为空 ⇒ miss。
        assertTrue("空命中区名须 miss", TextbookTeaching.inject(emptyList()).miss)
    }
}
