// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.taiji

import com.example.zhengdao.oc.OcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 消息列表「正序显示」核心纯逻辑单测：验证 [orderChronologically] 的收敛边界。
 *
 * 滚动跟随、浮标外观需真机看；但"顺序对不对"是可断言的逻辑，必须在 JVM 侧锁死——
 * 顺序错了是灾难性的（对话读起来是倒的），且极容易被后续重构悄悄改坏。
 */
class TaijiMessageOrderTest {

    private fun msg(id: String, t: Long?) =
        OcMessage(id = id, role = OcMessage.Role.USER, timeCreated = t)

    private fun ids(list: List<OcMessage>) = list.map { it.id }

    @Test
    fun `新在前的倒序输入被纠正为正序`() {
        // 数据层若给「新→老」，渲染层必须变成「老→新」
        val reversed = listOf(msg("c", 30L), msg("b", 20L), msg("a", 10L))
        assertEquals(listOf("a", "b", "c"), ids(orderChronologically(reversed)))
    }

    @Test
    fun `已经正序时原样返回同一实例`() {
        // 零改动：不得新建列表（否则每帧都触发下游重组）
        val ordered = listOf(msg("a", 10L), msg("b", 20L), msg("c", 30L))
        assertSame(ordered, orderChronologically(ordered))
    }

    @Test
    fun `缺失时间戳时保持数据层原序`() {
        // 有任一 null → 不排序。乱排会把消息打乱成"看起来随机"，比不排更糟。
        val list = listOf(msg("c", 30L), msg("x", null), msg("a", 10L))
        assertSame(list, orderChronologically(list))
    }

    @Test
    fun `时间戳相同时保持原相对顺序（稳定排序）`() {
        // b 时间最晚却在最前 → 需要重排；a/c 同值，重排后 a 必须仍在 c 之前（稳定）
        val list = listOf(msg("b", 20L), msg("a", 10L), msg("c", 10L))
        assertEquals(listOf("a", "c", "b"), ids(orderChronologically(list)))
    }

    @Test
    fun `空列表原样返回`() {
        val empty = emptyList<OcMessage>()
        assertSame(empty, orderChronologically(empty))
    }

    @Test
    fun `单条消息原样返回`() {
        val one = listOf(msg("only", 42L))
        assertSame(one, orderChronologically(one))
    }

    @Test
    fun `乱序输入收敛为严格升序`() {
        val messy = listOf(msg("b", 20L), msg("d", 40L), msg("a", 10L), msg("c", 30L))
        assertEquals(listOf("a", "b", "c", "d"), ids(orderChronologically(messy)))
    }
}
