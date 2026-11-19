// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 判据⑦：BODY／GENITAL 的数值一律从 660 读，不自造字段、不猜阈值。
// 660 已合入 main ⇒ 走真接（read）；另外两条路径也各钉一遍：
// · 660 有连续量但规格没给数值→档的阈值 ⇒ PARTIAL，且不给 usableAsNumber；
// · 660 压根没这个口径 ⇒ NOT_IN_660（未接入），值必须是 null，由七维表自持。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.baopu.state.form.GenitalInput
import com.example.zhengdao.baopu.state.form.HumanBodySpec
import com.example.zhengdao.baopu.state.form.Override
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorBodyBridgeTest {

    private val src = "660 形体输入卡·实测"
    private val day = "2026-11-19"

    private fun spec(
        height: Double = 178.0,
        weight: Double = 66.0,
        fat: Double = 12.0,
        muscle: Int? = 2,
        genital: GenitalInput = GenitalInput(),
    ) = HumanBodySpec(
        heightCm = height, weightKg = weight, bodyFatPct = fat, muscleGrade = muscle,
        shoulderRatio = Override(1.9, src, day), genital = genital,
    )

    private fun reading(id: String, s: HumanBodySpec? = spec()) = AnchorBodyBridge.read(s).first { it.fieldId == id }

    @Test
    fun `判据七_660已合_身高体重直接读数`() {
        val h = reading("body.height")
        assertEquals(BridgeStatus.CONNECTED, h.status)
        assertEquals(178.0, h.value!!, 0.001)
        assertEquals("cm", h.unit)
        assertTrue(h.usableAsNumber)
        assertEquals("HumanBodySpec.heightCm", h.sixSixtyPath)
        assertEquals("实测", h.origin)
        assertEquals(66.0, reading("body.weight").value!!, 0.001)
        assertTrue(AnchorBodyBridge.SIX_SIXTY_MERGED.contains("已合入 main"))
    }

    @Test
    fun `判据七_连续量给档待核_不猜阈值`() {
        val fat = reading("body.fat")
        assertEquals(BridgeStatus.PARTIAL, fat.status)
        assertEquals(12.0, fat.value!!, 0.001)
        assertFalse("没阈值就不许当档值用", fat.usableAsNumber)
        assertTrue(fat.note.contains("○ 待核"))
        assertEquals(BridgeStatus.PARTIAL, reading("body.muscle").status)
        assertEquals("推算", reading("body.muscle").origin)
    }

    @Test
    fun `判据七_660无口径的行标未接入且不给值`() {
        listOf("body.frame", "body.legs", "body.hands", "body.chest").forEach { id ->
            val r = reading(id)
            assertEquals("$id 应标未接入", BridgeStatus.NOT_IN_660, r.status)
            assertNull("未接入的行不许有值", r.value)
            assertTrue(r.sixSixtyPath.contains("660 无"))
        }
    }

    @Test
    fun `判据七_660未合时整表都是未接入`() {
        val all = AnchorBodyBridge.unavailable()
        assertEquals(AnchorBodyBridge.ROSTER.size, all.size)
        assertTrue(all.all { it.status == BridgeStatus.NOT_IN_660 })
        assertTrue(all.all { it.value == null })
    }

    @Test
    fun `判据七_卡上没带的值不补默认`() {
        val r = AnchorBodyBridge.read(HumanBodySpec(heightCm = 170.0, weightKg = 60.0, bodyFatPct = 18.0))
        assertEquals(BridgeStatus.CONNECTED, r.first { it.fieldId == "body.height" }.status)
        assertEquals("660 未给值", BridgeStatus.UNSET, r.first { it.fieldId == "g.overall" }.status)
        assertNull(r.first { it.fieldId == "g.overall" }.value)
        assertEquals(BridgeStatus.UNSET, r.first { it.fieldId == "g.testis" }.status)
    }

    @Test
    fun `判据七_覆盖值带着660自己的source与asOf`() {
        val g = GenitalInput(lengthMm = Override(140.0, "自查·软尺", day), girthMm = Override(112.0, "自查·软尺", day))
        val r = reading("g.overall", spec(genital = g))
        assertEquals(BridgeStatus.CONNECTED, r.status)
        assertEquals(140.0, r.value!!, 0.001)
        assertEquals("自查·软尺", r.source)
        assertEquals(day, r.asOf)
    }

    @Test
    fun `判据七_规格硬上限只核对不改写`() {
        val inside = GenitalInput(lengthMm = Override(140.0, src, day), girthMm = Override(112.0, src, day))
        assertTrue(AnchorBodyBridge.capCheck(spec(genital = inside)).isEmpty())
        val over = GenitalInput(lengthMm = Override(170.0, src, day), girthMm = Override(200.0, src, day))
        val report = AnchorBodyBridge.capCheck(spec(genital = over))
        assertEquals(2, report.size)
        assertTrue(report[0].contains("170.0mm"))
        assertTrue("周长要换成等效直径再比上限", report[1].contains("等效直径 63mm"))
    }

    @Test
    fun `判据七_名册覆盖BODY每一行与GENITAL的四个数值落点`() {
        val ids = AnchorBodyBridge.ROSTER.map { it.fieldId }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertTrue("$it 必须是七维表里的行", AnchorFields.known(it)) }
        val bodyRows = AnchorFields.ofDim(DimKind.BODY).map { it.id }
        assertTrue("BODY 九行一行都不许漏（判据⑦ 点名的就是这一维）：$bodyRows", ids.containsAll(bodyRows))
        assertTrue(ids.containsAll(listOf("g.overall", "g.scrotum", "g.testis", "g.shaft")))
        assertEquals(13, ids.size)
    }

    @Test
    fun `判据七_桥只读不改660`() {
        val original = spec()
        AnchorBodyBridge.read(original)
        AnchorBodyBridge.capCheck(original)
        assertEquals("桥不许写回 660 的输入", original, spec())
        assertEquals(178.0, original.heightCm, 0.001)
    }

    @Test
    fun `防闸空转_四档状态必须各有行`() {
        // 三档口径若只有一档被用到，read() 就退化成一张常数表；这里钉住每一档都真的有货
        val counts = AnchorBodyBridge.read(spec()).groupBy(BridgeReading::status).mapValues { it.value.size }
        listOf(BridgeStatus.CONNECTED, BridgeStatus.PARTIAL, BridgeStatus.NOT_IN_660).forEach {
            assertTrue("$counts 里 $it 一行没有", (counts[it] ?: 0) > 0)
        }
        val unset = AnchorBodyBridge.read(HumanBodySpec(170.0, 60.0, 18.0))
        assertTrue(unset.any { it.status == BridgeStatus.UNSET })
        assertThrows(AnchorSpecException::class.java) { AnchorFields.spec("body.thighGirth") }
    }

}
