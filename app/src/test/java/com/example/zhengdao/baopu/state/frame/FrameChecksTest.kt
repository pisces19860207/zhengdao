// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百六十五批 · 判据测试：①–⑥ 与工具门逐条，红/提示/通过齐全。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.BodyFatTier
import com.example.zhengdao.baopu.state.bodytype.SectionGrade
import com.example.zhengdao.baopu.state.bodytype.SkeletonTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChecksTest {

    @Test
    fun `判据一_点数与三项齐出账`() {
        val f = FramePointChecks.completeness(FrameFixtures.person())
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("BONE-COMPLETE", f.code)
        assertTrue(f.why.contains("三项齐"))
        assertEquals(FrameCatalog.POINTS.size, FrameFixtures.person().frame.size)
    }

    @Test
    fun `判据二_可见度全部由815体脂真枚举推`() {
        val p = FrameFixtures.person(fat = BodyFatTier.MID)
        val f = FramePointChecks.fatVisible(p)
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("VISIBLE-DERIVED", f.code)
    }

    @Test
    fun `补丁三_体脂高锁骨分明必须红`() {
        val base = FrameFixtures.person(fat = BodyFatTier.HIGH)
        val bad = FrameFixtures.withVisible(base, FramePoint.CLAVICLE, VisibleGrade.PRONOUNCED)
        val f = FramePointChecks.fatVisible(bad)
        assertEquals("体脂高却分明应判红", FrameVerdict.DENY, f.verdict)
        assertEquals("FAT-HIGH-VISIBLE", f.code)
        assertTrue(f.why.contains("锁骨"))
        assertTrue(f.why.contains("分明"))
    }

    @Test
    fun `补丁三_体脂很高锁骨清楚也红`() {
        val base = FrameFixtures.person(fat = BodyFatTier.VERY_HIGH)
        val bad = FrameFixtures.withVisible(base, FramePoint.CLAVICLE, VisibleGrade.CLEAR)
        assertEquals(FrameVerdict.DENY, FramePointChecks.fatVisible(bad).verdict)
    }

    @Test
    fun `判据二_低体脂锁骨分明不误红`() {
        val p = FrameFixtures.person(fat = BodyFatTier.LOW)
        assertEquals(FrameVerdict.PASS, FramePointChecks.fatVisible(p).verdict)
    }

    @Test
    fun `判据二_可见档手填偏低也红`() {
        val base = FrameFixtures.person(fat = BodyFatTier.LOW)
        val bad = FrameFixtures.withVisible(base, FramePoint.CLAVICLE, VisibleGrade.HIDDEN)
        val f = FramePointChecks.fatVisible(bad)
        assertEquals(FrameVerdict.DENY, f.verdict)
        assertEquals("VISIBLE-NOT-DERIVED", f.code)
    }

    @Test
    fun `判据二_可触及档由真枚举推出`() {
        val f = FramePointChecks.palpableDerived(FrameFixtures.person(fat = BodyFatTier.HIGH))
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("PALPABLE-DERIVED", f.code)
    }

    @Test
    fun `判据二_可触及档手填红`() {
        val base = FrameFixtures.person(fat = BodyFatTier.MID)
        val wrong = base.frame.all().map { reading ->
            if (reading.point == FramePoint.CLAVICLE) {
                reading.copy(palpable = PalpableGrade.HARD)
            } else {
                reading
            }
        }
        val f = FramePointChecks.palpableDerived(FramePerson(base.roleId, base.profile, BoneFrame(wrong)))
        assertEquals(FrameVerdict.DENY, f.verdict)
        assertEquals("PALPABLE-NOT-DERIVED", f.code)
    }

    @Test
    fun `判据三_锚示例至少三条且逐条可引用`() {
        val f = FramePointChecks.anchorExamples(FrameFixtures.person())
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("ANCHOR-READY", f.code)
        assertTrue(f.why.contains("肩峰｜距锁骨"))
    }

    @Test
    fun `判据四_出口无数值与评价词`() {
        val f = FramePointChecks.noNumbers(FrameFixtures.person())
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("NO-NUMBERS", f.code)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `判据四_出口塞评价词被咬`() {
        val p = FrameFixtures.person(role = "甲（" + "理" + "想" + "）")
        FramePointChecks.noNumbers(p)
    }

    @Test
    fun `判据五_未给截面跳过不假判一致`() {
        val f = FramePointChecks.sectionOrthogonal(FrameFixtures.person(), null)
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("SECTION-NA", f.code)
    }

    @Test
    fun `判据五_宽骨架不抬高可见度`() {
        val p = FrameFixtures.person(fat = BodyFatTier.HIGH, skeleton = SkeletonTier.WIDE)
        val f = FramePointChecks.sectionOrthogonal(p, FrameFixtures.section(SectionGrade.WIDE))
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("SECTION-ORTHOGONAL", f.code)
    }

    @Test
    fun `补丁五六_区名与子区字段对齐`() {
        val f = FramePointChecks.zoneAligned(FrameFixtures.person())
        assertEquals(FrameVerdict.PASS, f.verdict)
        assertEquals("ZONE-ALIGNED", f.code)
    }

    @Test
    fun `补丁八_髂嵴对体脂敏感的高体脂落隐约`() {
        val high = FrameFixtures.person(fat = BodyFatTier.HIGH)
        assertEquals(
            VisibleGrade.FAINT,
            high.frame.reading(FramePoint.ILIAC_CREST).visible,
        )
        assertEquals("隐约", high.frame.reading(FramePoint.ILIAC_CREST).visible.label)
    }

    @Test
    fun `汇总出口逐字含三态计数并含区字段`() {
        val p = FrameFixtures.person()
        val line = FramePointChecks.sceneLine(p, FrameFixtures.section())
        assertTrue(line.contains("通过 7"))
        assertTrue(line.contains("红 0"))
        assertTrue(line, line.contains("ZONE-ALIGNED"))
        assertTrue(line, line.contains("zoneId/zoneName"))
    }

    @Test
    fun `最严判定红压提示压通过`() {
        val list = listOf(
            FrameFinding(FrameVerdict.PASS, "A", "甲"),
            FrameFinding(FrameVerdict.DENY, "B", "乙"),
            FrameFinding(FrameVerdict.HINT, "C", "丙"),
        )
        assertEquals(FrameVerdict.DENY, FramePointChecks.worst(list))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负例_骨点单重复构造即拒`() {
        val one = FrameFixtures.person().frame.all().first()
        BoneFrame(listOf(one, one))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负例_骨点单缺项即拒`() {
        BoneFrame(FrameFixtures.person().frame.all().drop(1))
    }
}
