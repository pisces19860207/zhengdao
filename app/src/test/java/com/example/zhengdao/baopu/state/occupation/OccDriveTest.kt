// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 判据②③：驱动只给方向，方向必须举得出物理机制，且个体差异档不许抽走。
//
// 「因人而异」不是一个方向——方向枚举里根本没有这一值，只有 ↑／↓／中性；
// 「个体差异大」也不是免责条款——它必须与方向同时出现，缺任一条都红。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccDriveTest {

    private val rebar = OccupationTable.byKey("rebar")!!

    @Test
    fun `正例_每行四轴都有方向与机制`() {
        OccupationTable.ROWS.forEach { row ->
            val d = row.drives!!
            DriveAxis.entries.forEach { axis ->
                assertTrue("${row.key} 缺 ${axis.label} 的方向", d.dirOf(axis) != null)
            }
            assertTrue("${row.key} 机制空", d.mechanisms != null && d.mechanisms.isNotEmpty())
            assertEquals("${row.key} 个体差异档缺失", Spread.LARGE, d.spread)
        }
        assertEquals(280, OccupationTable.ROWS.size * DriveAxis.COUNT)
    }

    @Test
    fun `方向只有三值_没有因人而异这一档`() {
        assertEquals(listOf("↑", "↓", "中性"), Dir.entries.map { it.label })
        assertEquals(3, Dir.COUNT)
    }

    @Test
    fun `每轴的去处写死_体型归815磨损归881`() {
        assertEquals("815", DriveAxis.BUILD.home)
        assertEquals("805+807", DriveAxis.SKIN.home)
        assertEquals("881", DriveAxis.WEAR.home)
        assertEquals("806+827", DriveAxis.SWEAT.home)
        assertEquals(setOf(Mech.LOAD), DriveAxis.mechanismsOf(DriveAxis.BUILD))
        assertEquals(setOf(Mech.FRICTION), DriveAxis.mechanismsOf(DriveAxis.WEAR))
        assertEquals(setOf(Mech.FRICTION, Mech.EXPOSURE), DriveAxis.mechanismsOf(DriveAxis.SKIN))
        assertEquals(setOf(Mech.HEAT, Mech.LOAD), DriveAxis.mechanismsOf(DriveAxis.SWEAT))
    }

    @Test
    fun `负例_抽掉一轴方向按轴名报`() {
        val poisoned = rebar.copy(drives = rebar.drives!!.copy(wear = null))
        val c3 = OccupationGate.check(listOf(poisoned)).filter { it.rule == "C3" }
        assertEquals(1, c3.size)
        assertTrue(c3.first().message.contains("衣物磨损"))
    }

    @Test
    fun `负例_机制集合清空必红`() {
        val c3 = OccupationGate.check(
            listOf(rebar.copy(drives = rebar.drives!!.copy(mechanisms = emptySet()))),
        ).filter { it.rule == "C3" }
        assertEquals(1, c3.size)
        assertTrue(c3.first().message.contains("举不出物理机制"))
    }

    @Test
    fun `负例_个体差异档抽走必红`() {
        val c3 = OccupationGate.check(
            listOf(rebar.copy(drives = rebar.drives!!.copy(spread = null))),
        ).filter { it.rule == "C3" }
        assertEquals(1, c3.size)
        assertTrue(c3.first().message.contains("个体差异档"))
    }

    @Test
    fun `负例_方向与机制不配套按轴报三条`() {
        // 全表里钢筋工四面都给了↑，却只留一个「发热」：体型·皮肤·衣物三轴都没了因果支撑。
        val c3 = OccupationGate.check(
            listOf(rebar.copy(drives = rebar.drives!!.copy(mechanisms = setOf(Mech.HEAT)))),
        ).filter { it.rule == "C3" }
        assertEquals(3, c3.size)
        assertTrue(c3.all { it.message.contains("没有机制支撑") })
        assertTrue(c3.any { it.message.contains("体型与肌肉") })
        assertTrue(c3.any { it.message.contains("皮肤与手") })
        assertTrue(c3.any { it.message.contains("衣物磨损") })
    }

    @Test
    fun `正例_四面全中性不需要因果故事`() {
        val flat = rebar.copy(
            drives = DriveSet(Dir.FLAT, Dir.FLAT, Dir.FLAT, Dir.FLAT, setOf(Mech.FRICTION), Spread.LARGE),
        )
        assertEquals(0, OccupationGate.check(listOf(flat)).count { it.rule == "C3" })
    }

    @Test
    fun `正例_汗量方向可以是下`() {
        // 冷链装卸给的是汗量↓：低温环境少出汗，同样是方向而不是「看情况」。
        val cold = OccupationTable.byKey("coldChainHandler")!!
        assertEquals(Dir.DOWN, cold.drives!!.sweat)
        assertEquals(0, OccupationGate.check(listOf(cold)).size)
    }

    @Test
    fun `本包不产出任何档位值_只给方向`() {
        // 判据④与 815 的分工：这一包不许出现体脂·围度·公斤·厘米这类值。
        val all = OccupationTable.ROWS.flatMap { it.texts() }
        listOf("体脂", "胸围", "腰围", "公斤", "千克", "厘米", "毫米", "BMI").forEach { token ->
            assertTrue("方向表里出现了具体值：$token", all.none { it.contains(token) })
        }
    }
}
