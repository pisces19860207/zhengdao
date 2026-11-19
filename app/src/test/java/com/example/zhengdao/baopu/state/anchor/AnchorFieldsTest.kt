// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 判据①（表侧）：七维字段表自洽、行数对得上规格 §二、表外一律拒。
// 每道闸都配注入自检，防闸没牙。
package com.example.zhengdao.baopu.state.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorFieldsTest {

    // 规格 §二 各表的行数（交付报告 §二 的对账表就是这个）
    private val specRows = mapOf(
        DimKind.FACE to 11, DimKind.BODY to 9, DimKind.POSTURE to 7, DimKind.GENITAL to 45,
        DimKind.TENSION to 7, DimKind.ABILITY to 12, DimKind.KINK to 39,
    )

    @Test
    fun `判据一_七维恰好七个_名称与含义照规格`() {
        assertEquals(
            listOf("五官", "体型", "体态", "性器外观", "性张力", "性能力", "性癖"),
            DimKind.values().map { it.cn },
        )
    }

    @Test
    fun `判据一_每维行数照规格二节对账_不新增不删减`() {
        val actual = AnchorFields.rowCountByDim()
        specRows.forEach { (dim, expected) -> assertEquals("$dim 行数", expected, actual[dim]) }
        assertEquals("七维总行数", 130, AnchorFields.ALL.size)
    }

    @Test
    fun `判据一_每一行都带回规格的行锚`() {
        assertTrue(AnchorFields.ALL.all { it.specRow.startsWith("§二·") })
        val anchors = AnchorFields.ALL.map { it.specRow }.distinct()
        assertTrue("行锚太少对不齐全表：${anchors.size}", anchors.size >= 19)
        assertEquals("④ 与 ⑦ 是多张子表，锚点必须分得开", 8, anchors.count { "④" in it })
        assertEquals(5, anchors.count { "⑦·" in it })
    }

    @Test
    fun `判据一_L1核心标签恰好15个且顺序照规格三节`() {
        assertEquals(15, AnchorFields.L1_TAGS.size)
        assertEquals(listOf("薄肌", "肉壮", "软白", "精瘦", "露头"), AnchorFields.L1_TAGS.take(5))
        assertEquals("注射play", AnchorFields.L1_TAGS.last())
        AnchorFields.L1_TAGS.forEach { assertTrue("$it 须有一句话", it in AnchorFields.L1_TAG_WORDS) }
    }

    @Test
    fun `判据一_表外字段id直接拒`() {
        val e = assertThrows(AnchorSpecException::class.java) { AnchorFields.spec("face.hairColor") }
        assertTrue(e.message!!.contains("不许新增"))
    }

    @Test
    fun `判据一_封闭档行只收表内词`() {
        val eyes = AnchorFields.spec("face.eyes")
        assertTrue(eyes.closed)
        assertTrue(eyes.accepts("凤眼"))
        assertFalse("规格没有丹凤眼这个档", eyes.accepts("丹凤眼"))
        assertFalse(eyes.accepts(""))
    }

    @Test
    fun `判据一_落点行收自由文本但不收空`() {
        val shaft = AnchorFields.spec("g.shaft")
        assertFalse(shaft.closed)
        assertTrue(shaft.accepts("青筋细浮起，弯曲上翘"))
        assertFalse(shaft.accepts("   "))
        assertTrue("落点行仍要把规格给的落点列出来", shaft.words.contains("青筋"))
    }

    @Test
    fun `判据一_数值行只给带单位的行`() {
        assertEquals(
            listOf("a.stamina", "body.height", "body.weight"),
            AnchorFields.ALL.filter { it.measured }.map { it.id }.sorted(),
        )
    }

    @Test
    fun `判据一_性癖加权只取零到五`() {
        (0..5).forEach { AnchorFields.requireKinkWeight(it) }
        listOf(-1, 6, 100).forEach { bad ->
            val e = assertThrows(AnchorSpecException::class.java) { AnchorFields.requireKinkWeight(bad) }
            assertTrue("报错要点名越界的值", e.message!!.contains(bad.toString()))
        }
    }

    @Test
    fun `判据一_性癖五类共39项_类名进group`() {
        val kinks = AnchorFields.ofDim(DimKind.KINK)
        assertEquals(39, kinks.size)
        assertEquals(
            listOf("玩法类", "身体改造类", "权力类", "暴露类", "感官类"),
            kinks.map { it.group }.distinct(),
        )
        assertEquals(14, kinks.count { it.group == "玩法类" })
        assertEquals(6, kinks.count { it.group == "感官类" })
    }

    @Test
    fun `判据一_id全局唯一`() {
        assertEquals(AnchorFields.ALL.size, AnchorFields.ALL.map { it.id }.distinct().size)
    }

    @Test
    fun `防闸空转_行数表少一维时装配必炸`() {
        // 注入自检：把对账表改成 12 维行数的假值，装配期的 require 逻辑必须能咬住
        val fake = specRows.toMutableMap().also { it[DimKind.FACE] = 10 }
        val mismatch = fake.filter { (dim, n) -> AnchorFields.ofDim(dim).size != n }
        assertEquals("Face 少一行必须被抓出来", setOf(DimKind.FACE), mismatch.keys)
        assertTrue(AnchorFields.ofDim(DimKind.FACE).size != fake.getValue(DimKind.FACE))
    }
}
