// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十批 · 文字闸：票面点名的三句因果句必红、并列描述必过、颜色两层并一句必红。
// 每条闸都配负例（该过的要过、该红的要红）——不是「表里有个词」就算。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeGuardTest {

    @Test
    fun `因果句_票面三句必红`() {
        listOf("胖所以小", "矮所以短", "年纪大＝颜色深").forEach { text ->
            assertNotNull("「$text」必须被咬住", SizeGuard.causationHit(text))
            val e = assertThrows(IllegalArgumentException::class.java) { SizeGuard.rejectCausation(text) }
            assertTrue(e.message!!.startsWith(SizeGuard.CAUSATION_REJECT))
        }
    }

    @Test
    fun `因果句_埋没推尺寸也红`() {
        assertNotNull("埋没明显 ⇒ 尺寸小，属于判据③禁令",
            SizeGuard.causationHit("埋没明显，所以本体偏小"))
    }

    @Test
    fun `因果句_并列描述必须放行`() {
        assertNull("两个字段各说各的，不算推导",
            SizeGuard.causationHit("看起来埋没明显；本体尺寸偏小"))
        assertNull("否定句不是因果句", SizeGuard.causationHit("身高与尺寸无关"))
        assertNull("没有连接词就不算", SizeGuard.causationHit("偏胖记小"))
    }

    @Test
    fun `依据_提体格属性即红`() {
        assertEquals("身高", SizeGuard.derivedBasisHit("按身高推的"))
        assertEquals("体重", SizeGuard.derivedBasisHit("体重换算"))
        assertEquals("骨架", SizeGuard.derivedBasisHit("骨架宽"))
        assertNull(SizeGuard.derivedBasisHit("自述观测 2026-11"))
        assertNull(SizeGuard.derivedBasisHit("旧档案 2019"))
    }

    @Test
    fun `颜色两层_并成一句即红`() {
        assertNotNull(SizeGuard.mergedColorHit("颜色＝色变档2／色档3"))
        assertNotNull(SizeGuard.mergedColorHit("颜色档=深"))
        assertNull("分两句写就过", SizeGuard.mergedColorHit("色变档=无变化；色档=与肤色相同"))
        val e = assertThrows(IllegalArgumentException::class.java) {
            SizeGuard.rejectMergedColor("颜色＝色变档2／色档3")
        }
        assertTrue(e.message!!.startsWith(SizeGuard.MERGED_COLOR_REJECT))
    }

    @Test
    fun `结论行_自带四道闸`() {
        assertThrows(IllegalArgumentException::class.java) {
            SizeFinding(Verdict.DENY, "X-1", "胖所以小")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SizeFinding(Verdict.DENY, "X-2", "颜色＝色变档2／色档3")
        }
        assertEquals(
            "[红] X-3：两字段独立（" + SIZE_SOURCE + " " + SIZE_AS_OF + "）",
            SizeFinding(Verdict.DENY, "X-3", "两字段独立").line(),
        )
    }

    @Test
    fun `闸自检_连接词表与禁词表都不空`() {
        assertNotNull("因果闸不能是空表", SizeGuard.causationHit("胖所以小"))
        assertNotNull("依据闸不能是空表", SizeGuard.derivedBasisHit("身高"))
        assertNotNull("颜色闸不能是空表", SizeGuard.mergedColorHit("颜色档"))
    }
}
