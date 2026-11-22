// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ②：64 神经元固定拓扑的行为断言。
// 口径：规模、拓扑确定性（同 seed 同图 / 换 seed 换图）、权重符号、四档率单调、
// 放电率不超物理上限（每 3 步最多 1 次：1 步放电 + 2 步不应期）、同输入逐帧一致。
package com.example.zhengdao.baopu.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowNetTest {

    private val zones = listOf("glans", "shaft", "scrotum", "mons", "corona", "frenulum")

    @Test
    fun `每物类六十四神经元_四十八兴奋十六抑制`() {
        val net = ShadowNet("glans")
        assertEquals(64, net.neurons.size)
        assertEquals(48, net.neurons.count { it.excitatory })
        assertEquals(16, net.neurons.count { !it.excitatory })
        assertEquals(LifParams.N_TOTAL, LifParams.N_EXC + LifParams.N_INH)
    }

    @Test
    fun `同物类两实例拓扑逐边相同`() {
        val a = ShadowNet("glans")
        val b = ShadowNet("glans")
        for (post in 0 until LifParams.N_TOTAL) {
            assertTrue("第 $post 个神经元的入边源必须一致", a.presynapticOf(post).contentEquals(b.presynapticOf(post)))
            assertTrue("第 $post 个神经元的权重必须一致", a.weightsOf(post).contentEquals(b.weightsOf(post)))
        }
        assertEquals("偏置也必须一致", a.neurons.map { it.bias }, b.neurons.map { it.bias })
    }

    @Test
    fun `不同物类拓扑不同`() {
        // 负例守卫：物类没混进 seed 的话，所有物类会共用一张图（那就不是"每物类一张"了）
        val a = ShadowNet("glans")
        val b = ShadowNet("scrotum")
        val differs = (0 until LifParams.N_TOTAL).any {
            !a.presynapticOf(it).contentEquals(b.presynapticOf(it))
        }
        assertTrue("两个物类的入边源不该完全相同", differs)
    }

    @Test
    fun `入边无放回且不自连`() {
        for (zone in zones) {
            val net = ShadowNet(zone)
            for (post in 0 until LifParams.N_TOTAL) {
                val pre = net.presynapticOf(post)
                assertEquals("入边数固定 8", 8, pre.size)
                assertEquals("入边源不许重复", pre.size, pre.toSet().size)
                assertTrue("不许自连（post=$post）", post !in pre)
                assertTrue("入边源必须在 0..63", pre.all { it in 0 until LifParams.N_TOTAL })
            }
        }
    }

    @Test
    fun `权重符号按前后类型定`() {
        val net = ShadowNet("glans")
        for (post in 0 until LifParams.N_TOTAL) {
            val pre = net.presynapticOf(post)
            val w = net.weightsOf(post)
            for (k in pre.indices) {
                val sourceExc = pre[k] < LifParams.N_EXC
                if (sourceExc) {
                    assertTrue("兴奋入边必须为正（$post←${pre[k]}）", w[k] > 0)
                } else {
                    assertTrue("抑制入边必须为负（$post←${pre[k]}）", w[k] < 0)
                }
            }
        }
    }

    @Test
    fun `四档输入给出严格递增的末窗放电率`() {
        for (zone in zones) {
            val rounds = (0..Encode.MAX_TIER).map { tier ->
                ShadowNet(zone).runRound(Encode.currentOf(tier))
            }
            val rates = rounds.map { it.spikeRate }
            println(
                "[788B-rate] $zone rate=" + rates.joinToString(",") { "%.4f".format(it) } +
                    " 衰减=" + rounds.joinToString(",") { "%.6f".format(-it.adaptSlope) } +
                    " 平均电位=" + rounds.joinToString(",") { "%.3f".format(it.meanPotential) },
            )
            assertEquals("档 0 必须一步不放（$zone）", 0.0, rates[0], 0.0)
            for (i in 1 until rates.size) {
                assertTrue("$zone 的放电率必须随刺激档递增：$rates", rates[i] > rates[i - 1])
            }
            // 适应必须随强度变陡（0 档没有衰减，后面三档依次更陡）
            for (i in 2 until rounds.size) {
                assertTrue(
                    "$zone 的衰减必须随强度变陡：" + rounds.map { -it.adaptSlope },
                    -rounds[i].adaptSlope > -rounds[i - 1].adaptSlope,
                )
            }
        }
    }

    @Test
    fun `放电率不超过物理上限`() {
        // 每 3 步最多 1 次（1 步放电 + 2 步不应期）⇒ 群体率上界 1/3
        val cap = 1.0 / (1 + LifParams.REFRACTORY_STEPS)
        for (zone in zones) {
            for (tier in 0..Encode.MAX_TIER) {
                val round = ShadowNet(zone).runRound(Encode.currentOf(tier))
                assertTrue("$zone/$tier 的率 ${round.spikeRate} 超过上限 $cap", round.spikeRate <= cap)
                assertTrue("每窗率同理", round.windowRates.all { it <= cap })
            }
        }
    }

    @Test
    fun `窗口数等于步数除二十`() {
        val round = ShadowNet("glans").runRound(1.70, steps = 200)
        assertEquals(10, round.windowRates.size)
        val full = ShadowNet("glans").runRound(1.70)
        assertEquals(LifParams.MAX_STEPS / LifParams.STEPS_PER_WINDOW, full.windowRates.size)
    }

    @Test
    fun `同输入两跑逐帧一致`() {
        // 判据③：两跑比的是**帧文本**（窗口率 + 末窗率 + 平均电位 + 斜率），不是对象引用
        val first = ShadowNet("glans").runRound(1.70).frame()
        val second = ShadowNet("glans").runRound(1.70).frame()
        assertEquals("同 seed 同输入必须逐字相同", first, second)
        assertEquals(first, ShadowNet("glans", LifParams.SEED).runRound(1.70).frame())
    }

    @Test
    fun `换 seed 或换物类帧就变`() {
        val base = ShadowNet("glans").runRound(1.70).frame()
        assertNotEquals("换 seed 必须换帧", base, ShadowNet("glans", 0x1234L).runRound(1.70).frame())
        assertNotEquals("换物类必须换帧", base, ShadowNet("scrotum").runRound(1.70).frame())
    }

    @Test
    fun `步数越界当场抛`() {
        val net = ShadowNet("glans")
        for (bad in listOf(0, 19, LifParams.MAX_STEPS + 1)) {
            val error = runCatching { net.runRound(1.70, steps = bad) }.exceptionOrNull()
            assertTrue("步数 $bad 必须抛 IllegalArgumentException：$error", error is IllegalArgumentException)
        }
    }
}
