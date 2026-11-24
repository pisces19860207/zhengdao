// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百五十三批 · 行为测试：每条判据至少一条正例＋一条负例。
package com.example.zhengdao.baopu.state.extract

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.ExposeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProseExtractorTest {

    private fun hit(text: String, dim: ExtractDim, grade: String): Observed? =
        ProseExtractor.extract(text).observed.firstOrNull { it.dim == dim && it.grade == grade }

    // ── 判据③：每条 observed 必带 span，且逐字等于原文片段 ──────────────
    @Test
    fun `判据三_每条观测带span且span逐字等于原文`() {
        val text = "他留着六块腹肌，白线深。"
        val result = ProseExtractor.extract(text)
        assertTrue("应有观测", result.observed.isNotEmpty())
        result.observed.forEach { obs ->
            assertEquals(obs.span.text, text.substring(obs.span.start, obs.span.end))
            assertTrue("span 非空", obs.span.start < obs.span.end)
        }
    }

    @Test
    fun `判据三_短发无既有档不许自造短档`() {
        val result = ProseExtractor.extract("她留着短发。")
        assertTrue("短发不许落 Observed", result.observed.none { it.field == "hair.style" })
        assertTrue("短发必须进 misses", result.misses.any { it.reason == MissReason.NO_GRADE && "短发" in it.span.text })
    }

    // ── 判据①：只抽有档可落的项 ──────────────────────────────────────
    @Test
    fun `判据一_八维只落既有档不自造`() {
        val text = "六块腹肌，白线深，左右等高，下缘外高内低，胸沟宽，斜方陡，倒三角，前锯肌明显。"
        val result = ProseExtractor.extract(text)
        val dims = result.observed.map { it.dim }.toSet()
        listOf(
            ExtractDim.RECTUS_BLOCKS, ExtractDim.LINEA_ALBA, ExtractDim.BLOCK_ALIGNMENT,
            ExtractDim.PEC_LOWER_TREND, ExtractDim.PEC_GAP, ExtractDim.TRAP_SLOPE,
            ExtractDim.LAT_FLARE, ExtractDim.SERRATUS,
        ).forEach { assertTrue("缺维 ${it.label}", it in dims) }
        result.observed.forEach { obs ->
            assertTrue("自造档 ${obs.grade}", obs.grade in ExtractLexicon.legalGrades(obs.dim))
        }
    }

    // ── 判据②：下体只落 804/831 已合档 ──────────────────────────────
    @Test
    fun `判据二_退到冠后落到831六档而非自造`() {
        val obs = hit("包皮退到冠后。", ExtractDim.AREA_LEVEL, AreaLevel.RETRACTED_BEHIND_CORONA.label)
        assertNotNull(obs)
        assertEquals("831", obs!!.dim.tierSource)
    }

    @Test
    fun `判据二_全露与只露一点逐条落档`() {
        assertNotNull(hit("龟头全露。", ExtractDim.AREA_LEVEL, AreaLevel.FULLY_EXPOSED.label))
        assertNotNull(hit("只露一点。", ExtractDim.AREA_LEVEL, AreaLevel.CORONA_ONLY.label))
        assertNotNull(hit("覆盖过半。", ExtractDim.AREA_LEVEL, AreaLevel.OVER_HALF.label))
    }

    @Test
    fun `判据二_四型逐条可判`() {
        val cases = listOf(
            "环切后完全露出" to ExposeType.POST_CIRCUMCISION.label,
            "自然全露" to ExposeType.NATURAL.label,
            "状态性全露" to ExposeType.ERECTION_ONLY.label,
            "手动未复位" to ExposeType.MANUAL_UNRESTORED.label,
        )
        cases.forEach { (text, grade) ->
            assertNotNull("$text 应判 $grade", hit(text, ExtractDim.EXPOSE_TYPE, grade))
        }
    }

    @Test
    fun `判据二_只写全露判不出四型进misses不猜`() {
        val result = ProseExtractor.extract("龟头全露。")
        assertTrue("不许猜四型", result.observed.none { it.dim == ExtractDim.EXPOSE_TYPE })
        assertTrue("应显式记疑似矛盾", result.misses.any { it.reason == MissReason.AMBIGUOUS })
    }

    @Test
    fun `判据二_810疲软两态只进traces不自造erectState档`() {
        val result = ProseExtractor.extract("疲软时看着更长，半勃时纹理更显。")
        assertTrue("疲软进 traces", result.traces.any { "疲软" in it.word && it.grade == null && it.pending == "810" })
        assertTrue("半勃进 traces", result.traces.any { "半勃" in it.word && it.grade == null })
        assertTrue("不许自造 erectState 档", result.observed.none { it.dim == ExtractDim.ERECT_STATE })
        assertTrue("不许自造 groinBulge 档", result.observed.none { "bulge" in it.grade })
    }

    @Test
    fun `判据二_814截面875隆起775子区731青筋均进misses带pending`() {
        val result = ProseExtractor.extract("截面廓形很显，不太显，青筋明显，子区质地，meatus。")
        listOf("814", "875", "775", "731").forEach { id ->
            assertTrue("缺 pending:$id", result.misses.any { it.pending == id })
        }
        assertTrue("未落地项不许落档", result.observed.none {
            it.grade.contains("截面") || it.grade.contains("青筋") || it.grade.contains("隆起")
        })
    }

    // ── 判据④：低置信标低且不越级 ────────────────────────────────────
    @Test
    fun `判据四_模糊词标低且不喂连续性校验`() {
        val result = ProseExtractor.extract("似乎大概已环切。")
        val low = result.observed.filter { it.confidence == Confidence.LOW && it.field != null }
        assertTrue("应有带字段的低置信观测", low.isNotEmpty())
        assertTrue("低置信也逐条保留在 observed", low.any { it.field == "prepuce.circum" })
        val feed = result.observedForCheck()
        low.forEach { obs -> assertFalse("低置信越级", feed.containsKey(obs.field)) }
        assertFalse("本句低置信不得喂 prepuce.circum", feed.containsKey("prepuce.circum"))
    }

    @Test
    fun `判据四_高置信可喂且只喂817白名单字段`() {
        val result = ProseExtractor.extract("他已环切，明显出汗。"
            .replace("明显出汗", "湿透"))
        val feed = result.observedForCheck()
        assertTrue("817 白名单字段可喂", feed.containsKey("prepuce.circum"))
        assertFalse("未落地六档不喂 817", feed.containsKey("prepuce.area"))
        val traces = result.traceIdsForCheck()
        assertTrue("出汗痕迹可喂", "trace.sweat" in traces)
    }

    @Test
    fun `判据四_六档面积观测保留但不越级喂817`() {
        val text = "退到冠后。"
        val result = ProseExtractor.extract(text)
        assertTrue("六档照抽", result.observed.any { it.dim == ExtractDim.AREA_LEVEL })
        assertFalse("817 无六档字段，不许喂", result.observedForCheck().isNotEmpty())
    }

    // ── 判据⑤：纯函数确定性 ──────────────────────────────────────────
    @Test
    fun `判据五_同输入同输出逐字一致`() {
        val text = "六块腹肌，已环切，退到冠后，湿透，像铁一样硬。"
        val a = ProseExtractor.extract(text)
        val b = ProseExtractor.extract(text)
        assertEquals(a.line(), b.line())
    }

    // ── 判据⑥：比喻不解读 ────────────────────────────────────────────
    @Test
    fun `判据六_像铁一样硬进misses不猜硬度档`() {
        val result = ProseExtractor.extract("腹肌像铁一样硬。")
        assertTrue("比喻显式记 miss", result.misses.any { it.reason == MissReason.METAPHOR })
        assertTrue("比喻段内不许落档", result.observed.none { "硬" in it.grade })
    }

    // ── 判据⑦：字段名以 817 为准且可配 ───────────────────────────────
    @Test
    fun `判据七_默认表名可取817且字段名可配`() {
        val text = "已环切。"
        val dflt = ProseExtractor.extract(text).observed.first()
        assertEquals("prepuce.circum", dflt.field)
        val custom = FieldNames.Table("817+869", mapOf(ExtractDim.CIRCUMCISION to "prepuce.custom"))
        val alt = ProseExtractor.extract(text, custom).observed.first()
        assertEquals("prepuce.custom", alt.field)
        assertEquals("817+869", custom.source)
    }

    // ── 痕迹五类 ─────────────────────────────────────────────────────
    @Test
    fun `痕迹五类逐类可抽`() {
        val text = "湿透，中疲，呼吸浅，收紧，皮肤光滑。"
        val dims = ProseExtractor.extract(text).observed.map { it.dim }.toSet()
        listOf(
            ExtractDim.SWEAT, ExtractDim.FATIGUE, ExtractDim.BREATH,
            ExtractDim.POSTURE, ExtractDim.SKIN,
        ).forEach { assertTrue("缺痕迹 ${it.label}", it in dims) }
    }

    @Test
    fun `miss逐条带span且原因非空`() {
        val result = ProseExtractor.extract("短发，像铁一样硬，青筋，截面。")
        assertTrue("应有 miss", result.misses.isNotEmpty())
        result.misses.forEach { m ->
            assertTrue("miss 无原因", m.note.isNotBlank())
            assertNotNull(m.span)
        }
    }
}
