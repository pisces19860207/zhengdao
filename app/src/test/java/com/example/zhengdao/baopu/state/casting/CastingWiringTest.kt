// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百五十二批 · 判据①②③④⑤⑥：外观卡装配缝接通与否。
// 口径：夹具跑真轮——真 722 三行（FormFixtures＋BodyReadback）、真 747 档名（AnchorFields）、
// 真 817 判定（CastingSheet/SceneCheck），只用假卡源（roleId→卡的来源归 853）。
// 断言一律读"第几条/哪一行/什么态"，不读 contains 恒真。
package com.example.zhengdao.baopu.state.casting

import com.example.zhengdao.baopu.calibration.CalibrationWiring
import com.example.zhengdao.baopu.state.anchor.AnchorFields
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.readback.BodyReadback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CastingWiringTest {

    private val obsJson = """{"entries":[{"id":"OBS-01","topic":"肩线形态与胸腰","source":"作者素材","asOf":"2026-11-18"},{"id":"OBS-02","topic":"潮红可见部位","source":"作者素材","asOf":"2026-11-18"}]}"""
    private val factJson = """{"slices":[{"id":"fact-001","kind":"fact","title":"分布","body":"长度呈连续分布而非常量","source":"事实底盘.md:L86","asOf":"2026-11-16","words":["分布"]}]}"""

    @Before
    fun setUp() {
        CalibrationWiring.resetForTest()
        CastingRuntime.resetForTest()
        CalibrationWiring.readbackProvider = { BodyReadback.render(FormFixtures.state(), FormFixtures.anatomy).lines }
        CalibrationWiring.observableProvider = { obsJson }
        CalibrationWiring.factsProvider = { factJson }
    }

    @After
    fun tearDown() {
        CastingRuntime.detach()
        CalibrationWiring.resetForTest()
    }

    /** 逐字取既有档名（817 与 747 同一张表），一个档都不在测试里新造。 */
    private fun word(fieldId: String, last: Boolean = false): String {
        val words = AnchorFields.spec(fieldId).words
        return if (last) words.last() else words.first()
    }

    private fun card(role: String, extra: Map<String, List<String>> = emptyMap()) = CastingCard(
        roleId = role,
        profileId = "profile-$role",
        isMain = true,
        values = mapOf(
            "face.shape" to listOf(word("face.shape")),
            "body.frame" to listOf(word("body.frame")),
            "body.fat" to listOf(word("body.fat")),
        ) + extra,
    )

    // ── 判据① 有证据才算接通：注入位出 `[外观]` 行、日志出 `[casting]` 行 ──────

    @Test
    fun `判据一_夹具一轮_写前注入与写后校验都出casting行`() {
        CastingRuntime.install(cards = { listOf(card("hero")) })
        val notes = CalibrationWiring.notes(TRIGGER)
        assertEquals("注入位应恰好一条外观行", 1, notes.count { it.startsWith(CastingRuntime.LINE_HEAD) })

        val report = CastingRuntime.onSceneWritten(
            sceneId = "S01",
            observed = mapOf("face.shape" to listOf(word("face.shape"))),
            sceneTraces = setOf("trace.sweat"),
        )
        val lines = CastingRuntime.receiptLines()
        println("── 852 判据① 夹具轮回执 ──")
        lines.forEach { println(it) }
        assertTrue("写前必须有 [casting] 行", lines.any { it.startsWith("${CastingRuntime.TRACE_TAG} 写前 ") })
        assertTrue("写后必须有 [casting] 行", lines.any { it.startsWith("${CastingRuntime.TRACE_TAG} 写后 ") })
        assertEquals("真档一致应判通过", CheckState.PASS, report.continuity.state)
        assertEquals("汗是状态痕迹，鲜活应判通过", CheckState.PASS, report.vitality.state)
    }

    @Test
    fun `判据一_未装卡源时一条外观行都不出`() {
        CastingRuntime.install()
        assertEquals("只挂缝不绑源 ⇒ 零注入", 0, CalibrationWiring.notes(TRIGGER).count { it.startsWith(CastingRuntime.LINE_HEAD) })
        assertTrue("缝在，但没有数据可回执", CastingRuntime.receiptLines().isEmpty())
    }

    // ── 判据② 开关关／未装配 ⇒ 与"没有本单"逐字节一致 ──────────────────────

    @Test
    fun `判据二_挂缝不绑卡源与没装本单逐字节一致`() {
        val withoutBatch = CalibrationWiring.notes(TRIGGER)
        assertTrue("夹具轮本身要有内容，否则这条断言是空的", withoutBatch.isNotEmpty())
        CastingRuntime.install()
        assertEquals("装了缝仍是逐字节一致", withoutBatch, CalibrationWiring.notes(TRIGGER))
    }

    @Test
    fun `判据二_关总开关后归零_detach 后回到基线`() {
        val baseline = CalibrationWiring.notes(TRIGGER)
        CastingRuntime.install(cards = { listOf(card("hero")) })
        assertTrue("装卡后确有注入", CalibrationWiring.notes(TRIGGER).size > baseline.size)
        CalibrationWiring.enabled = { false }
        assertEquals("开关关 ⇒ 主链空表", emptyList<String>(), CalibrationWiring.notes(TRIGGER))
        CastingRuntime.detach()
        CalibrationWiring.enabled = { true }
        assertEquals("detach 即回滚，逐字节等于没有本单", baseline, CalibrationWiring.notes(TRIGGER))
    }

    // ── 判据③ 尾部纯追加（结构断言：排在本轮 notes 最末，且在 798 块之后）──────

    @Test
    fun `判据三_外观行排最后_教材块在前_都不动三行`() {
        CalibrationWiring.textbookNotesProvider = { listOf("[教材] 段B块") }
        CastingRuntime.install(cards = { listOf(card("hero")) })
        val notes = CalibrationWiring.notes(TRIGGER)
        val castingAt = notes.indexOfFirst { it.startsWith(CastingRuntime.LINE_HEAD) }
        assertEquals("外观块必须是本轮最后一段", notes.size - 1, castingAt)
        assertTrue("外观块排在 798 教材块之后", notes.indexOf("[教材] 段B块") < castingAt)
        assertEquals("722 三行仍在最前，一行都没被挤走", 3, notes.take(3).count { it.isNotBlank() })
    }

    // ── 判据④ 注入 ≤120 字；722 三行与敏感层预算不变；下体不进注入 ──────────

    @Test
    fun `判据四_摘要不超120字_三行与敏感层条目一字不动`() {
        val before = CalibrationWiring.notes(TRIGGER)
        val genitalWord = word("g.prepuce")
        CastingRuntime.install(cards = { listOf(card("hero", mapOf("g.prepuce" to listOf(genitalWord)))) })
        val after = CalibrationWiring.notes(TRIGGER)

        val body = after.single { it.startsWith(CastingRuntime.LINE_HEAD) }.removePrefix("${CastingRuntime.LINE_HEAD} ")
        assertTrue("摘要 ${body.length} 字须 ≤$SUMMARY_LIMIT", body.length <= SUMMARY_LIMIT)
        assertEquals("738 自己的条目（[校准·）一条不多一条不少", before.count { it.startsWith("[校准·") }, after.count { it.startsWith("[校准·") })
        assertEquals("前段整体逐字不动，外观块只追加在尾", before, after.take(before.size))
        assertTrue("下体档「$genitalWord」不许进注入", !body.contains(genitalWord))
    }

    @Test
    fun `判据四_满卡也要在120字内按完整段截断`() {
        val full = card(
            "crowded",
            mapOf(
                "face.eyes" to listOf(word("face.eyes")),
                "face.nose" to listOf(word("face.nose")),
                "face.lips" to listOf(word("face.lips")),
                "body.muscle" to listOf(word("body.muscle")),
                "part.pec.volume" to listOf(CastingRefs.field("part.pec.volume").words().first()),
                "mark.site" to listOf(CastingRefs.field("mark.site").words().first()),
            ),
        )
        val summary = CastingSheet.of(full).summary
        println("── 852 判据④ 满卡摘要（${summary.length} 字）── $summary")
        assertTrue("满卡须被截到 ≤$SUMMARY_LIMIT，实得 ${summary.length}", summary.length <= SUMMARY_LIMIT)
        assertTrue("截断处不许留逗号尾巴", !summary.endsWith("，") && !summary.endsWith("、"))
    }

    // ── 判据⑤ 写后只在场边界触发：一场一次，多段不触发 ────────────────────

    @Test
    fun `判据五_多段注入不涨场数_两场写后恰好两次`() {
        CastingRuntime.install(cards = { listOf(card("hero")) })
        repeat(4) { CalibrationWiring.notes(TRIGGER) }
        assertEquals("注入被调 4 次，场边界仍是 0", 0, CastingRuntime.writtenSceneCount())

        CastingRuntime.onSceneWritten("S01", mapOf("face.shape" to listOf(word("face.shape"))), setOf("trace.sweat"))
        assertEquals("一场一次", 1, CastingRuntime.writtenSceneCount())
        CastingRuntime.onSceneWritten("S02", mapOf("face.shape" to listOf(word("face.shape"))), setOf("trace.fatigue"))
        assertEquals("两场两次", 2, CastingRuntime.writtenSceneCount())
        assertEquals("写后回执与场数等条", 2, CastingRuntime.receiptLines().count { it.contains(" 写后 ") })
    }

    // ── 判据⑥ 不改 817 判定、不改 738 注入顺序 ────────────────────────────

    @Test
    fun `判据六_装配层逐字复用817产出_矛盾照判红不粉饰`() {
        val hero = card("hero")
        CastingRuntime.install(cards = { listOf(hero) })
        val line = CalibrationWiring.notes(TRIGGER).last()
        assertEquals("摘要一字不改，逐字等于 CastingSheet 的产出", "${CastingRuntime.LINE_HEAD} ${CastingSheet.of(hero).summary}", line)

        val report = CastingRuntime.onSceneWritten(
            sceneId = "S01",
            observed = mapOf("face.shape" to listOf(word("face.shape", last = true))),
            sceneTraces = emptySet(),
        )
        println("── 852 判据⑥ 写后校验明细 ──\n${report.line()}")
        assertEquals("描写与卡矛盾必须由 817 判红", CheckState.RED, report.continuity.state)
        assertEquals("整体取最严态", CheckState.RED, report.overall)
    }

    @Test
    fun `判据六_场里没有卡时空表照实判不编通过`() {
        CastingRuntime.install(cards = { emptyList() })
        val report = CastingRuntime.onSceneWritten("S01", emptyMap(), emptySet(), cards = emptyList())
        assertEquals("无描写 ⇒ 照 817 口径出「无描写」", "无描写", report.continuity.code)
        assertEquals("零痕迹 ⇒ 像标本（提示，不是红）", CheckState.HINT, report.vitality.state)
        assertEquals("卡源空也不许伪造注入", emptyList<String>(), CalibrationWiring.notes(TRIGGER).filter { it.startsWith(CastingRuntime.LINE_HEAD) })
    }

    private companion object {
        /** 与 738 既有夹具同口径的 L1 触发句（命中区名 ⇒ 真读索引）。 */
        const val TRIGGER = "写一下龟头的形态"
    }
}
