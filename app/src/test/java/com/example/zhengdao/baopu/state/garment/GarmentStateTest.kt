// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创测试。
package com.example.zhengdao.baopu.state.garment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarmentStateTest {

    @Test
    fun `默认有内裤且无内裤假设都能成立`() {
        val with = GarmentDefaults.default()
        val without = GarmentDefaults.withoutUnderwear()
        assertTrue(with.innerLayer.exists)
        assertEquals(Wornness.SOFT, (with.innerLayer.wear as InnerWear.Present).level)
        assertFalse(without.innerLayer.exists)
        assertEquals(InnerWear.Absent, without.innerLayer.wear)
        assertTrue(GarmentChecks.resolve(GarmentDefaults.draft(true)).accepted)
        assertTrue(GarmentChecks.resolve(GarmentDefaults.draft(false)).accepted)
    }

    @Test
    fun `五维与内裤字段缺失逐项判红`() {
        val base = GarmentDefaults.draft(true)
        val drafts = listOf(
            base.copy(flyType = null),
            base.copy(innerLayer = null),
            base.copy(zipBehavior = null),
            base.copy(fabricStiffness = null),
            base.copy(openingStyle = null),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(exists = null)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(style = null)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(frontOpening = null)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(tightness = null)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(wear = null)),
        )
        drafts.forEach { draft ->
            val result = GarmentChecks.resolve(draft)
            assertFalse("缺字段必须红", result.accepted)
            assertNull(result.state)
            assertTrue(GarmentChecks.MISSING_FIELD in GarmentFixture.codes(result.issues))
        }
    }

    @Test
    fun `无内裤不是异常也不要求紧度与磨损`() {
        val result = GarmentChecks.resolve(GarmentDefaults.draft(false))
        assertTrue(result.accepted)
        assertTrue(result.issues.isEmpty())
        assertEquals(InnerStyle.NONE, requireNotNull(result.state).innerLayer.style)
        assertEquals(Tightness.NOT_APPLICABLE, requireNotNull(result.state).innerLayer.tightness)
    }

    @Test
    fun `无内裤时矛盾形状组合逐项判红`() {
        val base = GarmentDefaults.draft(false)
        val bad = listOf(
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(style = InnerStyle.TRIANGLE)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(frontOpening = true)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(tightness = Tightness.REGULAR)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(
                wear = InnerWear.Present(Wornness.NEW, drivers = setOf(WearDriver.HABIT)),
            )),
        )
        bad.forEach { draft ->
            assertFalse(GarmentChecks.resolve(draft).accepted)
            assertTrue(GarmentChecks.INNER_SHAPE in GarmentFixture.codes(GarmentChecks.resolve(draft).issues))
        }
    }

    @Test
    fun `有内裤必须给款式紧度与磨损`() {
        val base = GarmentDefaults.draft(true)
        val bad = listOf(
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(style = InnerStyle.NONE)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(tightness = Tightness.NOT_APPLICABLE)),
            base.copy(innerLayer = requireNotNull(base.innerLayer).copy(wear = InnerWear.Absent)),
        )
        bad.forEach { draft ->
            assertFalse(GarmentChecks.resolve(draft).accepted)
            assertTrue(GarmentChecks.INNER_SHAPE in GarmentFixture.codes(GarmentChecks.resolve(draft).issues))
        }
    }

    @Test
    fun `非拉链门禁的拉链行为必须不适用`() {
        FlyType.entries.filter { it != FlyType.ZIPPER }.forEach { fly ->
            val draft = GarmentDefaults.draft(true).copy(
                flyType = fly,
                zipBehavior = ZipBehavior.NOT_APPLICABLE,
                openingStyle = OpeningStyle.PULL_EDGE,
            )
            assertTrue("$fly 应成立", GarmentChecks.resolve(draft).accepted)
            val bad = draft.copy(zipBehavior = ZipBehavior.SELF_LOCK)
            assertTrue(GarmentChecks.ZIP_SHAPE in GarmentFixture.codes(GarmentChecks.resolve(bad).issues))
            val unzipBad = draft.copy(openingStyle = OpeningStyle.UNZIP)
            assertTrue(GarmentChecks.OPENING_SHAPE in GarmentFixture.codes(GarmentChecks.resolve(unzipBad).issues))
        }
        val zipperBad = GarmentDefaults.draft(true).copy(zipBehavior = ZipBehavior.NOT_APPLICABLE)
        assertTrue(GarmentChecks.ZIP_SHAPE in GarmentFixture.codes(GarmentChecks.resolve(zipperBad).issues))
    }

    @Test
    fun `磨损档位连续且松垮可与局部破洞并存`() {
        assertEquals(listOf(0, 1, 2, 3), Wornness.entries.map { it.level })
        val base = GarmentDefaults.draft(true)
        val holed = base.copy(innerLayer = requireNotNull(base.innerLayer).copy(
            wear = InnerWear.Present(Wornness.HOLED, HoleSite.CROTCH, setOf(WearDriver.HABIT)),
        ))
        assertTrue(GarmentChecks.resolve(holed).accepted)
        val both = holed.copy(innerLayer = requireNotNull(holed.innerLayer).copy(
            wear = InnerWear.Present(Wornness.SAGGING, HoleSite.CROTCH, setOf(WearDriver.HABIT)),
        ))
        val resolved = GarmentChecks.resolve(both)
        assertTrue(resolved.accepted)
        val wear = requireNotNull(resolved.state).innerLayer.wear as InnerWear.Present
        assertTrue(wear.sagging)
        assertEquals(HoleSite.CROTCH, wear.hole)
    }

    @Test
    fun `破洞必须点位置且新软档不能夹带破洞`() {
        val base = GarmentDefaults.draft(true)
        val noSite = base.copy(innerLayer = requireNotNull(base.innerLayer).copy(
            wear = InnerWear.Present(Wornness.HOLED, drivers = setOf(WearDriver.HABIT)),
        ))
        assertTrue(GarmentChecks.WEAR_HOLE in GarmentFixture.codes(GarmentChecks.resolve(noSite).issues))
        val newWithHole = base.copy(innerLayer = requireNotNull(base.innerLayer).copy(
            wear = InnerWear.Present(Wornness.NEW, HoleSite.SIDE_SEAM, setOf(WearDriver.HABIT)),
        ))
        assertTrue(GarmentChecks.WEAR_HOLE in GarmentFixture.codes(GarmentChecks.resolve(newWithHole).issues))
        val softWithHole = base.copy(innerLayer = requireNotNull(base.innerLayer).copy(
            wear = InnerWear.Present(Wornness.SOFT, HoleSite.CROTCH, setOf(WearDriver.HABIT)),
        ))
        assertTrue(GarmentChecks.WEAR_HOLE in GarmentFixture.codes(GarmentChecks.resolve(softWithHole).issues))
    }

    @Test
    fun `磨损缺驱动只提示不判红`() {
        val base = GarmentDefaults.draft(true)
        val noDriver = base.copy(innerLayer = requireNotNull(base.innerLayer).copy(
            wear = InnerWear.Present(Wornness.SOFT),
        ))
        val result = GarmentChecks.resolve(noDriver)
        assertTrue(result.accepted)
        assertNotNull(result.state)
        assertEquals(1, result.issues.size)
        assertEquals(GarmentIssueSeverity.WARNING, result.issues.single().severity)
        assertEquals(GarmentChecks.WEAR_DRIVER, result.issues.single().code)
    }

    @Test
    fun `五维枚举取值齐全`() {
        assertEquals(setOf("ZIPPER", "BUTTON", "TIE", "NONE", "SIDE_OPEN"), FlyType.entries.map { it.name }.toSet())
        assertEquals(setOf("NONE", "TRIANGLE", "BOXER"), InnerStyle.entries.map { it.name }.toSet())
        assertEquals(
            setOf("SELF_LOCK", "SMOOTH", "TEETH_GRIP", "NOT_APPLICABLE"),
            ZipBehavior.entries.map { it.name }.toSet(),
        )
        assertEquals(setOf("SOFT", "MEDIUM", "STIFF"), FabricStiffness.entries.map { it.name }.toSet())
        assertEquals(
            setOf("UNZIP", "PULL_EDGE", "WAISTBAND_REACH", "WAISTBAND_DOWN"),
            OpeningStyle.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `快照字段稳定且全部带garment前缀`() {
        val fields = GarmentDefaults.default().snapshotFields()
        val expected = setOf(
            "garment.layer", "garment.flyType", "garment.innerLayer.exists",
            "garment.innerLayer.style", "garment.innerLayer.frontOpening", "garment.innerLayer.tightness",
            "garment.innerLayer.wornness", "garment.innerLayer.hole", "garment.innerLayer.sagging",
            "garment.innerLayer.wearDrivers", "garment.zipBehavior", "garment.fabricStiffness",
            "garment.openingStyle",
        )
        assertEquals(expected, fields.keys)
        assertTrue(fields.keys.all { it.startsWith("garment.") })
        assertEquals("interface", fields.getValue("garment.layer"))
        assertEquals("ZIPPER", fields.getValue("garment.flyType"))
        assertEquals("true", fields.getValue("garment.innerLayer.exists"))
        assertEquals("BOXER", fields.getValue("garment.innerLayer.style"))
        assertEquals("false", fields.getValue("garment.innerLayer.frontOpening"))
        assertEquals("REGULAR", fields.getValue("garment.innerLayer.tightness"))
        assertEquals("SOFT", fields.getValue("garment.innerLayer.wornness"))
        assertEquals("none", fields.getValue("garment.innerLayer.hole"))
        assertEquals("false", fields.getValue("garment.innerLayer.sagging"))
        assertEquals("HABIT", fields.getValue("garment.innerLayer.wearDrivers"))
        assertEquals("SELF_LOCK", fields.getValue("garment.zipBehavior"))
        assertEquals("MEDIUM", fields.getValue("garment.fabricStiffness"))
        assertEquals("UNZIP", fields.getValue("garment.openingStyle"))
        val absent = GarmentDefaults.withoutUnderwear().snapshotFields()
        assertEquals("false", absent.getValue("garment.innerLayer.exists"))
        assertEquals("absent", absent.getValue("garment.innerLayer.wornness"))
        assertEquals("none", absent.getValue("garment.innerLayer.wearDrivers"))
        val holed = GarmentFixture.state(
            wear = InnerWear.Present(Wornness.HOLED, HoleSite.CROTCH, setOf(WearDriver.HABIT)),
        ).snapshotFields()
        assertEquals("HOLED", holed.getValue("garment.innerLayer.wornness"))
        assertEquals("CROTCH", holed.getValue("garment.innerLayer.hole"))
        assertEquals("false", holed.getValue("garment.innerLayer.sagging"))
        val sagging = GarmentFixture.state(
            wear = InnerWear.Present(
                Wornness.SAGGING,
                HoleSite.CROTCH,
                setOf(WearDriver.OCCUPATION, WearDriver.HABIT),
            ),
        ).snapshotFields()
        assertEquals("SAGGING", sagging.getValue("garment.innerLayer.wornness"))
        assertEquals("true", sagging.getValue("garment.innerLayer.sagging"))
        assertEquals("CROTCH", sagging.getValue("garment.innerLayer.hole"))
        assertEquals("HABIT,OCCUPATION", sagging.getValue("garment.innerLayer.wearDrivers"))
    }

    @Test
    fun `纯判定同输入同输出且不读时钟随机`() {
        val draft = GarmentDefaults.draft(true)
        val first = GarmentChecks.resolve(draft)
        val second = GarmentChecks.resolve(draft)
        assertEquals(first, second)
        assertEquals(requireNotNull(first.state).snapshotFields(), requireNotNull(second.state).snapshotFields())
        val scene = GarmentFixture.scene()
        assertEquals(
            GarmentChecks.assessPinch(requireNotNull(first.state), scene),
            GarmentChecks.assessPinch(requireNotNull(first.state), scene),
        )
    }
}
