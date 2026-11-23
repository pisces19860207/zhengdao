// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十七批 · 引用层契约：每个卡字段的档必须逐字引用既有档，不许新造。
package com.example.zhengdao.baopu.state.casting

import com.example.zhengdao.baopu.state.anchor.AnchorFields
import com.example.zhengdao.baopu.state.bodyparts.PecVolume
import com.example.zhengdao.baopu.state.physiology.SweatLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingRefsTest {

    /** 判据①：ANCHOR 字段的 allowed 必须与既有词表逐字相同（引用，不复制）。 */
    @Test
    fun `判据一_脸型档逐字等于747既有词表`() {
        val ref = CastingRefs.field("face.shape")
        assertEquals(AnchorFields.spec("face.shape").words.distinct(), ref.words())
        assertTrue(ref.accepts("鹅蛋"))
        assertFalse("表外档不许收", ref.accepts("方脸"))
    }

    /** 判据①：805 部位档逐字等于 PecVolume.values().label。 */
    @Test
    fun `判据一_部位档逐字等于805枚举`() {
        val ref = CastingRefs.field("part.pec.volume")
        assertEquals(PecVolume.values().map { it.label }.distinct(), ref.words())
    }

    /** 判据①：804 / 658 档逐字等于各自枚举。 */
    @Test
    fun `判据一_下体与生理档逐字等于既有枚举`() {
        assertEquals(Circumcision.values().map { it.label }.distinct(), CastingRefs.field("prepuce.circum").words())
        assertEquals(SweatLevel.values().map { it.label }.distinct(), CastingRefs.field("trace.sweat").words())
    }

    /** 判据①：无既有档的字段（头发/纹身/眼镜）allowed 空 ⇒ 不许填值。 */
    @Test
    fun `判据一_无既有档字段allowed为空`() {
        listOf("hair.style", "mark.tattoo", "mark.glasses").forEach { id ->
            val ref = CastingRefs.field(id)
            assertEquals("$id 应无既有档", RefSource.NONE, ref.source)
            assertTrue("$id allowed 必须空", ref.words().isEmpty())
            assertFalse("$id 不许收任何值", ref.accepts("任意"))
        }
    }

    /** 判据①：未知字段直接拒（新增维度红）。 */
    @Test
    fun `判据一_表外字段拒`() {
        val err = runCatching { CastingRefs.field("face.hairColor") }.exceptionOrNull()
        assertTrue("表外字段必须抛：$err", err is CastingSpecException)
        assertTrue(err!!.message!!.contains("不许新增"))
        assertFalse(CastingRefs.known("face.hairColor"))
    }

    /** 判据⑤：卡字段白名单 ≤40（粒度预算结构性封顶）。 */
    @Test
    fun `判据五_卡字段白名单不超40`() {
        assertTrue("卡字段 ${CastingRefs.CARD_FIELDS.size} 须 ≤40", CastingRefs.CARD_FIELDS.size <= 40)
        assertEquals("三连恰好三维", 3, CastingRefs.TRIO.size)
    }

    /** 标记位置档引用 805 八部位与 790 六方位，不新造位置词。 */
    @Test
    fun `标记位置档引用既有部位与方位`() {
        assertEquals(8, CastingRefs.field("mark.site").words().size)
        assertEquals(6, CastingRefs.field("mark.bearing").words().size)
    }
}
