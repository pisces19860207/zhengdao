// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 504 判据⑦的 JVM 钉子：**模型调解除 ⇒ 拒**。
 * HaltGate.lift 是解除的唯一通道：byHuman=false 的调用（模型/任何自动化路径）一律 false，
 * 且状态不动。解除的拒绝文案是常量（模型看到的原话）。
 *
 * JVM 里没有 SharedPreferences ⇒ 档位读写走真机/模拟器验证（504 报告里另附）；
 * 本文件钉的是"拒"的逻辑本身与钩子的拦截形状——它们不依赖 SP。
 */
class HaltGateSafetyHooksTest {

    @Test
    fun `模型调解除_拒`() {
        assertFalse("模型路径（byHuman=false）必须被拒", HaltGate.lift(byHuman = false, nowMs = 1L))
        // 原话要能拿得到：模型回灌的就是这一句
        assertTrue(HaltGate.LIFT_REFUSED_LINE.contains("人工"))
        assertTrue(HaltGate.LIFT_REFUSED_LINE.contains("拒绝"))
    }

    @Test
    fun `没装时档位是NONE_读口不崩`() {
        assertEquals(HaltLevel.NONE, HaltGate.level())
        assertFalse(HaltGate.halted())
        assertFalse(HaltGate.hardHalted())
        assertNull(HaltGate.record())
    }

    @Test
    fun `解除停机形状的工具名_不管停没停都原话拒绝`() {
        val hooks = SafetyWiring.hooks
        assertEquals(HaltGate.LIFT_REFUSED_LINE, hooks.intercept("lift_halt"))
        assertEquals(HaltGate.LIFT_REFUSED_LINE, hooks.intercept("解除停机"))
        assertEquals(HaltGate.LIFT_REFUSED_LINE, hooks.intercept("x_lift_halt_x")) // 含 lift_halt 变体
    }

    @Test
    fun `没停机时普通工具放行_档位映射认坏值为NONE`() {
        assertNull(SafetyWiring.hooks.intercept("read_file"))
        assertNull(SafetyWiring.hooks.intercept("write_file"))
        assertEquals(HaltLevel.NONE, HaltLevel.ofWire("garbage"))
        assertEquals(HaltLevel.HARD, HaltLevel.ofWire("hard"))
        assertEquals(HaltLevel.SOFT, HaltLevel.ofWire("soft"))
        assertNotNull(HaltLevel.entries.firstOrNull { it.wire == "none" })
    }

    @Test
    fun `档位单向加档的语义在枚举序上_硬停严于软停`() {
        assertTrue(HaltLevel.HARD.ordinal > HaltLevel.SOFT.ordinal)
    }
}
