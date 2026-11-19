// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十八批 · 行为判据：档数／可观察特征、禁词、非医疗判断、
// 恢复必然回落、727 联动单调、依据带 source+asOf、确定性。
// 本测试零写盘（只读源码 + 纯函数），水位不受影响。
package com.example.zhengdao.baopu.state.injury

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjuryObservationTest {

    /** 判据②的靶子：禁词字面只作为断言清单住在测试里，不进任何生产源码与输出。 */
    private val banned = listOf("骨折", "内出血", "感染", "用药", "治疗", "预后")

    @Test
    fun `判据一_三档体系各四四三档且每档有可观察特征`() {
        assertEquals(4, InjuryGrade.COUNT)
        assertEquals(4, PainGrade.COUNT)
        assertEquals(3, RecoveryGrade.COUNT)
        assertEquals(4, InjuryGrade.values().size)
        assertEquals(4, PainGrade.values().size)
        assertEquals(3, RecoveryGrade.values().size)

        val all = InjuryGrade.values().map { it.label to it.observable } +
            PainGrade.values().map { it.label to it.observable } +
            RecoveryGrade.values().map { it.label to it.observable }
        assertEquals(11, all.size)
        for ((label, observable) in all) {
            assertTrue("$label 缺可观察特征", observable.isNotBlank())
        }
        assertTrue("色变档须写红与青", "红" in InjuryGrade.COLOR.observable && "青" in InjuryGrade.COLOR.observable)
        assertTrue("肿起档须写可见鼓起", "鼓起" in InjuryGrade.SWELL.observable)
        assertTrue("受限档须写动作做不了", "动作做不了" in InjuryGrade.LIMITED.observable)
        assertTrue("提示感须写一碰才知", "一碰" in PainGrade.TOUCH.observable)
        assertTrue("持续感须写不动也感", "不动" in PainGrade.STEADY.observable)
        assertTrue("影响动作须写动作变化", "动作" in PainGrade.MOTION.observable)
    }

    @Test
    fun `判据二_所有输出的六个禁词零命中`() {
        for (tier in 0..ImpactLink.MAX_TIER) {
            for (rounds in 0..RecoveryGrade.LONGEST_ROUNDS + 2) {
                val out = ObservableLine.render(tier, rounds)
                for (word in banned) assertFalse("输出含禁词 $word：$out", word in out)
            }
        }
    }

    @Test
    fun `判据三_每条输出自动带非医疗判断`() {
        for (tier in 0..ImpactLink.MAX_TIER) {
            for (rounds in listOf(0, 1, 3, 6, 9)) {
                val out = ObservableLine.render(tier, rounds)
                assertTrue("输出须带非医疗判断：$out", out.endsWith("（非医疗判断）"))
            }
        }
        assertTrue("未接入口也须带", ObservableLine.renderUnwired(0).endsWith("（非医疗判断）"))
    }

    @Test
    fun `判据四_时间夹具超过最慢档必然回落`() {
        for (tier in 0..ImpactLink.MAX_TIER) {
            val out = ObservableLine.render(tier, RecoveryGrade.LONGEST_ROUNDS + 1)
            assertTrue("tier=$tier 超时须回落：$out", GONE in out)
            assertTrue("回落后仍带免责：$out", out.endsWith("（非医疗判断）"))
        }
    }

    @Test
    fun `判据四_恢复档随轮次只降不升且终点回无`() {
        for (tier in 0..ImpactLink.MAX_TIER) {
            val start = ImpactLink.recoveryStart(tier)
            val seq = (0..RecoveryGrade.LONGEST_ROUNDS + 1).map { RecoveryModel.at(start, it) }
            for (i in 1 until seq.size) {
                val prev = seq[i - 1]?.level ?: 0
                val cur = seq[i]?.level ?: 0
                assertTrue("tier=$tier 第 $i 步回升了：$seq", cur <= prev)
            }
            assertNull("tier=$tier 终点未回落", seq.last())
        }
    }

    @Test
    fun `判据五_与727联动单调且端点到位`() {
        val injury = (0..ImpactLink.MAX_TIER).map { ImpactLink.injuryStart(it) }
        val pain = (0..ImpactLink.MAX_TIER).map { ImpactLink.painStart(it) }
        val recovery = (0..ImpactLink.MAX_TIER).map { ImpactLink.recoveryStart(it).level }
        for ((name, list) in listOf("伤情" to injury, "疼痛" to pain, "恢复" to recovery)) {
            for (i in 1 until list.size) {
                assertTrue("$name 起点随冲击档↑反而降了：$list", list[i] >= list[i - 1])
            }
        }
        assertEquals(0, injury.first())
        assertEquals(3, injury.last())
    }

    @Test
    fun `判据六_每档依据都带来源与时点`() {
        val all = InjuryGrade.values().map { it.source to it.asOf } +
            PainGrade.values().map { it.source to it.asOf } +
            RecoveryGrade.values().map { it.source to it.asOf }
        for ((source, asOf) in all) {
            assertTrue("source 不得为空", source.isNotBlank())
            assertTrue("asOf 不得为空", asOf.isNotBlank())
            assertEquals("2026-11-19", asOf)
        }
    }

    @Test
    fun `同输入两次渲染逐字节一致`() {
        assertEquals(ObservableLine.render(3, 2), ObservableLine.render(3, 2))
        assertEquals(ObservableLine.render(0, 0), ObservableLine.render(0, 0))
    }
}
