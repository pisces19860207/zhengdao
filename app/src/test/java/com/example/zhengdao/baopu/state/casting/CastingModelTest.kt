// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十七批 · 卡模型契约：构造即校验、变更留痕可回放、确定性。
package com.example.zhengdao.baopu.state.casting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingModelTest {

    /** 不变量：卡不可变，with 返回新卡，原卡不变，留痕逐条追加。 */
    private fun base(): CastingCard = CastingCard(
        roleId = "R1", profileId = "P1", isMain = true,
        values = mapOf("face.shape" to listOf("鹅蛋"), "body.frame" to listOf("中")),
    )

    /** 判据①：填表外档构造即抛，不许落卡。 */
    @Test
    fun `判据一_表外档构造即拒`() {
        val err = runCatching {
            CastingCard("R", "P", false, mapOf("face.shape" to listOf("方脸")))
        }.exceptionOrNull()
        assertTrue("表外档必须抛：$err", err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("不许新造档"))
    }

    /** 判据①：无既有档字段不许填值。 */
    @Test
    fun `判据一_无既有档字段不许填值`() {
        val err = runCatching {
            CastingCard("R", "P", false, mapOf("hair.style" to listOf("短发")))
        }.exceptionOrNull()
        assertTrue("无既有档不许填：$err", err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("无既有封闭档"))
    }

    /** 判据②：变更留痕——两次 with 后 rev=2，序号 1、2 连续，from/to 可回放。 */
    @Test
    fun `判据二_变更留痕逐条可回放`() {
        val v1 = base().with("face.shape", listOf("方"), "第一次改脸型")
        val v2 = v1.with("body.frame", listOf("宽"), "第二次改骨架")
        assertEquals(0, base().revision)
        assertEquals(1, v1.revision)
        assertEquals(2, v2.revision)
        // 第一次改脸型：从原值「鹅蛋」→「方」
        assertEquals(listOf("鹅蛋"), v1.history[0].from)
        assertEquals(listOf("方"), v1.history[0].to)
        // v2 的 history[0] 仍是第一次改；第二次改骨架在 history[1]
        assertEquals(listOf("鹅蛋"), v2.history[0].from)
        assertEquals(listOf("方"), v2.history[0].to)
        assertEquals(listOf("中"), v2.history[1].from)
        assertEquals(listOf("宽"), v2.history[1].to)
        assertEquals(listOf(1, 2), v2.history.map { it.seq })
        assertTrue(v1.line() != v2.line())
    }

    /** 判据②：撤字段也算变更（回到未设定），留痕 from=原值 to=空。 */
    @Test
    fun `判据二_撤字段留痕`() {
        val v2 = base().with("face.shape", emptyList(), "删掉脸型设定")
        assertFalse(v2.has("face.shape"))
        assertEquals(listOf("鹅蛋"), v2.history[0].from)
        assertEquals(emptyList<String>(), v2.history[0].to)
    }

    /** 判据②：留痕序号不许断号——构造期拒。 */
    @Test
    fun `判据二_断号留痕构造即拒`() {
        val bad = CardChange(2, "R", "f", emptyList(), listOf("x"), "理由")
        val err = runCatching { CastingCard("R", "P", false, history = listOf(bad)) }.exceptionOrNull()
        assertTrue("断号必须抛：$err", err is IllegalArgumentException)
    }

    /** 判据②：空理由不许留痕。 */
    @Test
    fun `判据二_空理由拒`() {
        val err = runCatching { CardChange(1, "R", "f", emptyList(), listOf("x"), "  ") }.exceptionOrNull()
        assertTrue("空理由必须抛：$err", err is IllegalArgumentException)
    }

    /** 判据④：确定性——同卡两次 line() 逐字一致；同输入两次 with 得同结论。 */
    @Test
    fun `判据四_确定性双跑逐字一致`() {
        val card = base().with("face.eyes", listOf("单", "杏眼"), "补眼睛")
        assertEquals(card.line(), card.line())
        val a = base().with("face.shape", listOf("方"), "改")
        val b = base().with("face.shape", listOf("方"), "改")
        assertEquals(a.line(), b.line())
    }

    /** 判据⑤：预算回报 = 当前项数 / 40。 */
    @Test
    fun `判据五_预算回报`() {
        assertEquals(2 to 40, base().budget())
    }
}
