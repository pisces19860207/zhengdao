// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十七批 · 判据③④⑥：连续性 / 鲜活 / 千人千面。
package com.example.zhengdao.baopu.state.casting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CastingChecksTest {

    private fun card(
        role: String, profile: String = "P1", main: Boolean = false,
        shape: String? = null, frame: String? = null,
    ): CastingCard {
        val v = LinkedHashMap<String, List<String>>()
        if (shape != null) v["face.shape"] = listOf(shape)
        if (frame != null) v["body.frame"] = listOf(frame)
        return CastingCard(role, profile, main, v)
    }

    // —— 判据③：连续性与卡比对 ——

    @Test
    fun `判据三_描写与卡一致判通过`() {
        val r = ContinuityCheck.check(card("R", shape = "鹅蛋"), mapOf("face.shape" to listOf("鹅蛋")))
        assertEquals(CheckState.PASS, r.state)
    }

    @Test
    fun `判据三_描写与卡矛盾判红`() {
        val r = ContinuityCheck.check(card("R", shape = "鹅蛋"), mapOf("face.shape" to listOf("方")))
        assertEquals(CheckState.RED, r.state)
        assertEquals("矛盾", r.code)
        assertTrue(r.detail.contains("矛盾"))
    }

    @Test
    fun `判据三_卡未设定提示可补卡不判红`() {
        val r = ContinuityCheck.check(card("R"), mapOf("face.shape" to listOf("鹅蛋")))
        assertEquals(CheckState.HINT, r.state)
        assertEquals("可补卡", r.code)
    }

    @Test
    fun `判据三_描写出现表外字段判红`() {
        val r = ContinuityCheck.check(card("R"), mapOf("face.hairColor" to listOf("金")))
        assertEquals(CheckState.RED, r.state)
        assertEquals("新维度", r.code)
    }

    @Test
    fun `判据三_描写出现表外档判红`() {
        val r = ContinuityCheck.check(card("R", shape = "鹅蛋"), mapOf("face.shape" to listOf("方脸")))
        assertEquals(CheckState.RED, r.state)
        assertEquals("新档", r.code)
    }

    @Test
    fun `判据三_多条取最严_红压过提示`() {
        val r = ContinuityCheck.check(
            card("R", shape = "鹅蛋"),
            mapOf("face.shape" to listOf("方"), "body.frame" to listOf("中")),
        )
        assertEquals(CheckState.RED, r.state)
    }

    @Test
    fun `判据三_同场不足三人不启用可辨差异`() {
        assertEquals(CheckState.PASS, ContinuityCheck.distinguishable(listOf(card("A"), card("B"))).state)
    }

    @Test
    fun `判据三_同场三人仅一项可辨判红`() {
        // A/B 只差脸型一项（体型都「中」），三人里出现这一对 ⇒ 红
        val cards = listOf(
            card("A", shape = "鹅蛋", frame = "中"),
            card("B", shape = "方", frame = "中"),
            card("C", shape = "长", frame = "中"),
        )
        val r = ContinuityCheck.distinguishable(cards)
        assertEquals(CheckState.RED, r.state)
        assertEquals("难辨", r.code)
        assertTrue(r.detail.contains("1 项"))
    }

    @Test
    fun `判据三_同场三人各两项可辨判通过`() {
        val cards = listOf(
            card("A", shape = "鹅蛋", frame = "窄"),
            card("B", shape = "方", frame = "中"),
            card("C", shape = "长", frame = "宽"),
        )
        assertEquals(CheckState.PASS, ContinuityCheck.distinguishable(cards).state)
    }

    @Test
    fun `判据三_未设定不算可辨差异`() {
        // 两两都只有一项「设定且不同」：脸型不同但体型都没设 ⇒ 红
        val cards = listOf(card("A", shape = "鹅蛋"), card("B", shape = "方"), card("C", shape = "长"))
        assertEquals(CheckState.RED, ContinuityCheck.distinguishable(cards).state)
    }

    // —— 判据④：鲜活判据 ——

    @Test
    fun `判据四_无痕迹提示像标本`() {
        val r = VitalityCheck.check(emptySet())
        assertEquals(CheckState.HINT, r.state)
        assertEquals("像标本", r.code)
        assertEquals(VitalityCheck.LIKE_SPECIMEN, r.detail)
    }

    @Test
    fun `判据四_命中出汗判鲜活`() {
        val r = VitalityCheck.check(setOf("trace.sweat"))
        assertEquals(CheckState.PASS, r.state)
        assertEquals("鲜活", r.code)
    }

    @Test
    fun `判据四五类痕迹都可用`() {
        setOf("trace.sweat", "trace.fatigue", "trace.breath", "trace.posture", "trace.skin").forEach { id ->
            assertEquals("$id 应算鲜活", CheckState.PASS, VitalityCheck.check(setOf(id)).state)
        }
    }

    @Test
    fun `判据四_表外痕迹字段判红`() {
        assertEquals(CheckState.RED, VitalityCheck.check(setOf("trace.mood")).state)
    }

    // —— 判据⑥：千人千面 ——

    @Test
    fun `判据六_同档案主要角色三人判红`() {
        val cards = listOf(
            card("A", "P1", true, "鹅蛋", "中"),
            card("B", "P1", true, "方", "宽"),
            card("C", "P1", true, "长", "窄"),
        )
        val r = UniquenessCheck.check(cards)
        assertEquals(CheckState.RED, r.state)
        assertEquals("同档案过多", r.code)
    }

    @Test
    fun `判据六_同档案主要角色两人不红`() {
        val cards = listOf(card("A", "P1", true, "鹅蛋", "中"), card("B", "P1", true, "方", "宽"))
        assertEquals(CheckState.PASS, UniquenessCheck.check(cards).state)
    }

    @Test
    fun `判据六_三连全同判红`() {
        val cards = listOf(
            card("A", "P1", true, "鹅蛋", "中"),
            card("B", "P2", true, "鹅蛋", "中"),
        )
        val r = UniquenessCheck.check(cards)
        assertEquals(CheckState.RED, r.state)
        assertEquals("三连全同", r.code)
    }

    @Test
    fun `判据六_脸型相同骨架不同不红`() {
        val cards = listOf(
            card("A", "P1", true, "鹅蛋", "中"),
            card("B", "P2", true, "鹅蛋", "宽"),
        )
        assertEquals(CheckState.PASS, UniquenessCheck.check(cards).state)
    }

    @Test
    fun `判据六_发型都未设定算同一维_脸型骨架全同仍红`() {
        // 头发无既有档 ⇒ 恒未设定；两人脸型+骨架全同 ⇒ 三连（含∅）全同 ⇒ 红
        val cards = listOf(card("A", "P1", true, "鹅蛋", "中"), card("B", "P2", true, "鹅蛋", "中"))
        assertEquals(CheckState.RED, UniquenessCheck.check(cards).state)
    }

    @Test
    fun `判据六_只看主要角色_配角不参与`() {
        val cards = listOf(
            card("A", "P1", true, "鹅蛋", "中"),
            card("B", "P1", false, "鹅蛋", "中"),
            card("C", "P1", false, "鹅蛋", "中"),
        )
        assertEquals(CheckState.PASS, UniquenessCheck.check(cards).state)
    }

    // —— 每场写后唯一出口 ——

    @Test
    fun `出口_四判据按固定次序汇总_红压过提示`() {
        val r = SceneCheck.check(
            sceneCards = listOf(card("A", shape = "鹅蛋")),
            observed = mapOf("face.shape" to listOf("鹅蛋")), // 一致 ⇒ PASS
            traces = emptySet(),                              // 像标本 ⇒ HINT
        )
        assertEquals(CheckState.HINT, r.overall) // 无红，提示压过通过
        assertEquals(CheckState.PASS, r.continuity.state)
        assertEquals(CheckState.HINT, r.vitality.state)
        assertEquals(4, r.line().lines().size)
    }

    @Test
    fun `出口_空场不崩_给可补卡不误判红`() {
        val r = SceneCheck.check(emptyList(), emptyMap(), emptySet())
        assertEquals(CheckState.HINT, r.overall)
    }

    @Test
    fun `出口_确定性双跑逐字一致`() {
        val args = { SceneCheck.check(listOf(card("A", shape = "鹅蛋")), mapOf("face.shape" to listOf("方")), setOf("trace.sweat")) }
        assertEquals(args().line(), args().line())
        assertEquals(CheckState.RED, args().overall)
    }
}
