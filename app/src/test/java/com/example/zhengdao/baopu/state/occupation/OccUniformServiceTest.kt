// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · ERRATA 段判据：军／警／消分细类（C6）＋只给类别不写番号参数（C12）。
//
// 三件要盯住的事：
// ① 细类不是装饰——三等级、六警种、四消种、六场景、四使命期、八种值班形态都得被某一行真取到；
// ② 特有字段只属于这三臂——别的行业填了就红（「其余行业不填」是双向的）；
// ③ 类别之外的字一个都不许多写：番号形态（序数黏部队单位字）、装备参数词，命中即红。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccUniformServiceTest {

    private val soldier = OccupationTable.byKey("conscript")!!

    private fun c6(rows: List<OccupationRow>) = OccupationGate.check(rows).filter { it.rule == "C6" }

    private fun c12(rows: List<OccupationRow>) = OccupationGate.check(rows).filter { it.rule == "C12" }

    @Test
    fun `正例_十八行的特有槽全齐`() {
        OccupationTable.withService().forEach { row ->
            val slot = row.service!!
            assertEquals("${row.key} 特有字段缺项：" + slot.missingNames(), emptyList<String>(), slot.missingNames())
            assertEquals("${row.key} 串臂", emptyList<String>(), slot.wrongArmPosts())
            assertTrue(row.hands!!.contains(Handoff.DUTY_WINDOW_883))
        }
    }

    @Test
    fun `军三个等级与六个场景都在表上`() {
        val army = OccupationTable.withService().filter { it.service?.arm == Arm.ARMY }
        assertEquals(8, army.size)
        ArmyRank.entries.forEach { rank ->
            assertTrue("${rank.label} 没有一行", army.any { it.service?.armyRank == rank })
        }
        ServiceScene.entries.forEach { scene ->
            assertTrue("${scene.label} 没有一行", army.any { it.service?.scene == scene })
        }
        DutyPhase.entries.forEach { phase ->
            assertTrue("${phase.label} 没有一行", army.any { it.service?.phase == phase })
        }
    }

    @Test
    fun `警六岗与消四岗都在表上`() {
        val police = OccupationTable.withService().filter { it.service?.arm == Arm.POLICE }
        val fire = OccupationTable.withService().filter { it.service?.arm == Arm.FIRE }
        assertEquals(6, police.size)
        assertEquals(4, fire.size)
        PolicePost.entries.forEach { post -> assertTrue(post.label, police.any { it.service?.policePost == post }) }
        assertTrue(police.all { it.service!!.policePost != null })
        FirePost.entries.forEach { post -> assertTrue(post.label, fire.any { it.service?.firePost == post }) }
    }

    @Test
    fun `八种值班形态四类负荷八件装备全部有落点`() {
        val slots = OccupationTable.withService().map { it.service!! }
        DutyCycle.entries.forEach { cycle -> assertTrue(cycle.label, slots.any { it.dutyCycle == cycle }) }
        BodyLoad.entries.forEach { load -> assertTrue(load.label, slots.any { it.bodyLoad?.contains(load) == true }) }
        EnvExtra.entries.forEach { extra -> assertTrue(extra.label, slots.any { it.envExtra?.contains(extra) == true }) }
        Gear.entries.forEach { gear -> assertTrue(gear.label, slots.any { it.gear?.contains(gear) == true }) }
        ReturnGap.entries.forEach { gap -> assertTrue(gap.label, slots.any { it.returnGap == gap }) }
    }

    @Test
    fun `别的行业一个都不填特有槽`() {
        OccupationTable.ROWS.filter { it.service == null }.forEach { row ->
            assertTrue("${row.key} 不是军警消却有 dutyCycle 交接", !row.hands!!.contains(Handoff.DUTY_WINDOW_883))
        }
        assertEquals(52, OccupationTable.ROWS.count { it.service == null })
    }

    @Test
    fun `生产表零涉密命中`() {
        assertEquals(0, c12(OccupationTable.ROWS).size)
        OccupationTable.withService().forEach { row ->
            row.texts().forEach { text ->
                OccWords.SECRET.forEach { word -> assertTrue("$text 含 $word", !text.contains(word)) }
            }
        }
    }

    @Test
    fun `负例_军警消行没有特有槽必红`() {
        val findings = c6(listOf(soldier.copy(service = null)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("没有特有槽"))
    }

    @Test
    fun `负例_别的行业填了特有槽必红`() {
        val findings = c6(listOf(OccupationTable.byKey("rebar")!!.copy(service = soldier.service)))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("别的行业填了特有槽"))
    }

    @Test
    fun `负例_串臂必红`() {
        val crossed = soldier.copy(service = soldier.service!!.copy(policePost = PolicePost.PATROL))
        val findings = c6(listOf(crossed))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("军填了警或消的岗位"))
    }

    @Test
    fun `负例_值班形态或时段档缺一格必红`() {
        val noCycle = c6(listOf(soldier.copy(service = soldier.service!!.copy(dutyCycle = null))))
        assertEquals(1, noCycle.size)
        assertTrue(noCycle.first().message.contains("值班形态"))
        val noGap = c6(listOf(soldier.copy(service = soldier.service!!.copy(returnGap = null))))
        assertEquals(1, noGap.size)
        assertTrue(noGap.first().message.contains("时段档"))
    }

    @Test
    fun `负例_军少了场景与使命期各报一条`() {
        val noScene = c6(listOf(soldier.copy(service = soldier.service!!.copy(scene = null))))
        assertEquals(1, noScene.size)
        assertTrue(noScene.first().message.contains("场景"))
        val noPhase = c6(listOf(soldier.copy(service = soldier.service!!.copy(phase = null))))
        assertEquals(1, noPhase.size)
        assertTrue(noPhase.first().message.contains("使命期"))
    }

    @Test
    fun `负例_警消不需要场景与使命期`() {
        val patrol = OccupationTable.byKey("patrolPolice")!!
        assertEquals(null, patrol.service!!.scene)
        assertEquals(null, patrol.service!!.phase)
        assertEquals(0, c6(listOf(patrol)).size)
    }

    @Test
    fun `负例_番号形态必红`() {
        listOf("第7旅的士官", "第3营士官", "7团士官").forEach { bad ->
            val findings = c12(listOf(soldier.copy(label = bad)))
            assertTrue("$bad 没被咬住", findings.isNotEmpty())
            assertTrue(findings.all { it.message.contains("序数") })
        }
    }

    @Test
    fun `负例_装备参数词必红`() {
        listOf("口径较大的枪械", "型号齐全", "行动代号已定").forEach { bad ->
            val findings = c12(listOf(soldier.copy(label = bad)))
            assertEquals(1, findings.size)
        }
    }

    @Test
    fun `负例_营区与连体不是番号形态`() {
        // 单字的「营」「连」在正常词里到处都是，只有序数黏上去才算命中——这条负例防的是误伤。
        assertEquals(0, c12(listOf(OccupationTable.byKey("borderPost")!!)).size)
        assertEquals(0, c12(listOf(OccupationTable.byKey("fireFighter")!!)).size)
        assertEquals(null, OccupationGate.check().firstOrNull { it.rule == "C12" })
    }

    @Test
    fun `特有字段与六维不重复计数`() {
        // 值班形态是特有字段，作息是六维⑤：两格各说各的，不许拿一个顶另一个。
        OccupationTable.withService().forEach { row ->
            assertTrue(row.six!!.sched != null)
            assertTrue(row.service!!.dutyCycle != null)
        }
    }
}
