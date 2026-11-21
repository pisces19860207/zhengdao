// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 校准装配判据①②③④⑥（JVM 侧）。
// 真机轮是同批的另一半（验收硬线）；这一层先把"装配对不对"钉死：
//   ① 三行的数字必须与 660 BodyState 逐字对上（不是与某个写死的字符串对上）；
//   ② 无设定/无口径表/缺来源 ⇒「未设定」，一条默认身材都不许编；
//   ③ 敏感层每条自带 source+asOf，未命中一条都不给，伪造条目进不了 notes；
//   ④ 三行合计 ≤200 token（含状态行逐场变的最坏情况）；⑥ 应急关闭后与"没有本单"一致。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.baopu.state.form.BodyModel
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.physiology.ActivityBand
import com.example.zhengdao.baopu.state.physiology.AmbientTempBand
import com.example.zhengdao.baopu.state.physiology.DurationBand
import com.example.zhengdao.baopu.state.physiology.EmotionBand
import com.example.zhengdao.baopu.state.physiology.HumidityBand
import com.example.zhengdao.baopu.state.physiology.IndividualBand
import com.example.zhengdao.baopu.state.physiology.PhysioInputs
import com.example.zhengdao.baopu.state.readback.BodyReadback
import com.example.zhengdao.baopu.state.readback.ReadbackLines
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class CalibrationAssemblyTest {

    companion object {
        /** 固定名沙箱：整个类只建一个目录，口径表只在缺失/大小不符时才拷（控水位）。 */
        private val ws = File(System.getProperty("java.io.tmpdir"), "zd774-ws")
        private val facts = File(ws, "docs/facts")
        private val ledger = File(ws, "ledger.jsonl")

        /** 与真实时钟无关的 mtime 基准（保证测试内的指纹推进单调）。 */
        private const val BASE_MS = 1_700_000_000_000L

        @JvmStatic
        @BeforeClass
        fun sandbox() {
            facts.mkdirs()
            val real = File(FormFixtures.repoRoot(), "docs/facts")
            real.listFiles()?.filter { it.extension == "json" }?.forEach { src ->
                val dst = File(facts, src.name)
                if (!dst.isFile || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
            }
            assertTrue("沙箱必须拿到 660 口径表", facts.listFiles()!!.isNotEmpty())
        }
    }

    private val specA: String get() = """{"heightCm":176.0,"weightKg":70.5,"bodyFatPct":25.0,"muscleGrade":3,${skinNode()}"source":"作者设定","asOf":"2026-11-21"}"""
    private val specB: String get() = """{"heightCm":181.0,"weightKg":78.25,"bodyFatPct":22.5,"muscleGrade":4,"ageBand":"adult",${skinNode()}"source":"作者设定","asOf":"2026-11-21"}"""

    /**
     * 合成语料的作者设定：660 的 SpecGuard 要求 669 词表 13 区每区都带 source+asOf 的色值，
     * 缺任何一区整份拒收（⇒「未设定」）。色值由设定文件填，装配层一个都不补。
     * 龟头比柱身深（INV-03 的行为侧），所以 glans/corona 取深于 shaft 的一档。
     */
    private val palette = linkedMapOf(
        "radix" to "#c89a7a", "shaft" to "#c89a7a", "raphe" to "#b98a6a",
        "glans" to "#a86a5a", "corona" to "#a86a5a", "sulcus" to "#b07a62",
        "frenulum" to "#b98a6a", "meatus" to "#9a5a4a", "prepuce" to "#c89a7a",
        "scrotum" to "#b08060", "testis" to "#b08060", "cord" to "#b08060", "mons" to "#c89a7a",
    )

    private fun skinNode(source: String = "作者设定", asOf: String = "2026-11-21", noSourceFor: String? = null): String =
        "\"skin\":{\"zoneColors\":{" + palette.entries.joinToString(",") { (zone, hex) ->
            "\"$zone\":{\"hex\":\"$hex\",\"source\":\"${if (zone == noSourceFor) "" else source}\",\"asOf\":\"$asOf\"}"
        } + "}}"

    /** 设定文件 mtime 手工推进：同一毫秒内连写两次也要让指纹看见变化。 */
    private var counter = 0L

    @Before
    fun setUp() {
        CalibrationAssembly.resetForTest()
        CalibrationAssembly.wire({ ws }, { ledger })
        ledger.delete()
        specFile().delete()
    }

    @After
    fun tearDown() = CalibrationAssembly.resetForTest()

    // ── ① 真装配：数值与 660 逐字对上 ────────────────────────────────────

    @Test
    fun `判据一_不变量行的数字就是660BodyState里的数`() {
        writeSpec(specA)
        val setting = requireNotNull(CalibrationBodySource.parseSetting(specA))
        val state = BodyModel.load(setting.spec, FormFixtures.repoRoot())
        val lines = CalibrationAssembly.lines()
        assertEquals(
            "不变量行必须逐字等于 722 对 660 的渲染",
            ReadbackLines.clip(BodyReadback.invariantLine(state)), lines.invariant,
        )
        val nums = numbers(lines.invariant)
        assertTrue("身高", nums.contains(176.0))
        assertTrue("体脂", nums.contains(25.0))
        assertTrue("肌肉档", nums.contains(3.0))
        val shoulder = state.frame.ratios.shoulder.value * 176.0
        assertTrue("肩峰宽须与 660 的比例推算对上：$lines.invariant", nums.any { close(it, shoulder) })
        val waist = state.frame.sections.first { it.id == "waist" }.widthCm.value
        assertTrue("腰宽须与 660 截面对上：$lines.invariant", nums.any { close(it, waist) })
    }

    @Test
    fun `判据一_经738出口三行在notes最前`() {
        writeSpec(specA)
        val lines = CalibrationAssembly.lines()
        val notes = CalibrationWiring.notes("详细描述根部和龟头的样子")
        assertEquals("三行原样、顺序不变", lines.asContextNotes(), notes.take(3))
        assertTrue("命中下体区必须有带源条目", notes.any { it.startsWith("[校准·") })
    }

    // ── ② 降级：未设定，不编 ─────────────────────────────────────────────

    @Test
    fun `判据二_无设定出未设定_不许编默认身材`() {
        val inv = CalibrationAssembly.lines().invariant
        assertTrue(inv.contains(CalibrationAssembly.UNSET))
        assertFalse("未设定行里一个尺寸都不许出现", Regex("""\d+(\.\d+)?cm""").containsMatchIn(inv))
        assertFalse(inv.contains("体脂"))
    }

    @Test
    fun `判据二_设定缺来源即不算数`() {
        writeSpec("""{"heightCm":190.0,"weightKg":90.0,"bodyFatPct":30.0,"muscleGrade":5}""")
        val inv = CalibrationAssembly.lines().invariant
        assertTrue("缺 source/asOf ⇒ 未设定", inv.contains(CalibrationAssembly.UNSET))
        assertFalse("190 这类数不许从默认路径漏进来", inv.contains("190"))
    }

    @Test
    fun `判据二_一区色值缺来源_整份不算数_不许补色`() {
        writeSpec(
            """{"heightCm":176.0,"weightKg":70.5,"bodyFatPct":25.0,"muscleGrade":3,""" +
                skinNode(noSourceFor = "glans") + ""","source":"作者设定","asOf":"2026-11-21"}"""
        )
        val inv = CalibrationAssembly.lines().invariant
        assertTrue("缺一区色源 ⇒ 660 拒收 ⇒ 未设定：$inv", inv.contains(CalibrationAssembly.UNSET))
        assertFalse("装配层不许拿默认色把这一区糊过去：$inv", inv.contains("#"))
        assertEquals("没身体就没有敏感层条目", 0, CalibrationAssembly.entries(listOf("龟头")).size)
    }

    @Test
    fun `判据二_口径表缺失不崩`() {
        writeSpec(specA)
        val bak = File(ws, "facts-bak")
        assertTrue("重命名口径表", facts.renameTo(bak))
        try {
            val lines = CalibrationAssembly.lines()
            assertTrue("没有口径表就没有身体数：$lines.invariant", lines.invariant.contains(CalibrationAssembly.UNSET))
            assertTrue(lines.vocab.contains(ReadbackLines.UNWIRED))
            assertSlotsUnwired(lines.status)
        } finally {
            assertTrue("还原口径表", bak.renameTo(facts))
        }
    }

    /**
     * 判据②的状态行形状：四项一律留位，且**写明前置单**（工单追加：缺项＝"未接入＋前置单号"）。
     * 658 已在本基线（3669b211c），所以 722 里那句"（658未合）"是过时话头，装配层按事实改写。
     */
    @Test
    fun `判据二_状态行四项留位并写明前置单`() {
        val status = CalibrationAssembly.lines().status
        assertSlotsUnwired(status)
        assertFalse("658 已合入基线，不许再写'658未合'：$status", status.contains("658未合"))
        assertTrue("留位要交代差在哪张单：$status", status.contains("前置单"))
        assertEquals(0, CalibrationPhysiology.wiredCount())
    }

    /** 逐场档有了、但没交出出处 ⇒ 一条都不算读数（"疲劳=清醒"就是这条红线）。 */
    @Test
    fun `严禁默认值_场景档缺出处就不算读数`() {
        CalibrationPhysiology.sceneProvider = { PhysioScene(hardScene(), "", "") }
        val status = CalibrationAssembly.lines().status
        assertSlotsUnwired(status)
        assertFalse("缺 source/asOf 的产出不能顶成读数：$status", status.contains("疲劳:清醒"))
    }

    @Test
    fun `判据二_658真读数接上_出汗心率疲劳换档`() {
        writeSpec(specA)
        val before = CalibrationAssembly.lines()
        CalibrationPhysiology.sceneProvider = { PhysioScene(hardScene(), "658夹具·剧烈长时", "2026-11-21") }
        val after = CalibrationAssembly.lines()
        assertNotEquals("状态行逐场变", before.status, after.status)
        listOf("出汗", "心率档", "疲劳").forEach { item ->
            assertFalse("$item 该换成 658 的档名：$after", after.status.contains("$item:${ReadbackLines.UNWIRED}"))
        }
        assertEquals(3, CalibrationPhysiology.wiredCount())
    }

    /** turgor 不是 658 的产出（658 四状态＝体温偏移/心率档/出汗/疲劳），它在 660 侧属命中才注入的下体信息。 */
    @Test
    fun `判据二_turgor不混进L0状态行_继续挂前置单`() {
        CalibrationPhysiology.sceneProvider = { PhysioScene(hardScene(), "658夹具·剧烈长时", "2026-11-21") }
        val status = CalibrationAssembly.lines().status
        assertTrue("turgor 仍留位：$status", status.contains("turgor:${ReadbackLines.UNWIRED}"))
        assertTrue("turgor 的话头要点明另挂单：$status", status.contains("turgor"))
    }

    @Test
    fun `状态行可逐场变_且不许反向污染不变量行`() {
        writeSpec(specA)
        val before = CalibrationAssembly.lines()
        CalibrationPhysiology.sceneProvider = { PhysioScene(hardScene(), "658夹具·剧烈长时", "2026-11-21") }
        val after = CalibrationAssembly.lines()
        assertNotEquals("状态行逐场变", before.status, after.status)
        assertEquals("不变量行一字不许被状态行带跑", before.invariant, after.invariant)
        assertEquals("词档行也不许被带跑", before.vocab, after.vocab)
    }

    /** 658 的入参夹具：全是极端档，产出必然非零——用来证明"接上了就换字"，不是拿基线糊。 */
    private fun hardScene(): PhysioInputs = PhysioInputs(
        ambientTemp = AmbientTempBand.HOT,
        humidity = HumidityBand.SATURATED,
        activity = ActivityBand.INTENSE,
        duration = DurationBand.PROLONGED,
        emotion = EmotionBand.INTENSE,
        individual = IndividualBand.SENSITIVE,
    )

    private fun assertSlotsUnwired(status: String) {
        listOf("turgor", "出汗", "心率档", "疲劳").forEach { item ->
            assertTrue("状态行应留位「$item」：$status", status.contains("$item:${ReadbackLines.UNWIRED}"))
        }
    }

    // ── ③ 来源纪律与触发式敏感层 ─────────────────────────────────────────

    @Test
    fun `判据三_敏感层每条自带来源_未命中一条都不给`() {
        writeSpec(specA)
        val hit = CalibrationAssembly.entries(listOf("龟头"))
        assertTrue("命中区名才有条目", hit.isNotEmpty())
        assertTrue("每条都得带 source+asOf", hit.all { it.hasSource })
        assertEquals("未命中（无区名）⇒ 零条目", 0, CalibrationAssembly.entries(emptyList()).size)
    }

    @Test
    fun `判据三_伪造条目进不了notes`() {
        writeSpec(specA)
        CalibrationWiring.observableProvider = {
            """{"entries":[{"id":"FAKE-1","topic":"龟头无来源","source":"","asOf":""}]}"""
        }
        CalibrationWiring.factsProvider = { "" }
        val notes = CalibrationWiring.notes("详细描述龟头")
        assertTrue("无源条目一条都不许注", notes.none { it.contains("FAKE-1") })
        val cited = notes.filter { it.startsWith("[校准·") }
        assertTrue(cited.isNotEmpty())
        assertTrue("每条都得写来源与 asOf", cited.all { it.contains("｜来源:") && it.contains("｜asOf:") })
    }

    @Test
    fun `判据三_FactsProvider拿到711与719真源`() {
        val obs = CalibrationSource.usable(CalibrationSource.fromObservableIndex(CalibrationAssembly.factsJson("observable-index.json")))
        val slices = CalibrationSource.usable(CalibrationSource.fromFactSlices(CalibrationAssembly.factsJson("fact-slices.json")))
        assertTrue("711 索引读不到就没有带源条目", obs.isNotEmpty())
        assertTrue("719 事实片读不到就没有带源条目", slices.isNotEmpty())
    }

    @Test
    fun `追加_未命中零注入且不写回执不落账`() {
        writeSpec(specA)
        assertEquals(0, CalibrationWiring.notes("今天天气不错，出去散步").size)
        assertEquals(null, CalibrationGovernance.lastReceipt)
        assertFalse("未命中不进日志", ledger.isFile)
    }

    // ── ④ 预算 ───────────────────────────────────────────────────────────

    @Test
    fun `判据四_三行合计不超200token_含状态行最坏情况`() {
        writeSpec(specA)
        assertBudget(CalibrationAssembly.lines())
        CalibrationPhysiology.sceneProvider = { PhysioScene(hardScene(), "658夹具·剧烈长时·出处署名", "2026-11-21") }
        assertBudget(CalibrationAssembly.lines())
    }

    // ── 追加三：重建是内部动作，改设定不重启即生效 ────────────────────────

    @Test
    fun `追加三_改设定不重启即生效`() {
        writeSpec(specA)
        val a = CalibrationWiring.notes("详细描述根部")
        writeSpec(specB)
        val b = CalibrationWiring.notes("详细描述根部")
        assertTrue("第一轮读到 176：$a", a.any { it.contains("176cm") })
        assertTrue("第二轮必须读到改后的 181：$b", b.any { it.contains("181cm") })
    }

    @Test
    fun `追加三_词表跟669走`() {
        writeSpec(specA)
        val names = CalibrationAssembly.lexicon().zoneNames
        assertTrue("词表来自 669，不能空", names.isNotEmpty())
        assertTrue(names.contains("根部"))
    }

    // ── ⑥ 回滚：治理级应急关闭（一次性、需确认、留痕）─────────────────────

    @Test
    fun `判据六_应急关闭后与没有本单逐字节一致`() {
        writeSpec(specA)
        val text = "详细描述根部"
        val on = CalibrationWiring.notes(text)
        assertTrue(on.isNotEmpty())
        assertTrue("治理级确认才关得掉", CalibrationGovernance.halt("治理", CalibrationGovernance.CONFIRM))
        assertEquals("关 ⇒ 空表", emptyList<String>(), CalibrationWiring.notes(text))
        assertEquals(emptyList<String>(), CalibrationWiring.notes("写一段身体描写"))
        assertTrue(CalibrationGovernance.release("治理", CalibrationGovernance.CONFIRM))
        assertEquals("解除后与关之前逐字节一致", on, CalibrationWiring.notes(text))
        assertTrue("应急关闭要留痕", ledger.isFile && ledger.readText().contains("应急关闭"))
    }

    @Test
    fun `判据六_角色或确认词不对就关不掉`() {
        writeSpec(specA)
        assertFalse(CalibrationGovernance.halt("模型", CalibrationGovernance.CONFIRM))
        assertFalse(CalibrationGovernance.halt("治理", "随便"))
        assertTrue(CalibrationGovernance.on())
        assertTrue(CalibrationWiring.notes("详细描述根部").isNotEmpty())
    }

    @Test
    fun `判据六_工作区开关位关得掉_但不认伪造确认`() {
        writeSpec(specA)
        val text = "详细描述根部"
        val on = CalibrationWiring.notes(text)
        assertTrue(on.isNotEmpty())
        // 文件在但角色/确认词不对 ⇒ 不算治理按下 ⇒ 照常注入（开关位不是"谁改文件都能关"）
        writeHalt("""{"who":"模型","confirm":"随便"}""")
        assertEquals("密封不过 ⇒ 与没关时逐字节一致", on, CalibrationWiring.notes(text))
        writeHalt("""{"who":"治理","confirm":"确认应急关闭"}""")
        assertEquals("关 ⇒ 空表", emptyList<String>(), CalibrationWiring.notes(text))
        assertTrue("开关位生效要留痕", ledger.readText().contains("工作区开关位"))
        haltFile().delete()
        assertEquals("删位即恢复，不重启、与关之前逐字节一致", on, CalibrationWiring.notes(text))
    }

    @Test
    fun `追加_回执与账本只留条数与类别`() {
        writeSpec(specA)
        CalibrationWiring.notes("详细描述龟头")
        val receipt = CalibrationGovernance.lastReceipt
        assertNotNull("命中就该有回执", receipt)
        val text = receipt!!.describe() + CalibrationGovernance.snapshot() + ledger.readText()
        assertFalse("尺寸数值不进日志：$text", text.contains("mm"))
        assertFalse("长度单位不进日志：$text", text.contains("cm"))
        assertFalse("具体区名不进日志：$text", text.contains("龟头"))
        assertTrue("得写清注入了几条：$text", text.contains("已注入"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun assertBudget(lines: ReadbackLines) {
        val tokens = TokenEstimator.estimate(lines.asText())
        assertTrue("三行 $tokens token 不得超 ${ReadbackLines.MAX_TOTAL_TOKENS}：${lines.asText()}",
            tokens <= ReadbackLines.MAX_TOTAL_TOKENS)
        assertTrue("每行不得超 ${ReadbackLines.MAX_LINE_CHARS} 字", lines.asContextNotes().all { it.length <= ReadbackLines.MAX_LINE_CHARS })
    }

    private fun specFile(): File = File(ws, CalibrationBodySource.REL_SPEC)

    private fun haltFile(): File = File(ws, CalibrationAssembly.REL_HALT)

    private fun writeHalt(json: String) {
        val f = haltFile()
        f.parentFile.mkdirs()
        f.writeText(json)
        counter++
        f.setLastModified(BASE_MS + counter)
    }

    private fun writeSpec(json: String?) {
        if (json == null) {
            specFile().delete()
            return
        }
        val f = specFile()
        f.parentFile.mkdirs()
        f.writeText(json)
        counter++
        f.setLastModified(BASE_MS + counter)
    }

    private fun numbers(line: String): List<Double> =
        Regex("""\d+(?:\.\d+)?""").findAll(line).map { it.value.toDouble() }.toList()

    /** 722 渲染只留一位小数，对数按 ±0.06 容差（四舍五入的最大误差）。 */
    private fun close(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.06
}
