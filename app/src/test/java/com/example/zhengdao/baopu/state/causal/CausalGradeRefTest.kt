// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 判据⑥：不新造档——九维的区间与文案必须**等于**业主包，
// 两个没有枚举的维度（体感/日照）拿 657 的真跑输出反查档名。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.environment.HumiditySolver
import com.example.zhengdao.baopu.state.environment.SolarSolver
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.motion.ComHeightGrade
import com.example.zhengdao.baopu.state.motion.JointAngleGrade
import com.example.zhengdao.baopu.state.physiology.BodyTempShift
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.surfaceflow.FlowVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalGradeRefTest {

    @Test
    fun `判据六_九维恰九条且区间等于业主包`() {
        assertEquals("九维", 9, CausalDim.COUNT)
        assertEquals(InjuryGrade.COUNT, CausalDim.SITE_INJURY.count)
        assertEquals(PainGrade.COUNT, CausalDim.SENSE_PAIN.count)
        assertEquals(FlowVolume.COUNT, CausalDim.SKIN_WET.count)
        assertEquals(BodyTempShift.COUNT, CausalDim.SKIN_TEMP.count)
        assertEquals(Fatigue.COUNT, CausalDim.SENSE_FATIGUE.count)

        val joints = JointAngleGrade.values().map { it.level }
        assertEquals(joints.min(), CausalDim.POSE_JOINT.min)
        assertEquals(joints.max(), CausalDim.POSE_JOINT.max)
        val coms = ComHeightGrade.values().map { it.level }
        assertEquals(coms.min(), CausalDim.POSE_COM.min)
        assertEquals(coms.max(), CausalDim.POSE_COM.max)
    }

    @Test
    fun `判据六_每一维的档名都向业主包要`() {
        CausalDim.values().forEach { d ->
            (d.min..d.max).forEach { lv ->
                assertEquals("${d.label} 第 $lv 档的文案", ownerLabel(d, lv), d.labelOf(lv))
            }
        }
    }

    private fun ownerLabel(d: CausalDim, lv: Int): String = when (d) {
        CausalDim.SITE_INJURY -> InjuryGrade.of(lv).label
        CausalDim.SENSE_PAIN -> PainGrade.of(lv).label
        CausalDim.POSE_JOINT -> JointAngleGrade.of(lv).name
        CausalDim.POSE_COM -> ComHeightGrade.of(lv).name
        CausalDim.SKIN_WET -> FlowVolume.of(lv).label
        CausalDim.SKIN_TEMP -> BodyTempShift.of(lv).label
        CausalDim.SENSE_FATIGUE -> Fatigue.of(lv).label
        CausalDim.SENSE_FEEL -> feltLabelFrom657(lv)
        CausalDim.LIGHT -> lightLabelFrom657(lv)
    }

    /** 657 真跑：四个温度各取一档，档名必须与本地表逐字相同。 */
    private fun feltLabelFrom657(level: Int): String {
        val temps = listOf(5.0, 22.0, 30.0, 40.0)
        val state = HumiditySolver.sweatOf(0, temps[level], 40.0, 0.0, 0)
        return feltTempGrade(state.effects)?.let { feltTempLabel(it) }
            ?: error("657 没给 felt_temp")
    }

    /** 657 真跑：夜里与正午两个极端，档名必须与本地表逐字相同。 */
    private fun lightLabelFrom657(level: Int): String = when (level) {
        0 -> SolarSolver.solve(0.0, 172, 0.0).intensity
        3 -> SolarSolver.solve(0.0, 172, 12.0).intensity
        else -> LIGHT_LABELS[level]
    }

    @Test
    fun `判据六_体感四档与657真跑一致`() {
        val labels = (0..3).map { i ->
            val state = HumiditySolver.sweatOf(0, listOf(5.0, 22.0, 30.0, 40.0)[i], 40.0, 0.0, 0)
            feltTempGrade(state.effects)?.let { feltTempLabel(it) }
        }
        assertEquals("657 体感四档", FEEL_LABELS, labels)
    }

    @Test
    fun `判据六_日照两端的档名与657一致`() {
        assertEquals("夜里是无", "无", SolarSolver.solve(0.0, 172, 0.0).intensity)
        assertEquals("正午是强", "强", SolarSolver.solve(0.0, 172, 12.0).intensity)
        assertEquals("档名表与 657 对得上", LIGHT_LABELS[0], SolarSolver.solve(0.0, 172, 0.0).intensity)
        assertEquals("档名表与 657 对得上", LIGHT_LABELS[3], SolarSolver.solve(0.0, 172, 12.0).intensity)
    }

    @Test
    fun `判据六_事件类型十一条且父子关系自洽`() {
        assertEquals("十一类", 11, CausalKind.COUNT)
        val all = CausalKind.values()
        all.forEach { k ->
            assertTrue("${k.label} 写的维度必须存在", k.writes.all { it in CausalDim.values() })
            assertTrue("${k.label} 的父类必须存在", k.parents.all { it in all })
        }
        assertEquals("三种根", listOf(CausalKind.STRIKE, CausalKind.AMBIENT, CausalKind.ACT), all.filter { it.isRoot })
        assertTrue("非根必须有父类", all.filter { !it.isRoot }.all { it.parents.isNotEmpty() })
        assertEquals(
            "只有可连续重复的类型才允许把自己当父",
            setOf(CausalKind.GAIT, CausalKind.WET, CausalKind.TEMP, CausalKind.FEEL, CausalKind.FATIGUE, CausalKind.RECOVER),
            all.filter { it in it.parents }.toSet(),
        )
    }

    @Test
    fun `判据六_748机制七类与派工单逐字一致`() {
        assertEquals("七类", 7, InjuryMechanism.COUNT)
        assertEquals(
            listOf("钝击", "挤压", "扭转·牵拉", "摩擦·擦伤", "热（烫/冻）", "化学刺激", "撕裂·裂伤"),
            InjuryMechanism.values().map { it.label },
        )
    }
}
