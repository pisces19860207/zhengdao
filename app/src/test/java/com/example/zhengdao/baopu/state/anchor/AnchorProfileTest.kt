// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 判据①（档案侧）：缺维拒算并报缺哪一维；三级粒度只换视图、不丢数据。
// 夹具用规格 §四 用法 A 自己的例子（AnchorTestData.complete），不是测试自造的合法数据。
package com.example.zhengdao.baopu.state.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorProfileTest {

    private val full = AnchorTestData.complete()

    @Test
    fun `判据一_七维齐了才让算`() {
        full.requireComplete()
        assertEquals(0, full.missingDims().size)
        assertEquals(7, full.completeness())
    }

    @Test
    fun `判据一_缺维拒算并报缺哪几维`() {
        val e = assertThrows(AnchorSpecException::class.java) { AnchorTestData.sparse().requireComplete() }
        listOf("体态", "性器外观", "性能力", "性癖").forEach { assertTrue("报错须点名$it", e.message!!.contains(it)) }
        assertFalse("五官填了就不该出现在缺维里", e.message!!.contains("五官"))
        assertEquals(4, AnchorTestData.sparse().missingDims().size)
    }

    @Test
    fun `判据一_空档案缺满七维`() {
        assertEquals(DimKind.values().toList(), AnchorProfile("空卡").missingDims())
    }

    @Test
    fun `判据一_三级粒度来回切_存储一个字都不丢`() {
        val before = full.snapshot()
        val levels = listOf(Granularity.L1, Granularity.L2, Granularity.L3)
        repeat(3) { levels.forEach { full.view(it) } }
        assertEquals("切粒度动了存储", before, full.snapshot())
        assertTrue("L1 视图只渲染标签", full.view(Granularity.L1).size == full.labels.size)
        assertTrue("L2 视图渲染细项", full.view(Granularity.L2).size > full.labels.size)
        assertEquals(listOf("自由补充：左肋有块疤，是被打的"), full.view(Granularity.L3))
    }

    @Test
    fun `判据一_存储恒为全量_L1视图不收细项`() {
        assertEquals("标签视图只有 3 行", 3, full.view(Granularity.L1).size)
        assertEquals("但细项一条不少", 22, full.values.size)
        assertTrue("身高在 L2 视图里", full.view(Granularity.L2).any { it.contains("身高＝178.0cm") })
        assertTrue("性癖权重在 L2 视图里", full.view(Granularity.L2).any { it.contains("玩法类·尿道 sounding＝3") })
        assertTrue("落点自由描述在 L2 视图里", full.view(Granularity.L2).any { it.contains("青筋细浮起") })
    }

    @Test
    fun `判据一_L1标签视图带规格那一句话`() {
        assertEquals("薄肌｜有肌肉但不厚，见筋", full.view(Granularity.L1).first())
    }

    @Test
    fun `判据一_档填进落点行_拒`() {
        val e = assertThrows(AnchorSpecException::class.java) {
            full.copy(values = full.values + ("g.shaft" to listOf("直")))
        }
        assertTrue(e.message!!.contains("落点行"))
    }

    @Test
    fun `判据一_自由文本填进封闭行_拒并报表内档`() {
        val e = assertThrows(AnchorSpecException::class.java) {
            full.copy(texts = full.texts + ("face.eyes" to "丹凤眼"))
        }
        assertTrue(e.message!!.contains("封闭档行"))
        assertTrue("报错要把合法档列出来", e.message!!.contains("杏眼"))
    }

    @Test
    fun `判据一_数值只进带单位的行`() {
        val e = assertThrows(AnchorSpecException::class.java) {
            full.copy(numbers = full.numbers + ("body.frame" to 1.0))
        }
        assertTrue(e.message!!.contains("不是数值行"))
    }

    @Test
    fun `判据一_单选行给两个值_拒`() {
        val e = assertThrows(AnchorSpecException::class.java) { full.copy(values = full.values + ("body.frame" to listOf("窄", "宽"))) }
        assertTrue(e.message!!.contains("单选"))
    }

    @Test
    fun `判据一_多选行给两个值_收`() {
        val two = full.copy(values = full.values + ("posture.scent" to listOf("浓", "烟")))
        assertTrue(two.view(Granularity.L2).any { it.contains("气味＝浓+烟") })
    }

    @Test
    fun `判据一_表外词_表外id_空值_一律拒`() {
        assertThrows(AnchorSpecException::class.java) { full.copy(values = full.values + ("face.eyes" to listOf("丹凤眼"))) }
        assertThrows(AnchorSpecException::class.java) { full.copy(values = full.values + ("face.eyebrow2" to listOf("浓"))) }
        assertThrows(AnchorSpecException::class.java) { full.copy(values = full.values + ("face.nose" to emptyList())) }
        assertThrows(AnchorSpecException::class.java) { full.copy(texts = full.texts + ("g.crotch" to "  ")) }
        assertThrows(AnchorSpecException::class.java) { full.copy(labels = full.labels + "肌肉男") }
        assertThrows(AnchorSpecException::class.java) { full.copy(kinks = full.kinks + ("k.play.1" to 9)) }
        assertThrows(AnchorSpecException::class.java) { full.copy(kinks = full.kinks + ("face.nose" to 2)) }
        assertThrows(AnchorSpecException::class.java) { AnchorProfile("") }
    }

    @Test
    fun `判据一_快照逐行覆盖有数据的行`() {
        val lines = full.snapshot().lines().filter { it.contains("=") }
        val dataRows = full.values.size + full.numbers.size + full.texts.size + full.kinks.size
        assertTrue("快照行数＝标签行＋数据行（$lines/${dataRows + 3}）", lines.size >= dataRows + 3)
        assertTrue(full.snapshot().contains("body.height=178.0cm"))
        assertTrue(full.snapshot().contains("k.play.1=3"))
    }

    @Test
    fun `防闸空转_把数据挪到合法槽后必须不再拒`() {
        // 反证：同一份内容，放错槽拒、放对槽过——说明拒的是槽位，不是词本身
        assertThrows(AnchorSpecException::class.java) { full.copy(values = full.values + ("g.corona" to listOf("明显伞状"))) }
        val ok = full.copy(texts = full.texts + ("g.corona" to "后缘明显伞状"))
        ok.requireComplete()
    }
}
