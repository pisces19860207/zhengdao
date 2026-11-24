// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十批 · 五行读数：两列齐、硬度独立、占位、六档引用、三道闸。
package com.example.zhengdao.baopu.state.erect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ErectReadingTest {

    // ── 角度两列 ──────────────────────────────────────────────
    @Test
    fun `角度两态各自成列_line含疲软与勃起`() {
        val line = ErectFixtures.angle().line()
        assertTrue(line.contains("疲软="))
        assertTrue(line.contains("勃起="))
        assertTrue(line.contains(ErectAngle.FLAT.label))
        assertTrue(line.contains(ErectAngle.SLIGHT_UP.label))
    }

    @Test
    fun `角度注入度数即红`() {
        assertThrows(IllegalArgumentException::class.java) {
            ErectGuard.rejectAngleValue("上翘 30" + "°")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ErectGuard.rejectAngleValue("仰" + "角 45")
        }
    }

    @Test
    fun `角度方向档名不误杀`() {
        ErectAngle.entries.forEach { ErectGuard.rejectAngleValue(it.label) }
    }

    // ── 硬度与尺寸独立（判据②）──────────────────────────────────
    @Test
    fun `硬度读数只有硬度字段_不含大小`() {
        // 结构证明：HardnessState 的构造参数只有硬度档，没有 size 字段
        val h = ErectFixtures.hardness()
        assertTrue(h.line().contains("硬度="))
        assertFalse(h.line().contains("大小"))
        assertFalse(h.line().contains("增长"))
    }

    @Test
    fun `注入综合档说法即红`() {
        listOf("综合" + "档", "大而硬", "大小" + "即硬", "软硬" + "兼").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { ErectGuard.rejectMerged(bad) }
        }
    }

    @Test
    fun `正常硬度描述不误杀`() {
        ErectGuard.rejectMerged(ErectFixtures.hardness().line())
        ErectGuard.rejectMerged(ErectFixtures.size().line())
    }

    // ── 包皮位六档（判据④）─────────────────────────────────────
    @Test
    fun `包皮位六档逐字_表外即红`() {
        assertEquals6(PrepuceState.PREPUCE_LEVELS)
        PrepuceState.PREPUCE_LEVELS.forEach { lv ->
            val row = PrepuceState(lv, lv)
            assertTrue(row.line().contains(lv))
        }
        assertThrows(IllegalArgumentException::class.java) { PrepuceState("自造档", "完全露出") }
        assertThrows(IllegalArgumentException::class.java) { PrepuceState("完全覆盖", "自造档") }
    }

    private fun assertEquals6(levels: List<String>) {
        org.junit.Assert.assertEquals(
            listOf("完全覆盖", "几乎全部", "过半", "仅冠", "退至冠后", "完全露出"), levels,
        )
    }

    // ── 占位（判据⑤）──────────────────────────────────────────
    @Test
    fun `冠外扩行含两个pending占位`() {
        val line = ErectFixtures.corona().line()
        assertTrue(line.contains(PENDING_SUBZONE))
        assertTrue(line.contains(PENDING_SECTION))
    }

    @Test
    fun `自造占位单号即红`() {
        assertThrows(IllegalArgumentException::class.java) {
            ErectGuard.rejectInventedPlaceholder("子区 " + "pending:999")
        }
    }

    @Test
    fun `自造截面子区名即红`() {
        assertThrows(IllegalArgumentException::class.java) {
            ErectGuard.rejectInventedPlaceholder("给个" + "横截面" + "示意")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ErectGuard.rejectInventedPlaceholder("写个" + "子区名")
        }
    }

    @Test
    fun `合法占位不误杀`() {
        ErectGuard.rejectInventedPlaceholder("截面 " + PENDING_SECTION + "｜子区 " + PENDING_SUBZONE)
    }

    // ── 三道闸（判据③）─────────────────────────────────────────
    @Test
    fun `注入倍数与长度即红`() {
        assertTrue(ErectGuard.factorHit("增长 2" + "倍") != null)
        assertThrows(IllegalArgumentException::class.java) { ErectGuard.rejectFactor("约 3 c" + "m") }
    }
}
