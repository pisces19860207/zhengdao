// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 判据①③⑥：装配缝的零读取／零增量／可回滚。
// 用**计数 provider**真读"索引被读了几次"，不靠 contains 恒真。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.readback.BodyReadback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalibrationWiringTest {

    private var obsReads = 0
    private var factReads = 0
    private var variantReads = 0
    private var readbackReads = 0

    private val obsJson = """{"entries":[{"id":"OBS-01","topic":"肩线形态与胸腰","source":"作者素材","asOf":"2026-11-18"},{"id":"OBS-02","topic":"潮红可见部位","source":"作者素材","asOf":"2026-11-18"}]}"""
    private val factJson = """{"slices":[{"id":"fact-001","kind":"fact","title":"分布","body":"长度呈连续分布而非常量","source":"事实底盘.md:L86","asOf":"2026-11-16","words":["分布"]}]}"""
    private val variantJson = """{"schema":"variant-spectrum/v1","source":"男性-真实变异谱","asOf":"2026-11-19","reality":"real","dimensions":[{"key":"bend_direction","name":"弯曲方向","pathological":false,"levels":[{"key":"up","name":"上翘","probability":42,"reality":"real"},{"key":"down","name":"下弯","probability":22,"reality":"real"},{"key":"left","name":"左偏","probability":18,"reality":"real"},{"key":"right","name":"右偏","probability":18,"reality":"real"}]},{"key":"girth_profile","name":"粗细分布","levels":[{"key":"a","name":"均匀","probability":50,"reality":"real"},{"key":"b","name":"根部偏粗","probability":50,"reality":"real"}]}]}"""

    @Before
    fun setUp() {
        CalibrationWiring.resetForTest()
        obsReads = 0
        factReads = 0
        variantReads = 0
        readbackReads = 0
        CalibrationWiring.readbackProvider = {
            readbackReads++
            BodyReadback.render(FormFixtures.state(), FormFixtures.anatomy).lines
        }
        CalibrationWiring.observableProvider = { obsReads++; obsJson }
        CalibrationWiring.factsProvider = { factReads++; factJson }
        CalibrationWiring.variantProvider = { variantReads++; variantJson }
    }

    @After
    fun tearDown() = CalibrationWiring.resetForTest()

    // ── ① 未命中：token 增量 0 + 索引零读取（两条断言）──────────────────

    @Test
    fun `判据一_未命中_token增量为零且索引零读取`() {
        val notes = CalibrationWiring.notes("今天天气不错，出去散步")
        assertEquals("未命中必须零注入", 0, notes.size)
        assertEquals("未命中索引零读取", 0, obsReads)
        assertEquals("未命中 facts 零读取", 0, factReads)
        assertEquals("未命中变体零读取", 0, variantReads)
    }

    @Test
    fun `判据一_空白输入零读取`() {
        assertEquals(0, CalibrationWiring.notes("").size)
        assertEquals(0, CalibrationWiring.notes(null).size)
        assertEquals(0, obsReads + factReads + variantReads + readbackReads)
    }

    @Test
    fun `判据一_L0风格命中不读索引_只读三行`() {
        val notes = CalibrationWiring.notes("一柱擎天")
        assertTrue("L0 应注入三行 + 1 提醒", notes.size >= 3)
        assertEquals("L0 不读 observable 索引", 0, obsReads)
        assertEquals("L0 不读 facts", 0, factReads)
        assertEquals("L0 不读变体", 0, variantReads)
        assertEquals("L0 读三行恰好 1 次", 1, readbackReads)
    }

    @Test
    fun `判据一_L1命中才读索引`() {
        val notes = CalibrationWiring.notes("写一下龟头的形态")
        assertTrue("L1 应带条目", notes.any { it.startsWith("[校准·") })
        assertEquals("L1 读 observable 索引 1 次", 1, obsReads)
        assertEquals("L1 读 facts 1 次", 1, factReads)
    }

    // ── ⑥ 可回滚：关开关 ⇒ 输出与"没有本单"逐字节一致 ──────────────────

    @Test
    fun `判据六_关开关后逐字节一致_且零读取`() {
        val text = "详细描述根部，写具体一点"
        val onNotes = CalibrationWiring.notes(text)
        assertTrue("开时确有注入", onNotes.isNotEmpty())

        // 归零读数基线：下面断言的是"关之后这次调用"的增量，不叠加上一次开态的读取。
        obsReads = 0
        factReads = 0
        variantReads = 0
        readbackReads = 0
        CalibrationWiring.enabled = { false }
        val offNotes = CalibrationWiring.notes(text)
        assertEquals("关时零注入", emptyList<String>(), offNotes)
        assertEquals("关时索引零读取", 0, obsReads)
        assertEquals("关时 facts 零读取", 0, factReads)
        assertEquals("关时三行零读取", 0, readbackReads)
    }

    @Test
    fun `判据六_未装配provider与关开关等价_均空表`() {
        val text = "写一下龟头的形态"
        val withProvider = CalibrationWiring.notes(text)
        assertTrue(withProvider.isNotEmpty())
        CalibrationWiring.resetForTest()
        val withoutProvider = CalibrationWiring.notes(text)
        assertEquals("未装配 ⇒ 空表 ⇒ 与没有本单逐字节一致", emptyList<String>(), withoutProvider)
    }

    // ── ③ 来源负例（wiring 侧）────────────────────────────────────────────

    @Test
    fun `判据三_缺源条目即便索引有也丢弃`() {
        CalibrationWiring.observableProvider = {
            obsReads++
            """{"entries":[{"id":"BAD-01","topic":"无来源条目","source":"","asOf":""}]}"""
        }
        CalibrationWiring.factsProvider = null
        val notes = CalibrationWiring.notes("写一下龟头的形态")
        assertTrue("三行仍在", notes.size >= 3)
        assertEquals("无源条目一条都不许注", 0, notes.count { it.startsWith("[校准·") })
    }
}
