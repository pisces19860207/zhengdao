// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百六十五批 · 目录表测试：点数、三群、八向、推导表与锚示例。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.BodyFatTier
import com.example.zhengdao.baopu.state.subzone.SubzoneId
import com.example.zhengdao.baopu.state.view.Octant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCatalogTest {

    @Test
    fun `点数十四落在十到十四闭区间`() {
        val size = FrameCatalog.POINTS.size
        assertTrue("点数须 >=10，实为 $size", size >= FrameCatalog.MIN_POINTS)
        assertTrue("点数须 <=14，实为 $size", size <= FrameCatalog.MAX_POINTS)
        assertEquals(FramePoint.COUNT, size)
    }

    @Test
    fun `三群都有骨点且群内不空`() {
        FrameRegion.entries.forEach { region ->
            assertTrue(region.label + " 群缺骨点", FrameCatalog.pointsOf(region).isNotEmpty())
        }
        assertEquals(FrameCatalog.POINTS.size, FrameRegion.entries.sumOf {
            FrameCatalog.pointsOf(it).size
        })
    }

    @Test
    fun `点名不重复且不含数字`() {
        val labels = FrameCatalog.POINTS.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        labels.forEach { label -> assertTrue(label + " 含数字", label.none { it.isDigit() }) }
    }

    @Test
    fun `八向闭集来自860且每点落闭集`() {
        assertEquals(8, Octant.SIZE)
        FrameCatalog.POINTS.forEach { point ->
            assertTrue(point.label + " 方位不在八向闭集", point.relative in Octant.entries)
        }
    }

    @Test
    fun `八向八档名字互不相同`() {
        val words = Octant.entries.map { it.label }
        assertEquals(Octant.SIZE, words.toSet().size)
    }

    @Test
    fun `成对结构标sided正中结构不标`() {
        assertTrue(FramePoint.CLAVICLE.sided)
        assertTrue(FramePoint.ACROMION.sided)
        assertTrue(FramePoint.ANKLE_MALLEOLI.sided)
        assertFalse(FramePoint.STERNAL_ANGLE.sided)
        assertFalse(FramePoint.SACRUM.sided)
        assertFalse(FramePoint.PATELLA.sided)
    }

    @Test
    fun `体脂越高可见度不升_四档闭集`() {
        FramePoint.entries.forEach { point ->
            val levels = BodyFatTier.entries.map { FrameCatalog.visibleOf(point, it).level }
            assertTrue(point.label + " 体脂换档可见度不动", levels.toSet().size >= 2)
            assertTrue(point.label + " 可见档随体脂升了", levels == levels.sortedDescending())
        }
    }

    @Test
    fun `高体脂锁骨锁骨不分明_负例推导上限`() {
        val clavicle = FrameCatalog.visibleOf(FramePoint.CLAVICLE, BodyFatTier.HIGH)
        assertTrue("高体脂锁骨不得分明", clavicle.level < VisibleGrade.LAST)
        assertEquals(VisibleGrade.CLEAR, clavicle)
    }

    @Test
    fun `很高体脂浅表点也不分明`() {
        assertTrue(FrameCatalog.visibleOf(FramePoint.CLAVICLE, BodyFatTier.VERY_HIGH).level <
            VisibleGrade.LAST)
    }

    @Test
    fun `低体脂浅表点可分明`() {
        assertEquals(VisibleGrade.PRONOUNCED, FrameCatalog.visibleOf(FramePoint.PATELLA, BodyFatTier.LOW))
    }

    @Test
    fun `可触及档四档闭集且浅表高脂仍清楚`() {
        FramePoint.entries.forEach { point ->
            val levels = BodyFatTier.entries.map { FrameCatalog.palpableOf(point, it).level }
            assertTrue(point.label + " 触及档越界", levels.all { it in 0..PalpableGrade.LAST })
        }
        assertEquals(
            PalpableGrade.EASY,
            FrameCatalog.palpableOf(FramePoint.CLAVICLE, BodyFatTier.HIGH),
        )
    }

    @Test
    fun `锚示例至少三条且点名已冻结点`() {
        val examples = FrameCatalog.ANCHOR_EXAMPLES
        assertTrue("锚示例至少三条", examples.size >= 3)
        examples.forEach { example ->
            val line = example.line()
            assertTrue(line, line.contains(example.target.label))
            assertTrue(line, line.contains(example.reference.label))
            assertTrue(line, line.contains(example.step.label))
            assertTrue(line, FrameCatalog.POINTS.contains(example.target))
            assertTrue(line, FrameCatalog.POINTS.contains(example.reference))
        }
    }

    @Test
    fun `肩峰距锁骨示例存在_下游可照抄`() {
        val hit = FrameCatalog.ANCHOR_EXAMPLES.first {
            it.target == FramePoint.ACROMION && it.reference == FramePoint.CLAVICLE
        }
        assertTrue(hit.line(), hit.line().startsWith("肩峰｜距锁骨"))
        assertTrue(hit.line(), hit.line().contains("档"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `锚示例不许自己距自己`() {
        AnchorExample(FramePoint.CLAVICLE, FramePoint.CLAVICLE, AnchorStep.ONE, "上方")
    }

    @Test
    fun `档序连续且可选组合数表驱动`() {
        assertEquals(0, VisibleGrade.entries.first().level)
        assertEquals(VisibleGrade.LAST, VisibleGrade.entries.last().level)
        assertEquals(0, PalpableGrade.entries.first().level)
        assertEquals(PalpableGrade.LAST, PalpableGrade.entries.last().level)
        assertEquals(
            FrameCatalog.POINTS.size * (VisibleGrade.COUNT + PalpableGrade.COUNT + 1),
            FrameCatalog.totalChoices,
        )
    }

    @Test
    fun `补丁五_每点有zoneId与人读名_自造区名零命中`() {
        val allowedNames = setOf(ZONE_TRUNK, ZONE_LEG_FOOT, ZONE_HEAD)
        FrameCatalog.POINTS.forEach { point ->
            assertTrue(point.label + " 缺 zoneId", point.zoneId.isNotBlank())
            assertTrue(point.label + " 人读名越界：" + point.zoneName, point.zoneName in allowedNames)
            assertFalse(point.label + " 自造区名", point.zoneName in setOf("髋区", "骨区"))
        }
        assertEquals(ZONE_TRUNK, FramePoint.CLAVICLE.zoneName)
        assertEquals(ZONE_TRUNK, FramePoint.COSTAL_MARGIN.zoneName)
        assertEquals(ZONE_HEAD, FramePoint.MASTOID.zoneName)
        assertEquals(ZONE_LEG_FOOT, FramePoint.CALCANEUS.zoneName)
    }

    @Test
    fun `补丁五_13区真名逐字从事实表核_zoneId只允许真名或none`() {
        val truth = java.io.File("docs/facts/anatomy-zones.json").takeIf { it.isFile }
            ?: java.io.File("../docs/facts/anatomy-zones.json")
        assertTrue("找不到 13 区事实表", truth.isFile)
        val text = truth.readText(Charsets.UTF_8)
        val ids = Regex("\"zone_id\"\\s*:\\s*\"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1] }.toSet()
        assertEquals("13 区真名数不对", 13, ids.size)
        FrameCatalog.POINTS.forEach { point ->
            assertTrue(point.label + " 的 zoneId 不在白名单：" + point.zoneId,
                point.zoneId == FRAME_ZONE_NONE || point.zoneId in ids)
        }
        assertFalse("pubis 不是 13 区真名，不许自造", "pubis" in ids)
    }

    @Test
    fun `补丁八_下游引用示例至少三条且点名消费方`() {
        val examples = FrameCatalog.DOWNSTREAM_EXAMPLES
        assertTrue("下游示例至少三条", examples.size >= 3)
        examples.forEach { line ->
            assertTrue(line, line.contains("→"))
            assertTrue(line, listOf("肩峰", "锁骨", "髂嵴", "髌骨", "大转子", "乳突").any { it in line })
        }
        assertTrue(examples.first(), examples.first().startsWith("肩峰｜距锁骨"))
        assertTrue(examples[1], examples[1].contains("高体脂→隐约"))
    }

    @Test
    fun `补丁六_zoneId与subzoneId分字段_775十四code冻结`() {
        val frozen = listOf(
            "apex", "glansbody", "corona", "sulcus", "frenulum",
            "shaftProximal", "shaftMid", "shaftDistal", "radix",
            "meatusRim", "meatusSlit", "prepuceInner", "prepuceOuter", "prepuceOrifice",
        )
        assertEquals(frozen, FrameCatalog.subzoneCodes())
        assertEquals(14, SubzoneId.entries.size)
        FrameCatalog.POINTS.forEach { point ->
            val code = point.subzoneId
            if (code != null) {
                assertTrue(code + " 不是 775 真 code", code in frozen)
                assertEquals(code, SubzoneId.ofCode(code).code)
            }
        }
    }
}
