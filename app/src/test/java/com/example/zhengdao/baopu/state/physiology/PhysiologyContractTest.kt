// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十八批 · 判据②④⑤：三液分道独立、不给点值、与 735/732 不重复落账。
package com.example.zhengdao.baopu.state.physiology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysiologyContractTest {

    private val inputs = listOf(
        PhysioInputs.BASELINE,
        PhysioInputs(
            AmbientTempBand.HOT, HumidityBand.SATURATED, ActivityBand.INTENSE,
            DurationBand.PROLONGED, EmotionBand.INTENSE, IndividualBand.SENSITIVE,
        ),
        PhysioInputs(
            AmbientTempBand.COOL, HumidityBand.DRY, ActivityBand.MODERATE,
            DurationBand.LONG, EmotionBand.AROUSED, IndividualBand.TYPICAL,
        ),
    )

    // ── 判据② 三液分道 ────────────────────────────────────────────────────

    @Test
    fun `判据二_通道名册恰为汗唾液前液三条`() {
        assertEquals(listOf("sweat", "saliva", "preSeminal"), FluidCatalog.CHANNEL_NAMES)
        assertEquals("通道名不得重复", 3, FluidCatalog.CHANNEL_NAMES.toSet().size)
    }

    @Test
    fun `判据二_三液字段恰三条无合并字段`() {
        // JaCoCo 插桩会给类加一个合成的 $jacocoData 字段，数真字段前先剥掉合成字段。
        val names = FluidChannels::class.java.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("\$") }
            .map { it.name }.toSet()
        assertTrue("须有汗分道", names.contains("sweat"))
        assertTrue("须有唾液分道", names.contains("saliva"))
        assertTrue("须有前液分道", names.contains("preSeminal"))
        assertEquals("FluidChannels 只许三条字段，不许合并通道", 3, names.size)
        assertTrue("不许有合并通道字段", names.none { it.contains("Fluid") || it.contains("cretion") })
    }

    @Test
    fun `判据二_三液量档可各异互不合并`() {
        val s = LightPhysiologySolver.solve(inputs[2])
        val edited = s.copy(fluids = s.fluids.copy(
            sweat = FluidTrack(true, FluidAmount.COPIOUS, listOf(PhysioDriver.ACTIVITY_INTENSITY)),
            saliva = FluidTrack(true, FluidAmount.TRACE, listOf(PhysioDriver.EMOTION)),
            preSeminal = FluidTrack(false, FluidAmount.NONE, emptyList()),
        ))
        assertEquals(FluidAmount.COPIOUS, edited.fluids.sweat.amount)
        assertEquals(FluidAmount.TRACE, edited.fluids.saliva.amount)
        assertTrue(!edited.fluids.preSeminal.present)
        assertTrue("三液有/无与量档须自洽", PhysiologyChecks.inconsistentFluidTracks(edited).isEmpty())
    }

    @Test
    fun `判据二_笼统合并词被检查器判红_负例夹具`() {
        listOf("体液", "分泌物", "bodyFluid", "secretion").forEach { term ->
            assertTrue("必须咬住合并词 $term",
                PhysiologyChecks.genericMergeTerms("三液[$term:多]").contains(term))
        }
        assertTrue("正例前提：分道文本不含合并词",
            PhysiologyChecks.genericMergeTerms(PhysioRenderer.render(
                LightPhysiologySolver.solve(inputs[1]))).isEmpty())
    }

    @Test
    fun `判据二_渲染文本不出现笼统合并词`() {
        inputs.forEach { input ->
            val text = PhysioRenderer.render(LightPhysiologySolver.solve(input))
            assertTrue("渲染不得合并三液：$text",
                PhysiologyChecks.genericMergeTerms(text).isEmpty())
            assertTrue("三液必须分道列出：$text",
                text.contains("三液[汗=") && text.contains("/唾液=") && text.contains("/前液="))
        }
    }

    // ── 判据④ 不给点值 ────────────────────────────────────────────────────

    @Test
    fun `判据四_物理单位被检查器判红_负例夹具`() {
        listOf("37℃", "30°C", "80bpm", "5毫升", "5ml", "80次/分", "170厘米", "176cm", "70kg")
            .forEach { token ->
                assertTrue("必须咬住点值 $token",
                    PhysiologyChecks.pointValueUnits(token).isNotEmpty())
            }
        assertTrue("正例前提：纯档名文本无点值",
            PhysiologyChecks.pointValueUnits("体温偏移=偏高(环境温湿) 心率档=中动(活动强度)").isEmpty())
    }

    @Test
    fun `判据四_求解输出无任何点值单位`() {
        inputs.forEach { input ->
            val text = PhysioRenderer.render(LightPhysiologySolver.solve(input))
            assertTrue("产出不得含点值：$text", PhysiologyChecks.pointValueUnits(text).isEmpty())
        }
    }

    // ── 判据⑤ 与 735 / 732 不重复 ────────────────────────────────────────

    @Test
    fun `判据五_他单落账口被检查器判红_负例夹具`() {
        listOf("前列腺液", "精液", "射精", "排出", "肛管", "直肠").forEach { term ->
            assertTrue("必须咬住他单账 $term",
                PhysiologyChecks.foreignLedgerTerms(term).contains(term))
        }
    }

    @Test
    fun `判据五_三液通道名不与他单落账口重合`() {
        FluidCatalog.CHANNEL_NAMES.forEach { name ->
            assertFalse("三液通道 $name 不得是 735/732 的落账口",
                FluidCatalog.FOREIGN_LEDGER_TERMS.contains(name))
        }
    }

    @Test
    fun `判据五_渲染文本不出现他单落账口`() {
        inputs.forEach { input ->
            val text = PhysioRenderer.render(LightPhysiologySolver.solve(input))
            assertTrue("不得替 735/732 落账：$text",
                PhysiologyChecks.foreignLedgerTerms(text).isEmpty())
        }
    }
}
