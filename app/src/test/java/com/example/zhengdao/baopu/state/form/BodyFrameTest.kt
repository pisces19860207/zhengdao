// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 比例骨架核验：判据①（三值落人群区间，不叠 ±10%）＋自洽＋定标容差只作偏差闸。
package com.example.zhengdao.baopu.state.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFrameTest {

    private val props = FormFixtures.props

    private fun frame(
        heightCm: Double = 170.0,
        weightKg: Double = 65.0,
        fat: Double = 18.0,
        muscle: Int? = null,
        shoulder: Override? = null,
        headBody: Override? = null,
        armSpan: Override? = null,
        segments: Map<String, SegmentOverride> = emptyMap(),
    ) = BodyFrame(
        FormFixtures.spec(heightCm, weightKg, fat).copy(
            muscleGrade = muscle, shoulderRatio = shoulder, headBodyRatio = headBody,
            armSpanRatio = armSpan, segments = segments,
        ),
        props,
    )

    private fun rejects(block: () -> Unit) =
        assertThrows(IllegalArgumentException::class.java) { block() }

    @Test
    fun `判据一_身高170三比例都落人群区间`() {
        val f = frame()
        assertTrue("三值须全落区间：${f.ratios}", f.ratios.allInPopulationRange())
        assertEquals(0.2275, f.ratios.shoulder.value, 1e-9)
        assertEquals(1.015, f.ratios.armSpan.value, 1e-9)
        assertEquals(7.75, f.ratios.headBody.value, 1e-9)
    }

    @Test
    fun `判据一_肩宽口径必须是肩峰宽不是衣服肩宽`() {
        val s = props.ratio("shoulder_biacromial")
        assertTrue("口径须写明肩峰：${s.caliber}", "肩峰" in s.caliber)
        assertTrue("口径须写明 biacromial：${s.caliber}", "biacromial" in s.caliber)
        assertTrue("口径须点明不是衣服肩宽", "bideltoid" in s.caliber)
        assertTrue("来源须点名人体测量学调查", "人体测量学" in s.source)
        assertEquals("2026-11-18", s.asOf)
    }

    @Test
    fun `判据一_区间就是裁定二那三条_不许放宽`() {
        assertEquals(0.220, props.ratio("shoulder_biacromial").low, 1e-12)
        assertEquals(0.235, props.ratio("shoulder_biacromial").high, 1e-12)
        assertEquals(0.98, props.ratio("arm_span").low, 1e-12)
        assertEquals(1.05, props.ratio("arm_span").high, 1e-12)
        assertEquals(7.5, props.ratio("head_body_ratio").low, 1e-12)
        assertEquals(8.0, props.ratio("head_body_ratio").high, 1e-12)
    }

    @Test
    fun `自洽_身高升则三段长度同升`() {
        val a = frame(170.0)
        val b = frame(180.0)
        for (id in listOf("head", "torso", "thigh", "calf", "upper_arm", "forearm")) {
            assertTrue("$id 应随身高变长", b.cm(id) > a.cm(id))
        }
        assertEquals(180.0, b.layout.topY, 1e-9)
    }

    @Test
    fun `自洽_臂展约等身高正负五个百分点`() {
        for (h in listOf(160.0, 170.0, 185.0)) {
            val f = frame(h)
            val rel = kotlin.math.abs(f.armSpanCm - h) / h
            assertTrue("臂展 $h → ${f.armSpanCm} 偏差 $rel 超 5%", rel <= 0.05)
        }
    }

    @Test
    fun `自洽_肩宽夹在胸腔宽与胸腔宽加两臂厚之间`() {
        val f = frame()
        val chest = f.sections.first { it.id == "chest_upper" }.widthCm.value
        val armR = f.radii.getValue("upper_arm").value
        val shoulder = f.ratios.shoulder.value * 170.0
        assertTrue("肩宽 $shoulder 小于胸腔宽 $chest", shoulder >= chest)
        assertTrue("肩宽 $shoulder 大于胸腔宽＋两臂厚 ${chest + 2 * armR}", shoulder <= chest + 2 * armR)
    }

    @Test
    fun `自洽_总高恒等_覆盖后仍成立`() {
        assertEquals(170.0, frame().layout.topY, 1e-9)
        val f = frame(headBody = FormFixtures.override(7.6), segments = mapOf(
            "thigh" to SegmentOverride(lengthCm = FormFixtures.override(43.0)),
        ))
        assertEquals(170.0, f.layout.topY, 1e-9)
        assertEquals(Origin.MEASURED, f.lengths.getValue("thigh").origin)
        assertEquals(
            "填的是头身比，头长＝身高÷比值 ⇒ 属按比例推（工单⑤：只有直接填的尺寸才标实测）",
            Origin.DERIVED, f.lengths.getValue("head").origin,
        )
    }

    @Test
    fun `定标容差只作偏差闸_五个百分点不留痕二十个百分点留痕`() {
        val tol = props.constant("calibration_tolerance").default
        assertEquals(0.10, tol, 1e-12)
        val mild = frame(shoulder = FormFixtures.override(0.2275 * 1.05))
        assertFalse("±10% 内不该报定标偏离", mild.deviations.any { it.kind == "calibration" })
        val wild = frame(shoulder = FormFixtures.override(0.2275 * 1.20))
        assertTrue("超容差须留痕", wild.deviations.any { it.kind == "calibration" && it.id == "shoulder_biacromial" })
    }

    @Test
    fun `超人群区间只留痕不改写覆盖值`() {
        val f = frame(shoulder = FormFixtures.override(0.30))
        assertEquals("覆盖值不许被改写", 0.30, f.ratios.shoulder.value, 1e-12)
        assertEquals(Origin.MEASURED, f.ratios.shoulder.origin)
        assertFalse(f.ratios.allInPopulationRange())
        assertTrue(f.deviations.any { it.kind == "population_range" && it.id == "shoulder_biacromial" })
    }

    @Test
    fun `未覆盖一律标推算且带表来源`() {
        val f = frame()
        for ((id, s) in f.lengths) {
            assertEquals("$id 未覆盖须标推算", Origin.DERIVED, s.origin)
            assertTrue("$id 缺来源", s.source.isNotBlank())
            assertTrue("$id 缺日期", s.asOf.isNotBlank())
        }
        assertTrue(f.radii.isNotEmpty())
        for ((id, s) in f.radii) assertEquals("$id 半径未覆盖须标推算", Origin.DERIVED, s.origin)
    }

    @Test
    fun `覆盖值缺来源即拒`() {
        rejects { Override(170.0, "", "2026-11-18") }
        rejects { Override(170.0, "卷尺", "") }
        rejects { Override(-1.0, "卷尺", "2026-11-18") }
        rejects { Sourced(1.0, "cm", Origin.DERIVED, "", "2026-11-18") }
        rejects { Sourced(Double.NaN, "cm", Origin.DERIVED, "表", "2026-11-18") }
    }

    @Test
    fun `体脂超可行域即拒`() {
        rejects { frame(fat = 60.0) }
        rejects { frame(fat = 1.0) }
    }

    @Test
    fun `肌肉档超域即拒`() {
        rejects { frame(muscle = 9) }
        rejects { frame(muscle = 0) }
    }

    @Test
    fun `躯干残差非正即拒_覆盖值把身高挤爆`() {
        rejects { frame(segments = mapOf("thigh" to SegmentOverride(lengthCm = FormFixtures.override(120.0)))) }
    }

    @Test
    fun `上臂残差非正即拒_臂展覆盖值不自洽`() {
        rejects { frame(armSpan = FormFixtures.override(0.60)) }
    }

    @Test
    fun `体脂升则脂肪遮盖降且不确定度升`() {
        val thin = frame(fat = 8.0)
        val fat = frame(fat = 30.0)
        assertTrue(fat.factors.fatCover < thin.factors.fatCover)
        assertTrue(fat.factors.fatUncertainty > thin.factors.fatUncertainty)
    }

    @Test
    fun `肌肉档升则肢体半径升_体重升则截面宽升`() {
        val weak = frame(muscle = 1)
        val strong = frame(muscle = 5)
        assertTrue(strong.radii.getValue("upper_arm").value > weak.radii.getValue("upper_arm").value)
        assertTrue(strong.radii.getValue("thigh").value > weak.radii.getValue("thigh").value)
        val light = frame(weightKg = 55.0)
        val heavy = frame(weightKg = 85.0)
        assertTrue(heavy.sections.first { it.id == "waist" }.widthCm.value >
            light.sections.first { it.id == "waist" }.widthCm.value)
    }

    @Test
    fun `分段与截面几何齐全_关节名取655`() {
        val f = frame()
        assertEquals(10, f.segments.size)
        assertEquals(4, f.sections.size)
        assertEquals(setOf("chest_upper", "chest_lower", "waist", "pelvis"), f.sections.map { it.id }.toSet())
        for (s in f.segments) {
            assertTrue("${s.id} 关节名为空", s.joint.isNotBlank())
            assertTrue("${s.id} 长度非正", s.lengthCm.value > 0.0)
            assertTrue("${s.id} 顶低于底", s.yTopCm > s.yBottomCm)
        }
        val torso = f.segments.first { it.id == "torso" }
        assertEquals("chest", torso.joint)
        assertEquals("spine", torso.extraJoint)
    }

    @Test
    fun `同输入两次逐字节一致`() {
        val a = frame()
        val b = frame()
        assertEquals(a.lengths, b.lengths)
        assertEquals(a.radii, b.radii)
        assertEquals(a.layout, b.layout)
        assertEquals(a.sections, b.sections)
        assertEquals(a.segments, b.segments)
        assertEquals(a.deviations, b.deviations)
        assertEquals(a.armSpanCm, b.armSpanCm, 0.0)
    }
}