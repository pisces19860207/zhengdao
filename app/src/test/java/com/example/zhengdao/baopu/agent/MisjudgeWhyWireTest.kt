// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十四批 · 归因机器键（`MisjudgeWhy.ofWire`）的变异看守。
//
// 这一跳的位置：账上存的是 `attribution:<wire>`（机器键），读数时靠 `ofWire` 认回人话。
// 认错了不会崩——**会静默把一类判错算到另一类头上**，归因读数因此失真（而且看起来还正常）。
// 所以三条：闭集每条都认回自己、未知键必须 null（不许默认成某一类）、键不许重复。
//
// 生产调用点：`AttributionWiring.readout`（读数一行）里两处（分组与"认不出 n 行"计数）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MisjudgeWhyWireTest {

    @Test
    fun `闭集往返_每一条都认回自己`() {
        MisjudgeWhy.entries.forEach { why ->
            assertEquals("机器键认不回自己：${why.wire}", why, MisjudgeWhy.ofWire(why.wire))
            assertTrue("线值不许是空串（空键会把所有空值混成一类）", why.wire.isNotBlank())
        }
        assertTrue("闭集至少三条（少一条说明有人删了判错类别）", MisjudgeWhy.entries.size >= 3)
    }

    @Test
    fun `未知键必须null_不许默认成某一类`() {
        listOf("", "made_up", "FIRST_REJECT", "first-reject", " first_reject").forEach { bad ->
            assertNull("认不出就必须 null（否则读数会把它们算进某一类）：[$bad]", MisjudgeWhy.ofWire(bad))
        }
        // 大小写不宽容：同一串大写不许被当成 FIRST_REJECT
        assertNotEquals(MisjudgeWhy.FIRST_REJECT, MisjudgeWhy.ofWire("FIRST_REJECT"))
    }

    @Test
    fun `线值不许重复`() {
        val wires = MisjudgeWhy.entries.map { it.wire }
        assertEquals("两条判错共用同一个机器键 ⇒ 读数永远分不开：$wires", wires.size, wires.toSet().size)
        val human = MisjudgeWhy.entries.map { it.cn }
        assertEquals("人话也不许重复（读数按人话聚合）", human.size, human.toSet().size)
    }
}
