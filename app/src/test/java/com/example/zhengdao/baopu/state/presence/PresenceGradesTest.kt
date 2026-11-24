// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGradesTest {

    @Test
    fun `档数冻结`() {
        assertEquals(6, PresenceKind.COUNT)
        assertEquals(6, PresenceKind.entries.size)
        assertEquals(4, CanBeNude.COUNT)
        assertEquals(4, CanBeNude.entries.size)
        assertEquals(2, CanDescribe.COUNT)
        assertEquals(6, Venue.COUNT)
        assertEquals(6, Venue.entries.size)
        assertEquals(3, MinorPresence.COUNT)
        assertEquals(3, MinorPresence.entries.size)
        assertEquals(5, AgeTier.COUNT)
        assertEquals(5, AgeTier.entries.size)
        assertEquals(3, KinAvoidance.COUNT)
        assertEquals(2, BloodTie.COUNT)
        assertEquals(2, SceneKind.COUNT)
        assertEquals(7, CareAction.COUNT)
        assertEquals(3, SexualSignal.COUNT)
        assertEquals(3, DistortionFlag.COUNT)
        assertEquals(4, FindingKind.COUNT)
        assertEquals(3, PresenceVerdict.COUNT)
    }

    @Test
    fun `三岁前是一等档且默认应对是穿内裤`() {
        assertEquals(0, AgeTier.UNDER_THREE.order)
        assertEquals(AgeTier.ZERO_AVOID_ORDER, AgeTier.UNDER_THREE.order)
        assertNotEqualOrder(AgeTier.UNDER_THREE, AgeTier.YOUNG)
        assertEquals("穿内裤", CanBeNude.entries.first { it.level == CanBeNude.DEFAULT_LEVEL }.label)
        assertEquals(0, CanBeNude.NATURAL.level)
        assertEquals(3, CanBeNude.FULLY_DRESSED.level)
    }

    @Test
    fun `未成年三态与避讳三档不许合并`() {
        assertEquals(
            listOf("无", "有小孩在场", "主角本人是未成年"),
            MinorPresence.entries.map { it.label },
        )
        assertEquals(
            listOf("不避", "幼年不避·长大避", "一直避"),
            KinAvoidance.entries.map { it.label },
        )
    }

    @Test
    fun `六场所全是日常身体场景`() {
        assertTrue(Venue.entries.all { it.routine })
        assertEquals(16, PresenceCatalog.MATRIX_CELL_COUNT)
        assertEquals(16, PresenceCatalog.matrix().size)
        assertEquals(4, PresenceCatalog.matrix().map { it.row }.distinct().size)
        assertEquals(4, PresenceCatalog.matrix().map { it.venue }.distinct().size)
    }

    @Test
    fun `参考矩阵不参与判定`() {
        val s = scene(presence = PresenceKind.SAME_SEX)
        val out = PresenceChecks.output(s)
        assertTrue(out.why.contains("在场者="))
        assertTrue(PresenceChecks.issues(s).isEmpty())
    }

    @Test
    fun `判定取最严不依赖声明次序`() {
        assertEquals(
            PresenceVerdict.DENY,
            PresenceVerdict.worst(PresenceVerdict.HINT, PresenceVerdict.DENY),
        )
        assertEquals(
            PresenceVerdict.HINT,
            PresenceVerdict.worst(PresenceVerdict.PASS, PresenceVerdict.HINT),
        )
    }

    private fun assertNotEqualOrder(a: AgeTier, b: AgeTier) {
        assertTrue(a.order != b.order)
    }
}
