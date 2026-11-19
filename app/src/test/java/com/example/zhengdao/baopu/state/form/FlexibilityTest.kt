// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 柔韧档与可达性三态核验（工单追加①②③④）：限位随档变・三态・余量・风险提示不禁止。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.state.geometry.JointNames
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibilityTest {

    private val props = FormFixtures.props

    private fun rejects(block: () -> Unit) =
        assertThrows(IllegalArgumentException::class.java) { block() }

    private fun demand(id: String, degrees: Map<String, Double>) =
        PoseDemand(id, degrees, "单测夹具动作（非授权动作库）", "2026-11-18")

    /** 极端屈曲（自口一类）：按 body_spec 判，不内置"人类不能"。 */
    private fun extreme() = demand(
        "extreme-flexion",
        mapOf("neck" to 70.0, "spine" to 50.0, "chest" to 35.0, "hip_l" to 95.0, "shoulder_l" to 110.0),
    )

    @Test
    fun `柔韧档默认按年龄段给_未填年龄段取表默认`() {
        for ((band, g) in mapOf("youth" to 4, "adult" to 3, "middle" to 2, "senior" to 1)) {
            val r = Flexibility.reading(FormFixtures.spec().copy(ageBand = band), props)
            assertEquals(band, g, r.grade)
            assertEquals(Origin.DERIVED, r.origin)
        }
        assertEquals(3, Flexibility.reading(FormFixtures.spec(), props).grade)
    }

    @Test
    fun `显式柔韧档覆盖年龄段且标实测`() {
        val r = Flexibility.reading(FormFixtures.spec().copy(flexibilityGrade = 5, ageBand = "senior"), props)
        assertEquals(5, r.grade)
        assertEquals(Origin.MEASURED, r.origin)
        assertEquals("senior", r.ageBand)
    }

    @Test
    fun `柔韧档超域或年龄段未知即拒`() {
        rejects { Flexibility.reading(FormFixtures.spec().copy(flexibilityGrade = 6), props) }
        rejects { Flexibility.reading(FormFixtures.spec().copy(flexibilityGrade = 0), props) }
        rejects { Flexibility.reading(FormFixtures.spec().copy(ageBand = "toddler"), props) }
        rejects { FormFixtures.state(FormFixtures.spec().copy(flexibilityGrade = 9)) }
        rejects { FormFixtures.state(FormFixtures.spec().copy(ageBand = "nowhere")) }
    }

    @Test
    fun `十七关节限位齐全_与655关节名一一对应`() {
        val l = Flexibility.limits(3, props)
        assertEquals(17, l.size)
        assertEquals(JointNames.ALL.toSet(), l.map { it.joint }.toSet())
        for (j in l) {
            assertTrue("${j.joint} 基准区间倒置", j.baseDeg.low <= j.baseDeg.high)
            assertTrue("${j.joint} 限位须对称非负", j.limit.minRad <= 0.0 && j.limit.maxRad >= 0.0)
            assertTrue("${j.joint} 缺来源", j.baseDeg.source.isNotBlank())
        }
    }

    @Test
    fun `限位随柔韧档变_不是常数表`() {
        val stiff = Flexibility.limits(1, props).associateBy { it.joint }
        val supple = Flexibility.limits(5, props).associateBy { it.joint }
        assertEquals(0.6, stiff.getValue("neck").scale, 1e-12)
        assertEquals(1.4, supple.getValue("neck").scale, 1e-12)
        for (joint in JointNames.ALL) {
            val a = stiff.getValue(joint)
            val b = supple.getValue(joint)
            assertNotEquals("$joint 限位不随档变＝常数表", a.limit.maxRad, b.limit.maxRad, 1e-12)
            assertTrue("$joint 柔韧高档限位须更大", b.limit.maxRad > a.limit.maxRad)
            assertEquals("$joint 基准区间不许被改写", a.baseDeg, b.baseDeg)
        }
    }

    @Test
    fun `度转弧度给655_换算口径可核`() {
        val j = Flexibility.limits(3, props).first { it.joint == "neck" }
        assertEquals(1.0, j.scale, 1e-12)
        assertEquals(j.baseDeg.high * PI / 180.0, j.limit.maxRad, 1e-12)
        assertEquals(-j.limit.maxRad, j.limit.minRad, 1e-12)
        assertEquals(j.baseDeg.high, j.scaledDeg.high, 1e-12)
    }

    @Test
    fun `判据四_同一动作柔韧一档与五档三态不同`() {
        val stiff = Flexibility.judge(extreme(), 1, props)
        val supple = Flexibility.judge(extreme(), 5, props)
        assertEquals(FlexReach.UNREACHABLE, stiff.state)
        assertEquals(FlexReach.REACHABLE, supple.state)
        assertNotEquals(stiff.state, supple.state)
        assertEquals("unreachable", stiff.state.label)
        assertEquals("reachable", supple.state.label)
    }

    @Test
    fun `unreachable必带差多少度_余量就是最大超出角`() {
        val v = Flexibility.judge(extreme(), 1, props)
        assertEquals(38.0, v.marginDeg, 1e-9)
        assertTrue(v.marginDeg > 0.0)
        assertEquals(FlexReach.UNREACHABLE, v.perJoint.getValue("neck"))
        assertEquals("余量取最紧的那个关节（肩）", 110.0 - 120.0 * 0.6, v.marginDeg, 1e-9)
    }

    @Test
    fun `边缘带内判marginal_余量仍给超出角`() {
        val v = Flexibility.judge(demand("near-limit", mapOf("neck" to 40.0)), 1, props)
        assertEquals(FlexReach.MARGINAL, v.state)
        assertEquals("marginal", v.state.label)
        assertEquals(4.0, v.marginDeg, 1e-9)
        val over = Flexibility.judge(demand("over-band", mapOf("neck" to 42.0)), 1, props)
        assertEquals(FlexReach.UNREACHABLE, over.state)
    }

    @Test
    fun `reachable余量为零_逐关节三态取最差`() {
        val v = Flexibility.judge(demand("easy", mapOf("neck" to 10.0, "spine" to 10.0)), 1, props)
        assertEquals(FlexReach.REACHABLE, v.state)
        assertEquals(0.0, v.marginDeg, 1e-12)
        val mixed = Flexibility.judge(demand("mixed", mapOf("neck" to 10.0, "spine" to 50.0)), 1, props)
        assertEquals(FlexReach.REACHABLE, mixed.perJoint.getValue("neck"))
        assertEquals(FlexReach.UNREACHABLE, mixed.perJoint.getValue("spine"))
        assertEquals(FlexReach.UNREACHABLE, mixed.state)
    }

    @Test
    fun `极端姿态柔韧高档判可但仍附颈与呼吸提示_只提示不禁止`() {
        val v = Flexibility.judge(extreme(), 5, props)
        assertEquals(FlexReach.REACHABLE, v.state)
        assertEquals(2, v.risks.size)
        assertTrue("须有颈部提示", v.risks.any { "颈" in it })
        assertTrue("须有呼吸提示", v.risks.any { "呼吸" in it })
        val stiff = Flexibility.judge(extreme(), 1, props)
        assertEquals("提示不随档位消失", stiff.risks, v.risks)
    }

    @Test
    fun `动作缺来源或需求角悬空或为负即拒`() {
        rejects { PoseDemand("x", mapOf("neck" to 10.0), "", "2026-11-18") }
        rejects { PoseDemand("x", mapOf("neck" to 10.0), "夹具", "") }
        rejects { PoseDemand("x", emptyMap(), "夹具", "2026-11-18") }
        rejects { PoseDemand("x", mapOf("neck" to -10.0), "夹具", "2026-11-18") }
        rejects { Flexibility.judge(demand("dangling", mapOf("tail" to 10.0)), 3, props) }
    }

    @Test
    fun `BodyState带着柔韧读数与限位一起交付`() {
        val s = FormFixtures.state(FormFixtures.spec().copy(flexibilityGrade = 4))
        assertEquals(4, s.flex.grade)
        assertEquals(17, s.jointLimits.size)
        assertEquals(props.flexibility.scale(4), s.flex.scale, 1e-12)
        val neck = s.jointLimits.first { it.joint == "neck" }
        assertEquals(60.0 * props.flexibility.scale(4), neck.scaledDeg.high, 1e-9)
    }

    @Test
    fun `追加四_判定只看需求角不看动作名_换个名字结论不变`() {
        val a = Flexibility.judge(extreme(), 5, props)
        val b = Flexibility.judge(extreme().copy(id = "换个名字"), 5, props)
        assertEquals(a.state, b.state)
        assertEquals(a.perJoint, b.perJoint)
        assertEquals(a.marginDeg, b.marginDeg, 1e-12)
        assertEquals(a.risks, b.risks)
        val c = Flexibility.judge(extreme().copy(id = "自口"), 1, props)
        assertEquals("极端姿态不许按名字一律判死", FlexReach.UNREACHABLE, c.state)
        assertEquals(38.0, c.marginDeg, 1e-9)
    }

    @Test
    fun `同输入两次逐字节一致`() {
        val a = Flexibility.judge(extreme(), 3, props)
        val b = Flexibility.judge(extreme(), 3, props)
        assertEquals(a, b)
        assertEquals(Flexibility.limits(3, props), Flexibility.limits(3, props))
        assertEquals(
            Flexibility.reading(FormFixtures.spec(), props),
            Flexibility.reading(FormFixtures.spec(), props),
        )
    }
}