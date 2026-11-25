// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十六批 · 727 占位回填：775 真 id、730 提睾档与双来源事件。
// 只读源码与纯函数，零写盘。
package com.example.zhengdao.baopu.state.impact

import com.example.zhengdao.SrcRoots
import com.example.zhengdao.baopu.state.subzone.SubzoneId
import com.example.zhengdao.baopu.state.thermo.CremastericGrade
import com.example.zhengdao.baopu.state.thermo.CremasterTrigger
import com.example.zhengdao.baopu.state.thermo.Direction
import com.example.zhengdao.baopu.state.thermo.HumidityBand
import com.example.zhengdao.baopu.state.thermo.Medium
import com.example.zhengdao.baopu.state.thermo.Posture
import com.example.zhengdao.baopu.state.thermo.ThermalBand
import com.example.zhengdao.baopu.state.thermo.Thermo
import com.example.zhengdao.baopu.state.thermo.ThermoInput
import com.example.zhengdao.baopu.state.thermo.WindBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactBackfillTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/impact/"
    private val files = listOf(
        "ImpactGrades.kt", "ImpactTable.kt", "ImpactMatrix.kt",
        "ImpactRoutine.kt", "ImpactChecks.kt", "ImpactSheet.kt",
    )

    @Test
    fun `占位回填_impact生产源码待775计数为零`() {
        val forbidden = listOf("pending:" + "775", "待 " + "775", "FINE_SUBZONE" + "_PENDING")
        val hits = files.flatMap { name ->
            val source = SrcRoots.find(dir + name).readText()
            forbidden.filter { source.contains(it) }.map { "$name:$it" }
        }
        assertEquals("impact 占位必须清零：$hits", 0, hits.size)
    }

    @Test
    fun `冠部宿主按775改为corona不沿用sulcus`() {
        assertEquals("corona", ImpactSite.CORONA.zoneId)
    }

    @Test
    fun `十四行真id映射完整且口缘显式归并内板粗桶`() {
        val expected = mapOf(
            SubzoneId.APEX to ImpactSite.GLANS,
            SubzoneId.GLANS_BODY to ImpactSite.GLANS,
            SubzoneId.GLANS_CROWN to ImpactSite.CORONA,
            SubzoneId.SULCUS to ImpactSite.CORONA,
            SubzoneId.FRENULUM to ImpactSite.FRENULUM,
            SubzoneId.SHAFT_PROXIMAL to ImpactSite.SHAFT,
            SubzoneId.SHAFT_MID to ImpactSite.SHAFT,
            SubzoneId.SHAFT_DISTAL to ImpactSite.SHAFT,
            SubzoneId.RADIX to ImpactSite.ROOT,
            SubzoneId.MEATUS_RIM to ImpactSite.MEATUS,
            SubzoneId.MEATUS_SLIT to ImpactSite.MEATUS,
            SubzoneId.PREPUCE_INNER to ImpactSite.PREPUCE_INNER,
            SubzoneId.PREPUCE_OUTER to ImpactSite.PREPUCE_OUTER,
            SubzoneId.PREPUCE_ORIFICE to ImpactSite.PREPUCE_INNER,
        )
        assertEquals(expected, SUBZONE_SITE)
        assertEquals(SubzoneId.entries.toSet(), SUBZONE_SITE.keys)
    }

    @Test
    fun `真id输入解析粗桶且与显式site冲突即拒绝`() {
        val inferred = impact(SubzoneId.PREPUCE_ORIFICE)
        assertEquals(ImpactSite.PREPUCE_INNER, inferred.site)
        assertEquals(SubzoneId.PREPUCE_ORIFICE, inferred.subzone)
        val agreed = impact(SubzoneId.SULCUS, site = ImpactSite.CORONA)
        assertEquals(ImpactSite.CORONA, agreed.site)
        val coarseOnly = ImpactSheet.of(impactInput(SubzoneId.SHAFT_MID).copy(subzone = null, site = ImpactSite.SHAFT))
        assertEquals(ImpactSite.SHAFT, coarseOnly.site)
        var rejected = false
        try {
            impact(SubzoneId.APEX, site = ImpactSite.SHAFT)
        } catch (error: IllegalArgumentException) {
            rejected = error.message.orEmpty().contains("子区")
        }
        assertTrue("真 id 与粗 site 冲突必须拒绝", rejected)
    }

    @Test
    fun `敏感与稳健集合按真id覆盖五区和四区`() {
        assertEquals(
            setOf(
                SubzoneId.APEX, SubzoneId.GLANS_BODY, SubzoneId.GLANS_CROWN,
                SubzoneId.SULCUS, SubzoneId.FRENULUM,
            ),
            SENSITIVE_SUBZONE_IDS,
        )
        assertEquals(
            setOf(
                SubzoneId.SHAFT_PROXIMAL, SubzoneId.SHAFT_MID,
                SubzoneId.SHAFT_DISTAL, SubzoneId.RADIX,
            ),
            ROBUST_SUBZONE_IDS,
        )
        assertTrue(ImpactMatrix.sensitivitySeparated())
    }

    @Test
    fun `七类力按真id重跑敏感后果严格高于柱身根部`() {
        for (force in ForceKind.entries) {
            val sensitive = SENSITIVE_SUBZONE_IDS.map {
                MATRIX_TIER.getValue(force)[SUBZONE_SITE.getValue(it).ordinal]
            }.filter { it >= 0 }
            val robust = ROBUST_SUBZONE_IDS.map {
                MATRIX_TIER.getValue(force)[SUBZONE_SITE.getValue(it).ordinal]
            }.filter { it >= 0 }
            if (sensitive.isNotEmpty() && robust.isNotEmpty()) {
                assertTrue("${force.label}：敏感最低档必须高于稳健最高档", sensitive.min() > robust.max())
            }
        }
    }

    @Test
    fun `同强度真id敏感后果高于每个柱身根部子区`() {
        val robust = ROBUST_SUBZONE_IDS.map { impact(it).consequence.level }
        for (id in SENSITIVE_SUBZONE_IDS) {
            assertTrue("${id.code} 后果必须高于全部稳健子区", impact(id).consequence.level > robust.max())
        }
    }

    @Test
    fun `同拍受击steps1读取730非零提睾档并传入727`() {
        val verdict = Thermo.judge(
            ThermoInput(
                thermal = ThermalBand.COOL,
                humidity = HumidityBand.NORMAL,
                wind = WindBand.STILL,
                medium = Medium.NONE,
                posture = Posture.STAND,
                trigger = CremasterTrigger.COLD,
                turgor = Direction.FLAT,
                sweatGrade = 1,
                steps = 1,
            ),
        )
        val tier = CremastericGrade.of(
            requireNotNull(verdict.reading).cremasteric,
        )
        assertTrue("同拍 steps=1 的 730 提睾档不得为零", tier.level > 0)
        val result = impact(SubzoneId.SHAFT_MID, cremastericTier = tier)
        assertTrue(result.event.justHit)
        assertEquals(tier, result.event.cremastericTier)
        assertTrue(result.event.cremasteric)
    }

    @Test
    fun `受击提睾事件同时携带730档和727后果档`() {
        val tier = CremastericGrade.UP
        val result = impact(SubzoneId.FRENULUM, cremastericTier = tier)
        assertEquals(tier, result.event.cremastericTier)
        assertEquals(result.consequence, result.event.consequenceTier)
        assertEquals(setOf("730", "727"), result.event.sources)
        val broken = listOf(
            result.copy(event = result.event.copy(cremasteric = false)),
            result.copy(event = result.event.copy(cremastericTier = CremastericGrade.LOOSE)),
            result.copy(event = result.event.copy(cremastericTier = CremastericGrade.ROOT)),
            result.copy(event = result.event.copy(consequenceTier = ConsequenceTier.NONE)),
            result.copy(event = result.event.copy(sources = setOf("730"))),
        )
        for (candidate in broken) {
            val eventFinding = ImpactChecks.findings(impactInput(SubzoneId.FRENULUM, tier), candidate)
                .single { it.code.contains("event") }
            assertEquals(FindingLevel.RED, eventFinding.level)
        }
        val input = impactInput(SubzoneId.FRENULUM, tier)
        assertFalse(ImpactChecks.findings(input, result).any { it.level == FindingLevel.RED })
    }

    private fun impactInput(
        subzone: SubzoneId,
        cremastericTier: CremastericGrade =
            CremastericGrade.LOOSE,
        site: ImpactSite? = null,
    ) = ImpactInput(
        speed = SpeedTier.FAST,
        mass = MassTier.HEAVY,
        area = AreaTier.POINT,
        cremastericTier = cremastericTier,
        subzone = subzone,
        site = site,
    )

    private fun impact(
        subzone: SubzoneId,
        cremastericTier: CremastericGrade =
            CremastericGrade.LOOSE,
        site: ImpactSite? = null,
    ) = ImpactSheet.of(impactInput(subzone, cremastericTier, site))
}
